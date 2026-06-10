@file:Suppress("DEPRECATION")

package com.holderzone.hardware.camera.driver.camera1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.holderzone.hardware.camera.AvailableCamera
import com.holderzone.hardware.camera.CameraBackend
import com.holderzone.hardware.camera.CameraCapability
import com.holderzone.hardware.camera.CameraConfig
import com.holderzone.hardware.camera.CameraEvent
import com.holderzone.hardware.camera.CameraException
import com.holderzone.hardware.camera.CameraFrame
import com.holderzone.hardware.camera.CameraSize
import com.holderzone.hardware.camera.CaptureKind
import com.holderzone.hardware.camera.CaptureRequest
import com.holderzone.hardware.camera.CaptureResult
import com.holderzone.hardware.camera.FrameDeliveryConfig
import com.holderzone.hardware.camera.LensFacing
import com.holderzone.hardware.camera.PreviewHost
import com.holderzone.hardware.camera.internal.log.CameraLogger
import com.holderzone.hardware.camera.internal.saveAsJpeg
import com.holderzone.hardware.camera.internal.spi.CameraDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume

/**
 * Camera1 compatibility driver.
 *
 * Camera1 is deprecated by Android, but remains useful as a fallback for older or vendor-custom
 * devices whose Camera2/CameraX metadata does not match the physical camera layout.
 */
