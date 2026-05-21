package com.holderzone.hardware.camera.driver.camera2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import com.holderzone.hardware.camera.AvailableCamera
import com.holderzone.hardware.camera.CameraBackend
import com.holderzone.hardware.camera.CameraCapability
import com.holderzone.hardware.camera.CameraConfig
import com.holderzone.hardware.camera.CameraEvent
import com.holderzone.hardware.camera.CameraException
import com.holderzone.hardware.camera.CameraFrame
import com.holderzone.hardware.camera.CaptureKind
import com.holderzone.hardware.camera.CaptureRequest as SdkCaptureRequest
import com.holderzone.hardware.camera.CaptureResult
import com.holderzone.hardware.camera.FrameDeliveryConfig
import com.holderzone.hardware.camera.LensFacing
import com.holderzone.hardware.camera.PreviewHost
import com.holderzone.hardware.camera.internal.saveAsJpeg
import com.holderzone.hardware.camera.internal.log.CameraLogger
import com.holderzone.hardware.camera.internal.spi.CameraDriver
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 fallback driver.
 *
 * It exposes snapshot capture from the camera frame pipeline.
 */
class Camera2CameraDriver(
    private val appContext: Context,
    private val logger: CameraLogger,
) : CameraDriver {

    private companion object {
        val DEFAULT_CAPTURE_SIZE = Size(1280, 720)
        const val SNAPSHOT_FRAME_TIMEOUT_MILLIS = 2_000L
    }

    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val eventFlow = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    private val frameFlow = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val backend: CameraBackend = CameraBackend.CAMERA_2
    override val capabilities: CameraCapability = CameraCapability(
        switchLens = cameraManager.cameraIdList.size > 1,
        switchCamera = cameraManager.cameraIdList.size > 1,
        snapshotCapture = true,
        frameStreaming = true,
        uvcSelection = false,
    )
    override val frames: Flow<CameraFrame> = frameFlow.asSharedFlow()
    override val events: Flow<CameraEvent> = eventFlow.asSharedFlow()

    private var previewHost: PreviewHost? = null
    private var textureView: TextureView? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var currentConfig: CameraConfig? = null
    private var currentLensFacing: LensFacing = LensFacing.BACK
    private var selectedCameraId: String? = null
    private var activeCameraId: String? = null
    private var activePreviewSize: Size? = null
    private var latestFrame: CameraFrame? = null
    private var captureWaiter: CompletableDeferred<CameraFrame>? = null
    private var lastFrameAtMs: Long = 0L
    private var closed = false

    override suspend fun bind(host: PreviewHost, config: CameraConfig) {
        ensureOpen()
        currentConfig = config
        currentLensFacing = config.lensFacing
        selectedCameraId = null
        previewHost = host
        textureView = TextureView(host.previewContext)
        host.attachPreview(textureView!!)
    }

    override suspend fun start() {
        ensureOpen()
        ensurePermission()
        ensureBackgroundThread()

        val texture = textureView ?: throw CameraException.PreviewBindingException(
            "Camera2 preview host is not bound."
        )
        val surfaceTexture = awaitSurfaceTexture(texture)
        val cameraId = selectedCameraId ?: selectCameraId(currentLensFacing)
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val previewSize = choosePreviewSize(characteristics)

        selectedCameraId = cameraId
        activeCameraId = cameraId
        activePreviewSize = previewSize
        val device = openCamera(cameraId)
        cameraDevice = device

        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(surfaceTexture)
        val reader = ImageReader.newInstance(
            previewSize.width,
            previewSize.height,
            ImageFormat.YUV_420_888,
            2,
        )
        imageReader = reader
        val frameConfig = currentConfig?.frameDeliveryConfig ?: FrameDeliveryConfig.DISABLED
        reader.setOnImageAvailableListener(
            { source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                handleImage(image, frameConfig)
            },
            backgroundHandler,
        )

        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        captureSession = createCaptureSession(device, listOf(previewSurface, reader.surface)).also { session ->
            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
        }
        eventFlow.emit(CameraEvent.PreviewStarted(backend))
    }

    override suspend fun stop() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        activeCameraId = null
        activePreviewSize = null
        eventFlow.emit(CameraEvent.PreviewStopped(backend))
    }

    override suspend fun switchLens(facing: LensFacing) {
        if (!capabilities.switchLens) {
            throw CameraException.ConfigurationException("Camera2 backend does not support lens switching.")
        }
        currentLensFacing = facing
        selectedCameraId = null
        if (cameraDevice != null) {
            stop()
            start()
        }
    }

    override suspend fun switchToNextCamera() {
        ensureOpen()
        val cameras = buildAvailableCameras()
        if (cameras.size < 2) {
            throw CameraException.DeviceUnavailableException("No alternate Camera2 camera is available.")
        }
        val currentId = resolveCurrentCameraId(cameras)
        val currentIndex = cameras.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % cameras.size
        } else {
            0
        }
        val nextCamera = cameras[nextIndex]
        selectedCameraId = nextCamera.id
        nextCamera.lensFacing?.let { currentLensFacing = it }
        if (cameraDevice != null) {
            stop()
            start()
        }
    }

    override suspend fun queryAvailableCameras(): List<AvailableCamera> {
        ensureOpen()
        return buildAvailableCameras()
    }

    override suspend fun capture(request: SdkCaptureRequest): CaptureResult {
        ensureOpen()
        val file = resolveOutputFile(
            requestedFile = request.outputFile,
            prefix = "camera2_snapshot",
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
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
        }.onFailure {
            logger.error("Camera2CameraDriver", "Failed to close camera resources.", it)
        }

        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        previewHost?.let { host ->
            textureView?.let(host::detachPreview)
        }
        previewHost = null
        textureView = null
        latestFrame = null
        captureWaiter?.cancel()
        captureWaiter = null
        scope.cancel()
    }

    private fun handleImage(
        image: Image,
        frameConfig: FrameDeliveryConfig,
    ) {
        try {
            val config = currentConfig ?: CameraConfig()
            val frame = CameraFrame(
                nv21 = image.toNv21(),
                width = image.width,
                height = image.height,
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
                            "Camera2 frame pipeline failed.",
                            throwable,
                        )
                    )
                )
            }
        } finally {
            image.close()
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
            throw CameraException.CaptureFailureException("Timed out waiting for a Camera2 snapshot frame.", exception)
        } finally {
            if (captureWaiter === waiter) {
                captureWaiter = null
            }
        }
    }

    private fun ensureBackgroundThread() {
        if (backgroundThread != null) {
            return
        }
        backgroundThread = HandlerThread("Camera2Driver").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private suspend fun awaitSurfaceTexture(textureView: TextureView): SurfaceTexture {
        textureView.surfaceTexture?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    textureView.surfaceTextureListener = null
                    continuation.resume(surface)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    private suspend fun openCamera(cameraId: String): CameraDevice {
        return suspendCancellableCoroutine { continuation ->
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        continuation.resume(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        continuation.resumeWithException(
                            CameraException.DeviceUnavailableException("Camera2 camera disconnected.")
                        )
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        continuation.resumeWithException(
                            CameraException.DeviceUnavailableException("Camera2 open failed with code $error.")
                        )
                    }
                },
                backgroundHandler,
            )
        }
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession {
        return suspendCancellableCoroutine { continuation ->
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        continuation.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        continuation.resumeWithException(
                            CameraException.PreviewBindingException("Camera2 capture session configuration failed.")
                        )
                    }
                },
                backgroundHandler,
            )
        }
    }

    private fun selectCameraId(facing: LensFacing): String {
        val desired = when (facing) {
            LensFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            LensFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
            LensFacing.EXTERNAL -> CameraCharacteristics.LENS_FACING_EXTERNAL
        }
        val fallback = if (desired == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        return findCameraId(desired)
            ?: findCameraId(fallback)
            ?: cameraManager.cameraIdList.firstOrNull()
            ?: throw CameraException.DeviceUnavailableException("No Camera2 camera is available.")
    }

    private fun resolveCurrentCameraId(cameras: List<AvailableCamera>): String? {
        return activeCameraId
            ?: selectedCameraId
            ?: cameras.firstOrNull { it.lensFacing == currentLensFacing }?.id
            ?: cameras.firstOrNull()?.id
    }

    private fun buildAvailableCameras(): List<AvailableCamera> {
        val activeId = resolveCurrentCameraIdFromManager()
        return cameraManager.cameraIdList.mapIndexed { index, cameraId ->
            val lensFacing = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.LENS_FACING)
                .toLensFacing()
            AvailableCamera(
                index = index,
                id = cameraId,
                displayName = buildDisplayName(
                    prefix = "Camera2",
                    index = index,
                    lensFacing = lensFacing,
                ),
                backend = backend,
                lensFacing = lensFacing,
                isActive = cameraId == activeId,
            )
        }
    }

    private fun resolveCurrentCameraIdFromManager(): String? {
        return activeCameraId
            ?: selectedCameraId
            ?: runCatching { selectCameraId(currentLensFacing) }.getOrNull()
    }

    private fun findCameraId(facing: Int): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    private fun choosePreviewSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw CameraException.PreviewBindingException("Camera2 stream configuration is unavailable.")
        val targetSize = currentConfig?.captureSize?.let { Size(it.width, it.height) }
            ?: DEFAULT_CAPTURE_SIZE
        val textureSizes = map.getOutputSizes(SurfaceTexture::class.java).toSet()
        val imageSizes = map.getOutputSizes(ImageFormat.YUV_420_888).toSet()
        val sharedSizes = textureSizes.intersect(imageSizes).toTypedArray()
        return chooseSize(
            candidates = sharedSizes.takeIf { it.isNotEmpty() }
                ?: map.getOutputSizes(SurfaceTexture::class.java),
            targetSize = targetSize,
        )
    }

    private fun chooseSize(
        candidates: Array<Size>,
        targetSize: Size,
    ): Size {
        val targetPixels = targetSize.width * targetSize.height
        val targetAspectRatio = targetSize.width.toFloat() / targetSize.height.toFloat()
        return candidates
            .sortedWith(
                compareBy<Size>(
                    { kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - targetAspectRatio) },
                    { kotlin.math.abs(it.width * it.height - targetPixels) },
                )
            )
            .firstOrNull()
            ?: candidates.first()
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

