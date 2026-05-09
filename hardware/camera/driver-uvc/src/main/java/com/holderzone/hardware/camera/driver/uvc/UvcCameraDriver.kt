package com.holderzone.hardware.camera.driver.uvc

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
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
import com.holderzone.hardware.camera.internal.log.CameraLogger
import com.holderzone.hardware.camera.internal.spi.CameraDriver
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
    }

    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    override val backend: CameraBackend = CameraBackend.UVC
    override val capabilities: CameraCapability = CameraCapability(
        switchLens = false,
        switchCamera = usbManager.deviceList.size > 1,
        stillCapture = false,
        previewSnapshot = true,
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
    private var previewWidth: Int = 1280
    private var previewHeight: Int = 720
    private var latestFrame: CameraFrame? = null
    private var captureWaiter: CompletableDeferred<CameraFrame>? = null
    private var startWaiter: CompletableDeferred<Unit>? = null
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
        if (request is CaptureRequest.RequireStill) {
            throw CameraException.CaptureFailureException(
                "UVC backend only supports preview snapshots in SDK v1."
            )
        }

        val file = resolveOutputFile(
            requestedFile = request.outputFile,
            prefix = "uvc_snapshot",
        )
        val frame = latestFrame ?: waitForFrame()
        saveFrame(frame, file)
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
        return waiter.await()
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
        try {
            camera.setPreviewSize(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG)
            previewWidth = 1280
            previewHeight = 720
        } catch (_: Throwable) {
            try {
                camera.setPreviewSize(1280, 720, UVCCamera.FRAME_FORMAT_YUYV)
                previewWidth = 1280
                previewHeight = 720
            } catch (_: Throwable) {
                camera.setPreviewSize(
                    UVCCamera.DEFAULT_PREVIEW_WIDTH,
                    UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                    UVCCamera.DEFAULT_PREVIEW_MODE,
                )
                previewWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH
                previewHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT
            }
        }
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
            val nv21 = nv12ToNv21(bytes)
            val frame = CameraFrame(
                nv21 = nv21,
                width = previewWidth,
                height = previewHeight,
                rotationDegrees = 0,
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
    }

    private fun saveFrame(frame: CameraFrame, file: File) {
        val output = ByteArrayOutputStream()
        val image = YuvImage(frame.nv21, ImageFormat.NV21, frame.width, frame.height, null)
        if (!image.compressToJpeg(Rect(0, 0, frame.width, frame.height), 95, output)) {
            throw CameraException.CaptureFailureException("Failed to encode UVC snapshot from NV21.")
        }
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { stream ->
            stream.write(output.toByteArray())
        }
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
