package com.holderzone.hardware.camera.driver.uvc

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.Surface
import android.view.TextureView
import com.holderzone.hardware.camera.AvailableCamera
import com.holderzone.hardware.camera.CameraBackend
import com.holderzone.hardware.camera.CameraCapability
import com.holderzone.hardware.camera.CameraConfig
import com.holderzone.hardware.camera.CameraEvent
import com.holderzone.hardware.camera.CameraException
import com.holderzone.hardware.camera.CameraFrame
import com.holderzone.hardware.camera.CaptureKind
import com.holderzone.hardware.camera.CaptureRequest
import com.holderzone.hardware.camera.CaptureResult
import com.holderzone.hardware.camera.FrameDeliveryConfig
import com.holderzone.hardware.camera.LensFacing
import com.holderzone.hardware.camera.PreviewHost
import com.holderzone.hardware.camera.R
import com.holderzone.hardware.camera.UsbDeviceSelector
import com.holderzone.hardware.camera.UvcYuvLayout
import com.holderzone.hardware.camera.internal.saveAsJpeg
import com.holderzone.hardware.camera.internal.log.CameraLogger
import com.holderzone.hardware.camera.internal.spi.CameraDriver
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer

/**
 * UVC driver isolated behind the same SDK contract as the built-in backends.
 */