class Camera1CameraDriver(
    private val appContext: Context,
    private val logger: CameraLogger,
) : CameraDriver {

    private companion object {
        val DEFAULT_CAPTURE_SIZE = CameraSize.HD_720P
        const val SNAPSHOT_FRAME_TIMEOUT_MILLIS = 2_000L
        const val CAMERA_RELEASE_SETTLE_MILLIS = 200L
        const val SURFACE_READY_TIMEOUT_MILLIS = 5_000L
        const val OPEN_RETRY_COUNT = 1
    }

    override val backend: CameraBackend = CameraBackend.CAMERA_1
    override val capabilities: CameraCapability = CameraCapability(
        switchLens = cameraCount() > 1,
        switchCamera = cameraCount() > 1,
        snapshotCapture = true,
        frameStreaming = true,
        uvcSelection = false,
    )

    private val eventFlow = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    private val frameFlow = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationMutex = Mutex()

    override val events: Flow<CameraEvent> = eventFlow.asSharedFlow()
    override val frames: Flow<CameraFrame> = frameFlow.asSharedFlow()

    private var previewHost: PreviewHost? = null
    private var surfaceView: SurfaceView? = null
    private var camera: Camera? = null
    private var currentConfig: CameraConfig? = null
    private var currentLensFacing: LensFacing = LensFacing.BACK
    private var selectedCameraId: Int? = null
    private var activeCameraId: Int? = null
    private var latestFrame: CameraFrame? = null
    private var captureWaiter: CompletableDeferred<CameraFrame>? = null
    private var lastFrameAtMs: Long = 0L
    private var previewActive = false
    private var closed = false

    override suspend fun bind(host: PreviewHost, config: CameraConfig) = operationMutex.withLock {
        ensureOpen()
        currentConfig = config
        currentLensFacing = config.lensFacing
        selectedCameraId = null
        activeCameraId = null
        previewHost = host
        val view = SurfaceView(host.previewContext)
        surfaceView = view
        host.attachPreview(view)
    }

    override suspend fun start() = operationMutex.withLock {
        ensureOpen()
        ensurePermission()
        if (previewActive && camera != null) {
            return
        }

        val view = surfaceView ?: throw CameraException.PreviewBindingException(
            "Camera1 preview host is not bound."
        )
        val holder = awaitSurfaceHolder(view)
        val cameraId = selectedCameraId ?: selectCameraId(currentLensFacing)
        val config = currentConfig ?: CameraConfig()
        val openedCamera = openCameraWithRetry(cameraId)
        try {
            ensureOpen()
            withContext(Dispatchers.Main.immediate) {
                ensureOpen()
                configureAndStartCamera(
                    openedCamera = openedCamera,
                    holder = holder,
                    cameraId = cameraId,
                    config = config,
                )
            }
        } catch (throwable: Throwable) {
            releaseCamera(openedCamera)
            delay(CAMERA_RELEASE_SETTLE_MILLIS)
            throw throwable
        }
        eventFlow.emit(CameraEvent.PreviewStarted(backend))
    }

    override suspend fun stop() = operationMutex.withLock {
        ensureOpen()
        stopInternal(emitEvent = true)
    }

    override suspend fun switchLens(facing: LensFacing) = operationMutex.withLock {
        ensureOpen()
        if (!capabilities.switchLens) {
            throw CameraException.ConfigurationException("Camera1 backend does not support lens switching.")
        }
        currentLensFacing = facing
        selectedCameraId = null
        if (previewActive || camera != null) {
            stopInternal(emitEvent = true)
            startInternalAfterSwitch()
        }
    }

    override suspend fun switchToNextCamera() = operationMutex.withLock {
        ensureOpen()
        val cameras = buildAvailableCameras()
        if (cameras.size < 2) {
            throw CameraException.DeviceUnavailableException("No alternate Camera1 camera is available.")
        }
        val currentId = resolveCurrentCameraId(cameras)
        val currentIndex = cameras.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % cameras.size
        } else {
            0
        }
        val nextCamera = cameras[nextIndex]
        selectedCameraId = nextCamera.id.toIntOrNull()
        nextCamera.lensFacing?.let { currentLensFacing = it }
        if (previewActive || camera != null) {
            stopInternal(emitEvent = true)
            startInternalAfterSwitch()
        }
    }

    override suspend fun queryAvailableCameras(): List<AvailableCamera> {
        ensureOpen()
        return buildAvailableCameras()
    }

    override suspend fun capture(request: CaptureRequest): CaptureResult {
        ensureOpen()
        val file = resolveOutputFile(
            requestedFile = request.outputFile,
            prefix = "camera1_snapshot",
        )
        val config = currentConfig ?: CameraConfig()
        val frame = latestFrame ?: awaitSnapshotFrame()
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

        runCatching {
            camera?.setPreviewCallbackWithBuffer(null)
        }.onFailure {
            logger.error("Camera1CameraDriver", "Failed to clear Camera1 preview callback.", it)
        }
        runCatching {
            camera?.stopPreview()
        }.onFailure {
            logger.error("Camera1CameraDriver", "Failed to stop Camera1 preview.", it)
        }
        runCatching {
            camera?.release()
        }.onFailure {
            logger.error("Camera1CameraDriver", "Failed to release Camera1.", it)
        }

        previewHost?.let { host ->
            surfaceView?.let(host::detachPreview)
        }
        camera = null
        previewHost = null
        surfaceView = null
        activeCameraId = null
        selectedCameraId = null
        latestFrame = null
        captureWaiter?.cancel()
        captureWaiter = null
        previewActive = false
        scope.cancel()
    }

    private suspend fun startInternalAfterSwitch() {
        ensurePermission()
        val view = surfaceView ?: throw CameraException.PreviewBindingException(
            "Camera1 preview host is not bound."
        )
        val holder = awaitSurfaceHolder(view)
        val cameraId = selectedCameraId ?: selectCameraId(currentLensFacing)
        val config = currentConfig ?: CameraConfig()
        val openedCamera = openCameraWithRetry(cameraId)
        try {
            ensureOpen()
            withContext(Dispatchers.Main.immediate) {
                ensureOpen()
                configureAndStartCamera(openedCamera, holder, cameraId, config)
            }
        } catch (throwable: Throwable) {
            releaseCamera(openedCamera)
            delay(CAMERA_RELEASE_SETTLE_MILLIS)
            throw throwable
        }
        eventFlow.emit(CameraEvent.PreviewStarted(backend))
    }

    private fun configureAndStartCamera(
        openedCamera: Camera,
        holder: SurfaceHolder,
        cameraId: Int,
        config: CameraConfig,
    ) {
        camera = openedCamera
        val parameters = openedCamera.parameters
        val previewSize = choosePreviewSize(
            sizes = parameters.supportedPreviewSizes,
            target = config.captureSize ?: DEFAULT_CAPTURE_SIZE,
        )
        parameters.setPreviewSize(previewSize.width, previewSize.height)
        parameters.previewFormat = ImageFormat.NV21
        parameters.pictureFormat = ImageFormat.JPEG
        parameters.supportedFocusModes
            ?.firstOrNull { it == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE }
            ?.let { parameters.focusMode = it }
        openedCamera.parameters = parameters

        openedCamera.setPreviewDisplay(holder)
        val bufferSize = previewSize.width * previewSize.height * 3 / 2
        repeat(2) {
            openedCamera.addCallbackBuffer(ByteArray(bufferSize))
        }
        val frameConfig = config.frameDeliveryConfig
        openedCamera.setPreviewCallbackWithBuffer { data, source ->
            handlePreviewFrame(data, source, frameConfig)
        }
        openedCamera.startPreview()
        selectedCameraId = cameraId
        activeCameraId = cameraId
        previewActive = true
    }

    private suspend fun stopInternal(emitEvent: Boolean) {
        val cameraToRelease = camera
        camera = null
        previewActive = false
        activeCameraId = null
        latestFrame = null
        captureWaiter?.cancel()
        captureWaiter = null

        if (cameraToRelease != null) {
            releaseCamera(cameraToRelease)
            delay(CAMERA_RELEASE_SETTLE_MILLIS)
        }
        if (emitEvent) {
            eventFlow.emit(CameraEvent.PreviewStopped(backend))
        }
    }

    private suspend fun openCameraWithRetry(cameraId: Int): Camera {
        var lastFailure: Throwable? = null
        repeat(OPEN_RETRY_COUNT + 1) { attempt ->
            try {
                return withContext(Dispatchers.IO) {
                    Camera.open(cameraId)
                }
            } catch (throwable: Throwable) {
                lastFailure = throwable
                if (attempt < OPEN_RETRY_COUNT) {
                    camera?.let { releaseCamera(it) }
                    camera = null
                    delay(CAMERA_RELEASE_SETTLE_MILLIS)
                }
            }
        }
        throw CameraException.DeviceUnavailableException(
            "Camera1 open failed for camera id $cameraId.",
            lastFailure,
        )
    }

    private fun releaseCamera(cameraToRelease: Camera) {
        runCatching {
            cameraToRelease.setPreviewCallbackWithBuffer(null)
        }.onFailure {
            logger.error("Camera1CameraDriver", "Failed to clear Camera1 preview callback.", it)
        }
        runCatching {
            cameraToRelease.stopPreview()
        }.onFailure {
            logger.error("Camera1CameraDriver", "Failed to stop Camera1 preview.", it)
        }
        runCatching {
            cameraToRelease.release()
        }.onFailure {
            logger.error("Camera1CameraDriver", "Failed to release Camera1.", it)
        }
    }

    private fun handlePreviewFrame(
        data: ByteArray?,
        source: Camera?,
        frameConfig: FrameDeliveryConfig,
    ) {
        try {
            val previewSize = source?.parameters?.previewSize
            if (data == null || previewSize == null) {
                return
            }
            val config = currentConfig ?: CameraConfig()
            val frame = CameraFrame(
                nv21 = data.copyOf(),
                width = previewSize.width,
                height = previewSize.height,
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
                            "Camera1 frame pipeline failed.",
                            throwable,
                        )
                    )
                )
            }
        } finally {
            if (data != null) {
                runCatching {
                    source?.addCallbackBuffer(data)
                }.onFailure {
                    logger.error("Camera1CameraDriver", "Failed to return Camera1 preview buffer.", it)
                }
            }
        }
    }

    private suspend fun awaitSnapshotFrame(): CameraFrame {
        val waiter = CompletableDeferred<CameraFrame>()
        captureWaiter = waiter
        return try {
            withTimeout(SNAPSHOT_FRAME_TIMEOUT_MILLIS) {
                waiter.await()
            }
        } catch (exception: TimeoutCancellationException) {
            throw CameraException.CaptureFailureException("Timed out waiting for a Camera1 snapshot frame.", exception)
        } finally {
            if (captureWaiter === waiter) {
                captureWaiter = null
            }
        }
    }

    private suspend fun awaitSurfaceHolder(view: SurfaceView): SurfaceHolder {
        view.holder.surface?.takeIf { it.isValid }?.let { return view.holder }
        return withTimeout(SURFACE_READY_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (continuation.isActive) {
                            holder.removeCallback(this)
                            continuation.resume(holder)
                        }
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                }
                view.holder.addCallback(callback)
                view.post {
                    if (continuation.isActive && view.holder.surface?.isValid == true) {
                        view.holder.removeCallback(callback)
                        continuation.resume(view.holder)
                    }
                }
                continuation.invokeOnCancellation {
                    view.holder.removeCallback(callback)
                }
            }
        }
    }

    private fun selectCameraId(facing: LensFacing): Int {
        val desired = when (facing) {
            LensFacing.FRONT -> Camera.CameraInfo.CAMERA_FACING_FRONT
            LensFacing.BACK -> Camera.CameraInfo.CAMERA_FACING_BACK
            LensFacing.EXTERNAL -> Camera.CameraInfo.CAMERA_FACING_BACK
        }
        val fallback = if (desired == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            Camera.CameraInfo.CAMERA_FACING_BACK
        } else {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        }
        return findCameraId(desired)
            ?: findCameraId(fallback)
            ?: firstAvailableCameraId()
            ?: throw CameraException.DeviceUnavailableException("No Camera1 camera is available.")
    }

    private fun findCameraId(facing: Int): Int? {
        return (0 until cameraCount()).firstOrNull { cameraId ->
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)
            info.facing == facing
        }
    }

    private fun firstAvailableCameraId(): Int? {
        return if (cameraCount() > 0) 0 else null
    }

    private fun buildAvailableCameras(): List<AvailableCamera> {
        val activeId = resolveCurrentCameraIdFromManager()
        return (0 until cameraCount()).mapIndexed { index, cameraId ->
            val lensFacing = lensFacingOf(cameraId)
            AvailableCamera(
                index = index,
                id = cameraId.toString(),
                displayName = buildDisplayName(cameraId, lensFacing),
                backend = backend,
                lensFacing = lensFacing,
                isActive = cameraId.toString() == activeId,
            )
        }
    }

    private fun resolveCurrentCameraId(cameras: List<AvailableCamera>): String? {
        return activeCameraId?.toString()
            ?: selectedCameraId?.toString()
            ?: cameras.firstOrNull { it.lensFacing == currentLensFacing }?.id
            ?: cameras.firstOrNull()?.id
    }

    private fun resolveCurrentCameraIdFromManager(): String? {
        return activeCameraId?.toString()
            ?: selectedCameraId?.toString()
            ?: runCatching { selectCameraId(currentLensFacing).toString() }.getOrNull()
    }

    private fun lensFacingOf(cameraId: Int): LensFacing? {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        return when (info.facing) {
            Camera.CameraInfo.CAMERA_FACING_FRONT -> LensFacing.FRONT
            Camera.CameraInfo.CAMERA_FACING_BACK -> LensFacing.BACK
            else -> null
        }
    }

    private fun choosePreviewSize(
        sizes: List<Camera.Size>?,
        target: CameraSize,
    ): Camera.Size {
        val candidates = sizes.orEmpty()
        if (candidates.isEmpty()) {
            throw CameraException.PreviewBindingException("Camera1 preview sizes are unavailable.")
        }
        val targetPixels = target.width * target.height
        val targetAspectRatio = target.width.toFloat() / target.height.toFloat()
        return candidates.sortedWith(
            compareBy<Camera.Size>(
                { kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - targetAspectRatio) },
                { kotlin.math.abs(it.width * it.height - targetPixels) },
            )
        ).first()
    }

    private fun ensurePermission() {
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw CameraException.PermissionDeniedException()
        }
    }

    private fun ensureOpen() {
        if (closed) {
            throw CameraException.ClosedException()
        }
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

    private fun createOutputFile(prefix: String): File {
        val parent = File(appContext.cacheDir, "camera-sdk").apply { mkdirs() }
        return File(parent, "${prefix}_${System.currentTimeMillis()}.jpg")
    }
}

private fun cameraCount(): Int {
    return runCatching { Camera.getNumberOfCameras() }.getOrDefault(0)
}

private fun buildDisplayName(
    index: Int,
    lensFacing: LensFacing?,
): String {
    val label = when (lensFacing) {
        LensFacing.FRONT -> "Front"
        LensFacing.BACK -> "Back"
        LensFacing.EXTERNAL -> "External"
        null -> "Unknown"
    }
    return "Camera1 Camera ${index + 1} ($label)"
}