private fun Int?.toLensFacing(): LensFacing? {
    return when (this) {
        CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
        CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
        else -> null
    }
}

private fun buildDisplayName(
    prefix: String,
    index: Int,
    lensFacing: LensFacing?,
): String {
    val label = when (lensFacing) {
        LensFacing.FRONT -> "Front"
        LensFacing.BACK -> "Back"
        LensFacing.EXTERNAL -> "External"
        null -> "Unknown"
    }
    return "$prefix Camera ${index + 1} ($label)"
}

private fun Image.toNv21(): ByteArray {
    val width = width
    val height = height
    val output = ByteArray(width * height * 3 / 2)
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBytes = ByteArray(yPlane.buffer.remaining())
    yPlane.buffer.get(yBytes)
    var offset = 0
    if (yPlane.pixelStride == 1 && yPlane.rowStride == width) {
        System.arraycopy(yBytes, 0, output, 0, yBytes.size)
        offset = yBytes.size
    } else {
        for (row in 0 until height) {
            var index = row * yPlane.rowStride
            repeat(width) {
                output[offset++] = yBytes[index]
                index += yPlane.pixelStride
            }
        }
    }

    val uBytes = ByteArray(uPlane.buffer.remaining())
    val vBytes = ByteArray(vPlane.buffer.remaining())
    uPlane.buffer.get(uBytes)
    vPlane.buffer.get(vBytes)
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
        var uIndex = row * uPlane.rowStride
        var vIndex = row * vPlane.rowStride
        repeat(chromaWidth) {
            output[offset++] = vBytes[vIndex]
            output[offset++] = uBytes[uIndex]
            uIndex += uPlane.pixelStride
            vIndex += vPlane.pixelStride
        }
    }
    return output
}