class UvcCameraDriver(
    private val appContext: Context,
    private val logger: CameraLogger,
) : CameraDriver {

    private companion object {
        const val TAG = "UvcCameraDriver"
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 360
        const val SNAPSHOT_FRAME_TIMEOUT_MILLIS = 2_000L
        const val AUTO_LAYOUT_MIN_SAMPLES = 3
        const val AUTO_LAYOUT_MIN_CONFIDENCE = 18
    }

    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    override val backend: CameraBackend = CameraBackend.UVC
    override val capabilities: CameraCapability = CameraCapability(
        switchLens = false,
        switchCamera = usbManager.deviceList.size > 1,
        snapshotCapture = true,
        frameStreaming = true,
        uvcSelection = true,
    )
    override val frames: Flow<CameraFrame>
        get() = frameFlow.asSharedFlow()
    override val events: Flow<CameraEvent>
        get() = eventFlow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eventFlow = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    private val frameFlow = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)

    private var previewHost: PreviewHost? = null
    private var textureView: TextureView? = null
    private var surface: Surface? = null
    private var usbMonitor: USBMonitor? = null
    private var currentConfig: CameraConfig? = null
    private var selectedDevice: UsbDevice? = null
    private var pendingControlBlock: USBMonitor.UsbControlBlock? = null
    private var openCamera: UVCCamera? = null
    private var previewWidth: Int = DEFAULT_WIDTH
    private var previewHeight: Int = DEFAULT_HEIGHT
    private var latestFrame: CameraFrame? = null
    private var captureWaiter: CompletableDeferred<CameraFrame>? = null
    private var startWaiter: CompletableDeferred<Unit>? = null
    private var resolvedAutoYuvLayout: UvcYuvLayout? = null
    private var autoLayoutSamples: Int = 0
    private var autoLayoutScore: Int = 0
    private var lastFrameAtMs: Long = 0L
    @Volatile
    private var closed = false

    override suspend fun bind(host: PreviewHost, config: CameraConfig) {
        ensureOpen()
        currentConfig = config
        previewHost = host
        textureView = TextureView(host.previewContext).apply {
            surfaceTextureListener = PreviewTextureListener()
        }
        host.attachPreview(textureView!!)
        ensureUsbMonitor()
    }

    override suspend fun start() {
        ensureOpen()
        val monitor = ensureUsbMonitor()
        if (!monitor.isRegistered) {
            monitor.register()
        }

        val target = resolveTargetDevice(monitor)
        selectedDevice = target
        val waiter = CompletableDeferred<Unit>()
        startWaiter = waiter
        val requestFailed = monitor.requestPermission(target)
        if (requestFailed) {
            startWaiter = null
            throw CameraException.DeviceUnavailableException("Failed to request USB permission for UVC camera.")
        }
        try {
            waiter.await()
        } finally {
            if (startWaiter === waiter) {
                startWaiter = null
            }
        }
    }

    override suspend fun stop() {
        releaseCamera()
        usbMonitor?.takeIf { it.isRegistered }?.unregister()
        eventFlow.emit(CameraEvent.PreviewStopped(backend))
    }

    override suspend fun switchLens(facing: LensFacing) {
        throw CameraException.ConfigurationException("UVC backend does not support lens switching.")
    }

    override suspend fun switchToNextCamera() {
        ensureOpen()
        val monitor = ensureUsbMonitor()
        val cameras = buildAvailableCameras(monitor)
        if (cameras.size < 2) {
            throw CameraException.DeviceUnavailableException("No alternate UVC camera is available.")
        }
        val currentId = resolveCurrentDeviceId(monitor)
        val currentIndex = cameras.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % cameras.size
        } else {
            0
        }
        val nextDevice = cameras[nextIndex]
        selectedDevice = monitor.deviceList.firstOrNull { device ->
            device.toAvailableCameraId() == nextDevice.id
        } ?: throw CameraException.DeviceUnavailableException("The next UVC camera is no longer available.")
        if (openCamera != null) {
            stop()
            start()
        }
    }

    override suspend fun queryAvailableCameras(): List<AvailableCamera> {
        ensureOpen()
        return buildAvailableCameras(ensureUsbMonitor())
    }

    override suspend fun capture(request: CaptureRequest): CaptureResult {
        ensureOpen()

        val file = resolveOutputFile(
            requestedFile = request.outputFile,
            prefix = "uvc_snapshot",
        )
        val config = currentConfig ?: CameraConfig()
        val frame = latestFrame ?: waitForFrame()
        frame.saveAsJpeg(file, config.jpegQuality, frame.rotationDegrees)
        return CaptureResult(
            path = file.absolutePath,
            kind = CaptureKind.SNAPSHOT,
            backend = backend,
        )
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        releaseCamera()
        usbMonitor?.takeIf { it.isRegistered }?.unregister()
        usbMonitor?.destroy()
        usbMonitor = null
        surface?.release()
        surface = null
        previewHost?.let { host ->
            textureView?.let(host::detachPreview)
        }
        previewHost = null
        textureView = null
        latestFrame = null
        captureWaiter?.cancel()
        startWaiter?.cancel()
        scope.cancel()
    }

    private fun ensureUsbMonitor(): USBMonitor {
        usbMonitor?.let { return it }
        val created = USBMonitor(appContext, DeviceListener())
        usbMonitor = created
        return created
    }

    private fun resolveTargetDevice(monitor: USBMonitor): UsbDevice {
        val candidates = monitor.deviceList
        if (candidates.isEmpty()) {
            throw CameraException.DeviceUnavailableException("No UVC device matches device_filter.xml.")
        }

        selectedDevice?.let { selected ->
            candidates.firstOrNull { it.deviceId == selected.deviceId }?.let { return it }
        }

        val selector = currentConfig?.usbDeviceSelector
        return when (selector) {
            null -> {
                candidates.first()
            }

            is UsbDeviceSelector.ByVidPid -> candidates.firstOrNull { device ->
                device.vendorId == selector.vendorId && device.productId == selector.productId
            } ?: throw CameraException.DeviceUnavailableException(
                "No UVC device matches VID=${selector.vendorId} PID=${selector.productId}."
            )
        }
    }

    private suspend fun waitForFrame(): CameraFrame {
        val waiter = CompletableDeferred<CameraFrame>()
        captureWaiter = waiter
        return try {
            withTimeout(SNAPSHOT_FRAME_TIMEOUT_MILLIS) {
                waiter.await()
            }
        } catch (exception: TimeoutCancellationException) {
            throw CameraException.CaptureFailureException("Timed out waiting for a UVC snapshot frame.", exception)
        } finally {
            if (captureWaiter === waiter) {
                captureWaiter = null
            }
        }
    }

    private fun openCamera(controlBlock: USBMonitor.UsbControlBlock) {
        if (closed) {
            controlBlock.close()
            return
        }
        releaseCamera()
        val camera = UVCCamera()
        try {
            camera.open(controlBlock)
            if (closed) {
                camera.close()
                camera.destroy()
                return
            }
            configurePreviewSize(camera)
            camera.setFrameCallback({ buffer ->
                handleFrame(buffer, currentConfig?.frameDeliveryConfig ?: FrameDeliveryConfig.DISABLED)
            }, UVCCamera.PIXEL_FORMAT_YUV420SP)
        } catch (throwable: Throwable) {
            runCatching { camera.close() }
            runCatching { camera.destroy() }
            throw throwable
        }
        openCamera = camera

        val currentSurface = surface
        if (closed) {
            releaseCamera()
            return
        } else if (currentSurface != null) {
            camera.setPreviewDisplay(currentSurface)
            if (closed) {
                releaseCamera()
                return
            }
            camera.startPreview()
            scope.launch {
                startWaiter?.complete(Unit)
                startWaiter = null
                eventFlow.emit(CameraEvent.PreviewStarted(backend))
            }
        } else {
            pendingControlBlock = controlBlock
        }
    }

    private fun configurePreviewSize(camera: UVCCamera) {
        val targetWidth = currentConfig?.captureSize?.width ?: DEFAULT_WIDTH
        val targetHeight = currentConfig?.captureSize?.height ?: DEFAULT_HEIGHT
        val candidates = camera.getSupportedSizeList()
            .takeIf { it.isNotEmpty() }
            ?: listOf(Size(0, 0, 0, targetWidth, targetHeight))
        val orderedSizes = chooseSizes(candidates, targetWidth, targetHeight)
        val formats = listOf(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV)
        for (size in orderedSizes) {
            for (format in formats) {
                try {
                    camera.setPreviewSize(size.width, size.height, format)
                    previewWidth = size.width
                    previewHeight = size.height
                    return
                } catch (_: Throwable) {
                    // Try the next supported size/format pair.
                }
            }
        }
        throw CameraException.PreviewBindingException(
            "Failed to configure a supported UVC preview size."
        )
    }

    private fun handleFrame(
        buffer: ByteBuffer,
        frameConfig: FrameDeliveryConfig,
    ) {
        if (closed) {
            return
        }
        try {
            val length = buffer.capacity()
            val bytes = ByteArray(length)
            buffer.rewind()
            buffer.get(bytes)
            val config = currentConfig ?: CameraConfig()
            val nv21 = normalizeUvcFrame(
                source = bytes,
                layout = config.uvcFrameConfig.yuvLayout,
                width = previewWidth,
                height = previewHeight,
            )
            val frame = CameraFrame(
                nv21 = nv21,
                width = previewWidth,
                height = previewHeight,
                rotationDegrees = config.frameRotationDegrees,
            )
            latestFrame = frame
            captureWaiter?.takeIf { !it.isCompleted }?.complete(frame)
            captureWaiter = null

            val now = System.currentTimeMillis()
            if (frameConfig.enabled && now - lastFrameAtMs >= frameConfig.minIntervalMillis) {
                lastFrameAtMs = now
                scope.launch {
                    frameFlow.emit(frame)
                }
            }
        } catch (throwable: Throwable) {
            scope.launch {
                eventFlow.emit(
                    CameraEvent.Error(
                        CameraException.CaptureFailureException(
                            "UVC frame pipeline failed.",
                            throwable,
                        )
                    )
                )
            }
        }
    }

    private fun releaseCamera() {
        pendingControlBlock = null
        openCamera?.let { camera ->
            runCatching {
                camera.setFrameCallback(null, 0)
            }
            runCatching {
                camera.stopPreview()
            }
            runCatching {
                camera.close()
            }
            runCatching {
                camera.destroy()
            }
        }
        openCamera = null
        resolvedAutoYuvLayout = null
        autoLayoutSamples = 0
        autoLayoutScore = 0
    }

    private fun normalizeUvcFrame(
        source: ByteArray,
        layout: UvcYuvLayout,
        width: Int,
        height: Int,
    ): ByteArray {
        return when (layout) {
            UvcYuvLayout.NV12_TO_NV21 -> nv12ToNv21(source)
            UvcYuvLayout.NV21_DIRECT -> source.copyOf()
            UvcYuvLayout.AUTO -> {
                val resolvedLayout = resolvedAutoYuvLayout
                    ?: resolveAutoYuvLayout(source, width, height)
                when (resolvedLayout) {
                    UvcYuvLayout.NV21_DIRECT -> source.copyOf()
                    UvcYuvLayout.AUTO,
                    UvcYuvLayout.NV12_TO_NV21,
                    -> nv12ToNv21(source)
                }
            }
        }
    }

    private fun resolveAutoYuvLayout(
        source: ByteArray,
        width: Int,
        height: Int,
    ): UvcYuvLayout {
        val frameSize = width * height
        if (source.size < frameSize * 3 / 2) {
            resolvedAutoYuvLayout = UvcYuvLayout.NV12_TO_NV21
            logger.warn(TAG, "UVC frame buffer is shorter than expected; falling back to NV12_TO_NV21.")
            return UvcYuvLayout.NV12_TO_NV21
        }

        val directScore = scoreUvLayout(source, width, height, directNv21 = true)
        val swappedScore = scoreUvLayout(source, width, height, directNv21 = false)
        autoLayoutSamples += 1
        autoLayoutScore += directScore - swappedScore

        val absoluteScore = kotlin.math.abs(autoLayoutScore)
        if (autoLayoutSamples >= AUTO_LAYOUT_MIN_SAMPLES && absoluteScore >= AUTO_LAYOUT_MIN_CONFIDENCE) {
            val resolved = if (autoLayoutScore > 0) {
                UvcYuvLayout.NV21_DIRECT
            } else {
                UvcYuvLayout.NV12_TO_NV21
            }
            resolvedAutoYuvLayout = resolved
            logger.debug(TAG, "Resolved UVC YUV layout as $resolved.")
            return resolved
        }

        return UvcYuvLayout.NV12_TO_NV21
    }

    private fun createOutputFile(prefix: String): File {
        val parent = File(appContext.cacheDir, "camera-sdk").apply { mkdirs() }
        return File(parent, "${prefix}_${System.currentTimeMillis()}.jpg")
    }

    private fun resolveOutputFile(
        requestedFile: File?,
        prefix: String,
    ): File {
        requestedFile?.let { file ->
            file.parentFile?.mkdirs()
            return file
        }
        return createOutputFile(prefix)
    }

    private fun ensureOpen() {
        if (closed) {
            throw CameraException.ClosedException()
        }
    }

    private fun buildAvailableCameras(monitor: USBMonitor): List<AvailableCamera> {
        val devices = monitor.deviceList
        val activeId = resolveCurrentDeviceId(monitor)
        return devices.mapIndexed { index, device ->
            AvailableCamera(
                index = index,
                id = device.toAvailableCameraId(),
                displayName = buildDisplayName(device, index),
                backend = backend,
                lensFacing = LensFacing.EXTERNAL,
                isActive = device.toAvailableCameraId() == activeId,
            )
        }
    }

    private fun resolveCurrentDeviceId(monitor: USBMonitor): String? {
        return selectedDevice?.toAvailableCameraId()
            ?: runCatching { resolveTargetDevice(monitor).toAvailableCameraId() }.getOrNull()
    }

    private inner class DeviceListener : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) = Unit

        override fun onDettach(device: UsbDevice) {
            if (!closed && device.deviceId == selectedDevice?.deviceId) {
                releaseCamera()
            }
        }

        override fun onConnect(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean,
        ) {
            if (closed) {
                ctrlBlock.close()
                return
            }
            if (device.deviceId != selectedDevice?.deviceId) {
                return
            }
            if (surface == null) {
                pendingControlBlock = ctrlBlock
                return
            }
            runCatching {
                openCamera(ctrlBlock)
            }.onFailure { throwable ->
                notifyStartFailure(
                    CameraException.DeviceUnavailableException(
                        "Failed to open the selected UVC device.",
                        throwable,
                    )
                )
            }
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            if (!closed && device.deviceId == selectedDevice?.deviceId) {
                releaseCamera()
                scope.launch {
                    eventFlow.emit(CameraEvent.PreviewStopped(backend))
                }
            }
        }

        override fun onCancel(device: UsbDevice) {
            if (!closed && device.deviceId == selectedDevice?.deviceId) {
                scope.launch {
                    startWaiter?.completeExceptionally(CameraException.PermissionDeniedException())
                    startWaiter = null
                }
            }
        }
    }

    private inner class PreviewTextureListener : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            surface = Surface(surfaceTexture)
            if (closed) {
                surface?.release()
                surface = null
                pendingControlBlock?.close()
                pendingControlBlock = null
                return
            }
            pendingControlBlock?.let { controlBlock ->
                pendingControlBlock = null
                runCatching {
                    openCamera(controlBlock)
                }.onFailure { throwable ->
                    notifyStartFailure(
                        CameraException.DeviceUnavailableException(
                            "Failed to open the selected UVC device after the preview surface became available.",
                            throwable,
                        )
                    )
                }
            }
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
    }

    private fun notifyStartFailure(exception: CameraException) {
        logger.error(TAG, exception.message ?: "UVC start failed.", exception)
        scope.launch {
            startWaiter?.takeIf { !it.isCompleted }?.completeExceptionally(exception)
            startWaiter = null
            eventFlow.emit(CameraEvent.Error(exception))
        }
    }
}

