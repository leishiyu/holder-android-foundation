package com.holderzone.hardware.camera.driver.camerax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import android.view.ViewTreeObserver
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.util.concurrent.ListenableFuture
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
import com.holderzone.hardware.camera.internal.saveAsJpeg
import com.holderzone.hardware.camera.internal.log.CameraLogger
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
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX driver used as the default built-in camera backend.
 */
class CameraXCameraDriver(
    private val appContext: Context,
    private val logger: CameraLogger,
) : CameraDriver {

    private companion object {
        val DEFAULT_CAPTURE_SIZE = Size(1280, 720)
        const val SNAPSHOT_FRAME_TIMEOUT_MILLIS = 2_000L
    }

    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    override val backend: CameraBackend = CameraBackend.CAMERA_X
    override val capabilities: CameraCapability = CameraCapability(
        switchLens = true,
        switchCamera = cameraManager.cameraIdList.size > 1,
        snapshotCapture = true,
        frameStreaming = true,
        uvcSelection = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eventFlow = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    private val frameFlow = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)
    override val events: Flow<CameraEvent> = eventFlow.asSharedFlow()
    override val frames: Flow<CameraFrame> = frameFlow.asSharedFlow()

    private val lifecycleOwner = DriverLifecycleOwner()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var previewHost: PreviewHost? = null
    private var previewContainerView: View? = null
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var currentConfig: CameraConfig? = null
    private var currentLensFacing: LensFacing = LensFacing.BACK
    private var selectedCameraId: String? = null
    private var activeCameraId: String? = null
    private var latestFrame: CameraFrame? = null
    private var captureWaiter: CompletableDeferred<CameraFrame>? = null
    private var lastFrameAtMs: Long = 0L
    private var closed = false

    override suspend fun bind(host: PreviewHost, config: CameraConfig) {
        ensureOpen()
        currentConfig = config
        currentLensFacing = config.lensFacing
        selectedCameraId = null
        activeCameraId = null
        previewHost = host
        previewContainerView = host as? View
        val view = PreviewView(host.previewContext).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        previewView = view
        host.attachPreview(view)
    }

    override suspend fun start() {
        ensureOpen()
        ensureCameraPermission()
        val hostView = previewView ?: throw CameraException.PreviewBindingException(
            "CameraX preview host is not bound."
        )
        val config = currentConfig ?: throw CameraException.ConfigurationException(
            "CameraX config is missing."
        )

        try {
            startWithTimeouts(
                hostView = hostView,
                config = config,
                readyTimeoutMillis = 2_500L,
            )
        } catch (exception: TimeoutCancellationException) {
            if (shouldRetryHostReadiness(hostView)) {
                logger.warn(
                    "CameraXDriver",
                    "CameraX start timed out before the preview host was ready. " +
                        "Retrying once after layout settles."
                )
                delay(200L)
                try {
                    startWithTimeouts(
                        hostView = hostView,
                        config = config,
                        readyTimeoutMillis = 5_000L,
                    )
                    return
                } catch (retryException: TimeoutException) {
                    throw CameraException.PreviewBindingException(
                        "CameraX timed out while creating the preview session. " +
                            "The preview surface may not be ready yet, or the device camera service " +
                            "is responding too slowly.",
                        retryException,
                    )
                } catch (retryException: TimeoutCancellationException) {
                    throw CameraException.PreviewBindingException(
                        "CameraX start timed out while waiting for the preview host or camera provider.",
                        retryException,
                    )
                }
            }
            throw CameraException.PreviewBindingException(
                "CameraX start timed out while waiting for the preview host or camera provider.",
                exception,
            )
        } catch (exception: TimeoutException) {
            throw CameraException.PreviewBindingException(
                "CameraX timed out while creating the preview session. " +
                    "The preview surface may not be ready yet, or the device camera service " +
                    "is responding too slowly.",
                exception,
            )
        }
    }

    override suspend fun stop() {
        cameraProvider?.unbindAll()
        lifecycleOwner.moveToCreated()
        preview = null
        imageAnalysis = null
        camera = null
        activeCameraId = null
        latestFrame = null
        captureWaiter?.cancel()
        captureWaiter = null
        eventFlow.emit(CameraEvent.PreviewStopped(backend))
    }

    override suspend fun switchLens(facing: LensFacing) {
        currentLensFacing = facing
        selectedCameraId = null
        if (camera != null) {
            stop()
            start()
        }
    }

    override suspend fun switchToNextCamera() {
        ensureOpen()
        val provider = getOrCreateCameraProvider()
        val cameras = buildAvailableCameras(provider)
        if (cameras.size < 2) {
            throw CameraException.DeviceUnavailableException("No alternate CameraX camera is available.")
        }
        val currentId = resolveCurrentCameraId(provider)
        val currentIndex = cameras.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % cameras.size
        } else {
            0
        }
        val nextCamera = cameras[nextIndex]
        selectedCameraId = nextCamera.id
        nextCamera.lensFacing?.let { currentLensFacing = it }
        if (camera != null) {
            stop()
            start()
        }
    }

    override suspend fun queryAvailableCameras(): List<AvailableCamera> {
        ensureOpen()
        return buildAvailableCameras(getOrCreateCameraProvider())
    }

    override suspend fun capture(request: CaptureRequest): CaptureResult {
        ensureOpen()
        val file = resolveOutputFile(
            requestedFile = request.outputFile,
            prefix = "camerax_snapshot",
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

        val cleanup = {
            previewHost?.let { host ->
                previewView?.let(host::detachPreview)
            }
            previewHost = null
            previewContainerView = null
            previewView = null
            cameraProvider?.unbindAll()
            preview = null
            imageAnalysis = null
            camera = null
            latestFrame = null
            captureWaiter?.cancel()
            captureWaiter = null
            lifecycleOwner.destroy()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    cleanup()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
        }

        cameraExecutor.shutdownNow()
        scope.cancel()
    }

    private fun handleAnalysisFrame(
        imageProxy: ImageProxy,
        frameConfig: FrameDeliveryConfig,
    ) {
        try {
            val now = System.currentTimeMillis()
            val config = currentConfig ?: CameraConfig()
            val frame = CameraFrame(
                nv21 = imageProxy.toNv21(),
                width = imageProxy.width,
                height = imageProxy.height,
                rotationDegrees = (imageProxy.imageInfo.rotationDegrees + config.frameRotationDegrees) % 360,
            )
            latestFrame = frame
            captureWaiter?.takeIf { !it.isCompleted }?.complete(frame)
            captureWaiter = null
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
                            "CameraX frame analysis failed.",
                            throwable,
                        )
                    )
                )
            }
        } finally {
            imageProxy.close()
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
            throw CameraException.CaptureFailureException("Timed out waiting for a CameraX snapshot frame.", exception)
        } finally {
            if (captureWaiter === waiter) {
                captureWaiter = null
            }
        }
    }

    private fun selectCameraSelector(
        provider: ProcessCameraProvider,
    ): CameraSelector {
        val targetCameraId = selectedCameraId
        if (targetCameraId != null) {
            return CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info ->
                        cameraIdOf(info) == targetCameraId
                    }
                }
                .build()
        }
        val facing = currentLensFacing
        val primary = when (facing) {
            LensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            LensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            LensFacing.EXTERNAL -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val secondary = if (primary == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        return when {
            provider.hasCamera(primary) -> primary
            provider.hasCamera(secondary) -> secondary
            else -> throw CameraException.DeviceUnavailableException("No CameraX camera is available.")
        }
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CAMERA
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

    private suspend fun startWithTimeouts(
        hostView: PreviewView,
        config: CameraConfig,
        readyTimeoutMillis: Long,
    ) {
        previewContainerView?.awaitReady(
            timeoutMillis = readyTimeoutMillis,
            label = "preview container",
        )
        previewHost?.attachPreview(hostView)
        hostView.awaitReady(
            timeoutMillis = readyTimeoutMillis,
            label = "preview view",
        )
        lifecycleOwner.moveToStarted()

        val provider = getOrCreateCameraProvider()
        val selector = selectCameraSelector(provider)
        val analysisSize = config.captureSize?.let { Size(it.width, it.height) }
            ?: DEFAULT_CAPTURE_SIZE
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    analysisSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                )
            )
            .build()

        preview = Preview.Builder().build().also {
            it.surfaceProvider = hostView.surfaceProvider
        }
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    handleAnalysisFrame(imageProxy, config.frameDeliveryConfig)
                }
            }

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            imageAnalysis,
        )
        activeCameraId = camera?.cameraInfo?.let(::cameraIdOf)
        if (activeCameraId != null) {
            selectedCameraId = activeCameraId
        }
        eventFlow.emit(CameraEvent.PreviewStarted(backend))
    }

    private fun shouldRetryHostReadiness(hostView: PreviewView): Boolean {
        val containerReady = previewContainerView?.isReadyForCameraBinding() != false
        val previewReady = hostView.isReadyForCameraBinding()
        return !containerReady || !previewReady
    }

    /**
     * CameraX is sensitive to preview hosts that are created but not yet attached or measured.
     * Waiting for a real surface container greatly reduces startup timeouts on slower devices.
     */
    private suspend fun View.awaitReady(
        timeoutMillis: Long,
        label: String,
    ) {
        if (isReadyForCameraBinding()) {
            return
        }

        try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    lateinit var attachListener: View.OnAttachStateChangeListener
                    lateinit var layoutListener: View.OnLayoutChangeListener
                    lateinit var preDrawListener: ViewTreeObserver.OnPreDrawListener

                    fun cleanup() {
                        removeOnAttachStateChangeListener(attachListener)
                        removeOnLayoutChangeListener(layoutListener)
                        if (viewTreeObserver.isAlive) {
                            viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                        }
                    }

                    fun resumeIfReady() {
                        if (!continuation.isActive) {
                            return
                        }
                        if (isReadyForCameraBinding()) {
                            cleanup()
                            continuation.resume(Unit)
                        }
                    }

                    attachListener = object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            resumeIfReady()
                        }

                        override fun onViewDetachedFromWindow(v: View) = Unit
                    }
                    layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        resumeIfReady()
                    }
                    preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            resumeIfReady()
                            return true
                        }
                    }

                    addOnAttachStateChangeListener(attachListener)
                    addOnLayoutChangeListener(layoutListener)
                    if (viewTreeObserver.isAlive) {
                        viewTreeObserver.addOnPreDrawListener(preDrawListener)
                    }

                    post { resumeIfReady() }
                    continuation.invokeOnCancellation { cleanup() }
                }
            }
        } catch (exception: TimeoutCancellationException) {
            logger.warn(
                "CameraXDriver",
                "Timed out waiting for $label to become ready. attached=$isAttachedToWindow width=$width height=$height"
            )
            throw exception
        }
    }

    private fun View.isReadyForCameraBinding(): Boolean {
        return isAttachedToWindow && width > 0 && height > 0
    }

    private suspend fun getOrCreateCameraProvider(): ProcessCameraProvider {
        return cameraProvider ?: withTimeout(8_000L) {
            ProcessCameraProvider.getInstance(appContext).await()
        }.also {
            cameraProvider = it
        }
    }

    private fun buildAvailableCameras(provider: ProcessCameraProvider): List<AvailableCamera> {
        val infosById = provider.availableCameraInfos.associateBy(::cameraIdOf)
        val orderedIds = buildList {
            cameraManager.cameraIdList.forEach { cameraId ->
                if (infosById.containsKey(cameraId)) {
                    add(cameraId)
                }
            }
            infosById.keys.forEach { cameraId ->
                if (!contains(cameraId)) {
                    add(cameraId)
                }
            }
        }
        val activeId = resolveCurrentCameraId(provider)
        return orderedIds.mapIndexedNotNull { index, cameraId ->
            val info = infosById[cameraId] ?: return@mapIndexedNotNull null
            val lensFacing = lensFacingOf(info)
            AvailableCamera(
                index = index,
                id = cameraId,
                displayName = buildDisplayName(index, lensFacing),
                backend = backend,
                lensFacing = lensFacing,
                isActive = cameraId == activeId,
            )
        }
    }

    private fun resolveCurrentCameraId(provider: ProcessCameraProvider): String? {
        return activeCameraId
            ?: selectedCameraId
            ?: currentSelectedCameraId(provider)
            ?: provider.availableCameraInfos.firstOrNull()?.let(::cameraIdOf)
    }

    private fun currentSelectedCameraId(provider: ProcessCameraProvider): String? {
        val targetCameraId = selectedCameraId
        if (targetCameraId != null) {
            val infos = provider.availableCameraInfos
            return infos.firstOrNull { info -> cameraIdOf(info) == targetCameraId }?.let(::cameraIdOf)
        }
        val selector = selectCameraSelector(provider)
        return runCatching {
            selector.filter(provider.availableCameraInfos).firstOrNull()?.let(::cameraIdOf)
        }.getOrNull()
    }

    private fun cameraIdOf(cameraInfo: CameraInfo): String {
        return Camera2CameraInfo.from(cameraInfo).cameraId
    }

    private fun lensFacingOf(cameraInfo: CameraInfo): LensFacing? {
        return Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            .toLensFacing()
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

    private suspend fun <T> ListenableFuture<T>.await(): T {
        return suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    try {
                        continuation.resume(get())
                    } catch (throwable: Throwable) {
                        continuation.resumeWithException(throwable)
                    }
                },
                ContextCompat.getMainExecutor(appContext),
            )
        }
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
    index: Int,
    lensFacing: LensFacing?,
): String {
    val label = when (lensFacing) {
        LensFacing.FRONT -> "Front"
        LensFacing.BACK -> "Back"
        LensFacing.EXTERNAL -> "External"
        null -> "Unknown"
    }
    return "CameraX Camera ${index + 1} ($label)"
}

private class DriverLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    fun moveToStarted() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun moveToCreated() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

private fun ImageProxy.toNv21(): ByteArray {
    val width = width
    val height = height
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val output = ByteArray(width * height * 3 / 2)
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val yBytes = ByteArray(yBuffer.remaining())
    yBuffer.get(yBytes)
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

    val uBytes = ByteArray(uBuffer.remaining())
    val vBytes = ByteArray(vBuffer.remaining())
    uBuffer.get(uBytes)
    vBuffer.get(vBytes)
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
