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
import com.holderzone.hardware.camera.internal.rotateNv21
import com.holderzone.hardware.camera.internal.saveAsJpeg
import com.holderzone.hardware.camera.internal.spi.CameraDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Camera1 compatibility driver.
 *
 * Camera1 is deprecated by Android, but remains useful as a fallback for older or vendor-custom
 * devices whose Camera2/CameraX metadata does not match the physical camera layout.
 */
class Camera1CameraDriver internal constructor(
    private val appContext: Context,
    private val logger: CameraLogger,
    private val cameraOpener: suspend (Int) -> Camera,
) : CameraDriver {

    constructor(appContext: Context, logger: CameraLogger) : this(
        appContext,
        logger,
        // Do not lose a newly opened camera to prompt cancellation when returning from IO.
        { cameraId ->
            // Keep the return-to-Main handoff non-cancellable too. Combining NonCancellable
            // and IO in one withContext can still discard its result on the dispatcher hop.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { Camera.open(cameraId) }
            }
        },
    )

    private companion object {
        val DEFAULT_CAPTURE_SIZE = CameraSize.HD_720P
        const val SNAPSHOT_FRAME_TIMEOUT_MILLIS = 2_000L
        const val FIRST_FRAME_TIMEOUT_MILLIS = 5_000L
        const val CAMERA_RELEASE_SETTLE_MILLIS = 200L
        const val SURFACE_READY_TIMEOUT_MILLIS = 5_000L
        const val START_RETRY_COUNT = 1
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
    @Volatile
    private var currentConfig: CameraConfig? = null
    private var currentLensFacing: LensFacing = LensFacing.BACK
    private var selectedCameraId: Int? = null
    private var activeCameraId: Int? = null
    private var latestFrame: CameraFrame? = null
    private var captureWaiter: CompletableDeferred<CameraFrame>? = null
    private var firstFrameWaiter: CompletableDeferred<CameraFrame>? = null
    private var sessionFailure: CameraException? = null
    private var lastFrameAtMs: Long = 0L
    private var previewActive = false
    // Unlike previewActive, this survives a failed switch so another switch can reopen the camera.
    private var previewRequested = false
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
        previewRequested = true
        if (previewActive && camera != null) {
            return
        }
        stopInternal(emitEvent = false)
        startInternal()
    }

    override suspend fun stop() = operationMutex.withLock {
        ensureOpen()
        previewRequested = false
        stopInternal(emitEvent = true)
    }

    override suspend fun setFrameRotationDegrees(degrees: Int) = operationMutex.withLock {
        ensureOpen()
        val config = currentConfig ?: throw CameraException.ConfigurationException(
            "Camera1 config is missing."
        )
        currentConfig = config.copy(frameRotationDegrees = degrees)
        camera?.takeIf { sessionFailure == null }?.setDisplayOrientation(degrees)
        Unit
    }

    override suspend fun switchLens(facing: LensFacing) = operationMutex.withLock {
        ensureOpen()
        if (!capabilities.switchLens) {
            throw CameraException.ConfigurationException("Camera1 backend does not support lens switching.")
        }
        currentLensFacing = facing
        selectedCameraId = null
        if (previewRequested) {
            stopInternal(emitEvent = true)
            startInternal()
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
        if (previewRequested) {
            stopInternal(emitEvent = true)
            startInternal()
        }
    }

    override suspend fun queryAvailableCameras(): List<AvailableCamera> {
        ensureOpen()
        return buildAvailableCameras()
    }

    override suspend fun capture(request: CaptureRequest): CaptureResult {
        ensureOpen()
        if (!previewActive || camera == null || sessionFailure != null) {
            throw CameraException.CaptureFailureException(
                "Camera1 preview is not ready. Start or switch the camera before capturing.",
                sessionFailure,
            )
        }
        val file = resolveOutputFile(
            requestedFile = request.outputFile,
            prefix = "camera1_snapshot",
        )
        val config = currentConfig ?: CameraConfig()
        val frame = latestFrame ?: awaitSnapshotFrame()
        frame.saveAsJpeg(file, config.jpegQuality)
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
        previewRequested = false
        clearSession(CameraException.ClosedException())?.let(::releaseCamera)

        previewHost?.let { host ->
            surfaceView?.let(host::detachPreview)
        }
        previewHost = null
        surfaceView = null
        selectedCameraId = null
        scope.cancel()
    }

    private suspend fun startInternal() {
        ensurePermission()
        val view = surfaceView ?: throw CameraException.PreviewBindingException(
            "Camera1 preview host is not bound."
        )
        val cameraId = selectedCameraId ?: selectCameraId(currentLensFacing)
        var lastFailure: Throwable? = null
        repeat(START_RETRY_COUNT + 1) { attempt ->
            var openedCamera: Camera? = null
            var adopted = false
            try {
                ensureOpen()
                val holder = awaitSurfaceHolder(view)
                logger.debug("Camera1CameraDriver", "Opening camera id=$cameraId attempt=${attempt + 1}")
                openedCamera = cameraOpener(cameraId)
                ensureOpen()
                coroutineContext.ensureActive()
                camera = openedCamera
                adopted = true
                sessionFailure = null
                val waiter = CompletableDeferred<CameraFrame>()
                firstFrameWaiter = waiter
                configureAndStartCamera(
                    openedCamera,
                    holder,
                    currentConfig ?: CameraConfig(),
                    useCallbackBuffers = attempt == 0,
                )
                try {
                    withTimeout(FIRST_FRAME_TIMEOUT_MILLIS) { waiter.await() }
                } catch (exception: TimeoutCancellationException) {
                    coroutineContext.ensureActive()
                    throw CameraException.PreviewBindingException(
                        "Camera1 camera id $cameraId produced no preview frame within ${FIRST_FRAME_TIMEOUT_MILLIS}ms.",
                        exception,
                    )
                }
                ensureOpen()
                sessionFailure?.let { throw it }
                selectedCameraId = cameraId
                activeCameraId = cameraId
                previewActive = true
                firstFrameWaiter = null
                logger.debug("Camera1CameraDriver", "First preview frame received for camera id=$cameraId")
                eventFlow.emit(CameraEvent.PreviewStarted(backend))
                return
            } catch (throwable: Throwable) {
                if (camera === openedCamera && openedCamera != null) {
                    clearSession()?.let(::releaseCamera)
                } else if (!adopted) {
                    openedCamera?.let(::releaseCamera)
                }
                if (throwable is CancellationException || throwable is CameraException.ClosedException) {
                    throw throwable
                }
                lastFailure = throwable
                logger.error("Camera1CameraDriver", "Camera id=$cameraId start attempt=${attempt + 1} failed.", throwable)
                if (attempt < START_RETRY_COUNT) {
                    // Retry the entire preview pipeline, not only Camera.open(). Some vendor HALs
                    // open successfully but fail to deliver buffered preview callbacks after a switch.
                    delay(CAMERA_RELEASE_SETTLE_MILLIS * (attempt + 1))
                }
            }
        }
        throw CameraException.PreviewBindingException(
            "Camera1 preview failed for camera id $cameraId after ${START_RETRY_COUNT + 1} attempts.",
            lastFailure,
        )
    }

    private fun configureAndStartCamera(
        openedCamera: Camera,
        holder: SurfaceHolder,
        config: CameraConfig,
        useCallbackBuffers: Boolean,
    ) {
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

        // Vendor HALs can adjust the requested size. Allocate buffers for the effective parameters.
        val actualParameters = openedCamera.parameters
        val actualSize = actualParameters.previewSize
        if (actualParameters.previewFormat != ImageFormat.NV21) {
            throw CameraException.ConfigurationException("Camera1 did not accept NV21 preview format.")
        }
        logger.debug("Camera1CameraDriver", "Preview ${actualSize.width}x${actualSize.height}, buffered=$useCallbackBuffers")

        openedCamera.setDisplayOrientation(config.frameRotationDegrees)
        openedCamera.setPreviewDisplay(holder)
        openedCamera.setErrorCallback { code, source ->
            failSession(source, CameraException.DeviceUnavailableException("Camera1 error $code for camera id ${activeCameraId ?: selectedCameraId}."))
        }
        val frameConfig = config.frameDeliveryConfig
        val callback = Camera.PreviewCallback { data, source ->
            handlePreviewFrame(data, source, frameConfig, actualSize.width, actualSize.height, useCallbackBuffers)
        }
        if (useCallbackBuffers) {
            openedCamera.setPreviewCallbackWithBuffer(callback)
            val bufferSize = actualSize.width * actualSize.height * 3 / 2
            repeat(2) { openedCamera.addCallbackBuffer(ByteArray(bufferSize)) }
        } else {
            openedCamera.setPreviewCallback(callback)
        }
        openedCamera.startPreview()
    }

    private suspend fun stopInternal(emitEvent: Boolean) {
        val cameraToRelease = clearSession()

        if (cameraToRelease != null) {
            releaseCamera(cameraToRelease)
            delay(CAMERA_RELEASE_SETTLE_MILLIS)
        }
        if (emitEvent) {
            eventFlow.emit(CameraEvent.PreviewStopped(backend))
        }
    }

    private fun clearSession(
        failure: CameraException = CameraException.PreviewBindingException("Camera1 preview session stopped."),
    ): Camera? {
        val cameraToRelease = camera
        camera = null
        previewActive = false
        activeCameraId = null
        latestFrame = null
        lastFrameAtMs = 0L
        sessionFailure = null
        firstFrameWaiter?.completeExceptionally(failure)
        firstFrameWaiter = null
        captureWaiter?.completeExceptionally(failure)
        captureWaiter = null
        return cameraToRelease
    }

    private fun releaseCamera(cameraToRelease: Camera) {
        runCatching { cameraToRelease.setErrorCallback(null) }
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
        width: Int,
        height: Int,
        useCallbackBuffers: Boolean,
    ) {
        // A queued callback from the previous camera must never seed the new session.
        if (closed || source == null || source !== camera || sessionFailure != null) return
        try {
            if (data == null || data.size < width * height * 3 / 2) {
                throw CameraException.CaptureFailureException("Camera1 returned an empty or undersized NV21 preview frame.")
            }
            val config = currentConfig ?: CameraConfig()
            val rotated = rotateNv21(
                nv21 = data.copyOf(),
                width = width,
                height = height,
                rotationDegrees = config.frameRotationDegrees,
            )
            val frame = CameraFrame(
                nv21 = rotated.nv21,
                width = rotated.width,
                height = rotated.height,
                rotationDegrees = 0,
            )
            latestFrame = frame
            firstFrameWaiter?.complete(frame)
            captureWaiter?.takeIf { !it.isCompleted }?.complete(frame)
            captureWaiter = null

            val now = System.currentTimeMillis()
            if (frameConfig.enabled && now - lastFrameAtMs >= frameConfig.minIntervalMillis) {
                lastFrameAtMs = now
                scope.launch {
                    if (!closed && source === camera && sessionFailure == null) frameFlow.emit(frame)
                }
            }
        } catch (throwable: Throwable) {
            failSession(source, CameraException.CaptureFailureException("Camera1 frame pipeline failed.", throwable))
        } finally {
            if (useCallbackBuffers && data != null && source === camera && sessionFailure == null && !closed) {
                runCatching {
                    source.addCallbackBuffer(data)
                }.onFailure {
                    logger.error("Camera1CameraDriver", "Failed to return Camera1 preview buffer.", it)
                }
            }
        }
    }

    private fun failSession(source: Camera?, exception: CameraException) {
        if (closed || source == null || source !== camera || sessionFailure != null) return
        val wasPreviewActive = previewActive
        sessionFailure = exception
        previewActive = false
        latestFrame = null
        firstFrameWaiter?.completeExceptionally(exception)
        captureWaiter?.completeExceptionally(exception)
        logger.error("Camera1CameraDriver", exception.message.orEmpty(), exception)
        // Startup failures are handled by startInternal's bounded retry. Only publish an
        // unsolicited error for a session which had already reported a valid preview.
        if (wasPreviewActive) {
            scope.launch {
                operationMutex.withLock {
                    if (camera === source) {
                        stopInternal(emitEvent = true)
                        eventFlow.emit(CameraEvent.Error(exception))
                    }
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