private fun nv12ToNv21(source: ByteArray): ByteArray {
    val output = source.copyOf()
    var index = source.size / 3 * 2
    while (index + 1 < output.size) {
        val u = output[index]
        output[index] = output[index + 1]
        output[index + 1] = u
        index += 2
    }
    return output
}

private fun scoreUvLayout(
    source: ByteArray,
    width: Int,
    height: Int,
    directNv21: Boolean,
): Int {
    val frameSize = width * height
    val chromaStart = frameSize
    val chromaEnd = (frameSize + frameSize / 2).coerceAtMost(source.size)
    if (chromaEnd - chromaStart < 2) {
        return 0
    }

    val stepX = (width / 12).coerceAtLeast(2)
    val stepY = (height / 12).coerceAtLeast(2)
    var score = 0
    var samples = 0
    var row = 0
    while (row < height) {
        var col = 0
        while (col < width) {
            val yIndex = row * width + col
            val uvRow = row / 2
            val uvCol = (col / 2) * 2
            val uvIndex = chromaStart + uvRow * width + uvCol
            if (yIndex < frameSize && uvIndex + 1 < chromaEnd) {
                val y = source[yIndex].toInt() and 0xff
                val first = source[uvIndex].toInt() and 0xff
                val second = source[uvIndex + 1].toInt() and 0xff
                val v = if (directNv21) first else second
                val u = if (directNv21) second else first
                val r = (y + 1.402f * (v - 128)).toInt().coerceIn(0, 255)
                val g = (y - 0.344136f * (u - 128) - 0.714136f * (v - 128)).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * (u - 128)).toInt().coerceIn(0, 255)
                score += colorPlausibilityScore(r, g, b)
                samples += 1
            }
            col += stepX
        }
        row += stepY
    }
    return if (samples == 0) 0 else score / samples
}

private fun colorPlausibilityScore(
    r: Int,
    g: Int,
    b: Int,
): Int {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val clippedChannels = listOf(r, g, b).count { it <= 3 || it >= 252 }
    var score = 0
    score -= clippedChannels * 5
    if (g > r + 70 && g > b + 70) {
        score -= 8
    }
    if (b > r + 95 && r < 80) {
        score -= 5
    }
    if (max - min <= 18) {
        score += 3
    }
    if (r in 16..245 && g in 16..245 && b in 16..245) {
        score += 2
    }
    return score
}

private fun chooseSizes(
    candidates: List<Size>,
    targetWidth: Int,
    targetHeight: Int,
): List<Size> {
    val targetPixels = targetWidth * targetHeight
    val targetAspectRatio = targetWidth.toFloat() / targetHeight.toFloat()
    return candidates
        .distinctBy { "${it.width}x${it.height}" }
        .sortedWith(
            compareBy<Size>(
                { kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - targetAspectRatio) },
                { kotlin.math.abs(it.width * it.height - targetPixels) },
            )
        )
}

private fun UsbDevice.toAvailableCameraId(): String {
    return "uvc:${vendorId}:${productId}:${deviceId}"
}

private fun buildDisplayName(
    device: UsbDevice,
    index: Int,
): String {
    val productName = device.productName?.takeIf { it.isNotBlank() } ?: "USB Camera"
    return "$productName ${index + 1}"
}
