package com.holderzone.hardware.camera.core

import android.content.Context
import android.os.Looper
import com.holderzone.hardware.camera.AvailableCamera
import com.holderzone.hardware.camera.CameraBackend
import com.holderzone.hardware.camera.CameraBackendPreference
import com.holderzone.hardware.camera.CameraCapability
import com.holderzone.hardware.camera.CameraConfig
import com.holderzone.hardware.camera.CameraController
import com.holderzone.hardware.camera.CameraEvent
import com.holderzone.hardware.camera.CameraException
import com.holderzone.hardware.camera.CameraFrame
import com.holderzone.hardware.camera.CameraState
import com.holderzone.hardware.camera.CaptureRequest
import com.holderzone.hardware.camera.CaptureResult
import com.holderzone.hardware.camera.PreviewHost
import com.holderzone.hardware.camera.internal.log.CameraLogger
import com.holderzone.hardware.camera.internal.spi.CameraDriver
import com.holderzone.hardware.camera.internal.spi.CameraDriverFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default controller implementation that serializes all commands through one queue.
 *
 * The controller owns exactly one backend driver at a time and never shares it globally.
 */
class DefaultCameraController(
    context: Context,
    private val config: CameraConfig,
    private val driverFactories: List<CameraDriverFactory>,
    private val logger: CameraLogger,
) : CameraController {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandChannel = Channel<ControllerCommand>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)

    private val mutableState = MutableStateFlow<CameraState>(CameraState.Idle)
    private val mutableCapabilities = MutableStateFlow(CameraCapability.NONE)
    private val mutableEvents = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 32)
    private val mutableFrames = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)

    private var activeDriver: CameraDriver? = null
    private var activeBackend: CameraBackend? = null
    private var activeHost: PreviewHost? = null
    private var bridgeJobs: List<Job> = emptyList()

    override val state: StateFlow<CameraState> = mutableState.asStateFlow()
    override val events: Flow<CameraEvent> = mutableEvents.asSharedFlow()
    override val frames: SharedFlow<CameraFrame> = mutableFrames.asSharedFlow()
    override val capabilities: StateFlow<CameraCapability> = mutableCapabilities.asStateFlow()

    init {
        scope.launch {
            for (command in commandChannel) {
                when (command) {
                    is ControllerCommand.Bind -> command.finishUnit { handleBind(command.host) }
                    is ControllerCommand.Start -> command.finishUnit { handleStart() }
                    is ControllerCommand.Stop -> command.finishUnit { handleStop() }
                    is ControllerCommand.SwitchLens -> command.finishUnit { handleSwitchLens(command.facing) }
                    is ControllerCommand.SwitchToNextCamera -> command.finishUnit { handleSwitchToNextCamera() }
                    is ControllerCommand.QueryAvailableCameras -> command.finishResult { handleQueryAvailableCameras() }
                    is ControllerCommand.Capture -> command.finishResult { handleCapture(command.request) }
                    is ControllerCommand.Close -> {
                        command.finishUnit { handleCloseInternal() }
                        break
                    }
                }
            }
        }
    }

    override suspend fun bind(previewHost: PreviewHost) {
        submit<Unit>(ControllerCommand.Bind(previewHost))
    }

    override suspend fun start() {
        submit<Unit>(ControllerCommand.Start())
    }

    override suspend fun stop() {
        submit<Unit>(ControllerCommand.Stop())
    }

    override suspend fun switchLens(facing: com.holderzone.hardware.camera.LensFacing) {
        submit<Unit>(ControllerCommand.SwitchLens(facing))
    }

    override suspend fun switchToNextCamera() {
        submit<Unit>(ControllerCommand.SwitchToNextCamera())
    }

    override suspend fun queryAvailableCameras(): List<AvailableCamera> {
        return submit(ControllerCommand.QueryAvailableCameras())
    }

    override suspend fun capture(request: CaptureRequest): CaptureResult {
        return submit(ControllerCommand.Capture(request))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        closeActiveDriverForInterrupt()

        val deferred = CompletableDeferred<Unit>()
        commandChannel.trySend(ControllerCommand.Close(deferred))
        commandChannel.close()

        if (!hasMainLooper() || isMainThread()) {
            scope.launch {
                deferred.await()
                scope.cancel()
            }
        } else {
            runBlocking {
                deferred.await()
            }
            scope.cancel()
        }
    }

    private suspend fun handleBind(host: PreviewHost) {
        ensureOpen()
        if (activeHost === host && activeDriver != null) {
            return
        }

        releaseActiveDriver()
        val resolvedFactory = resolveFactory()
        val driver = resolvedFactory.create(appContext, logger)
        activeDriver = driver
        activeBackend = resolvedFactory.backend
        activeHost = host
        attachDriverBridges(driver)
        driver.bind(host, config)

        mutableCapabilities.value = driver.capabilities
        mutableState.value = CameraState.Bound(
            backend = resolvedFactory.backend,
            capability = driver.capabilities,
        )
        emitEvent(CameraEvent.BackendSelected(resolvedFactory.backend))
    }

    private suspend fun handleStart() {
        val driver = requireDriver()
        mutableState.value = CameraState.Starting(driver.backend)
        driver.start()
    }

    private suspend fun handleStop() {
        val driver = requireDriver()
        driver.stop()
        mutableState.value = CameraState.Stopped(driver.backend, driver.capabilities)
    }

    private suspend fun handleSwitchLens(facing: com.holderzone.hardware.camera.LensFacing) {
        val driver = requireDriver()
        if (!driver.capabilities.switchLens) {
            throw CameraException.ConfigurationException(
                "Backend ${driver.backend} does not support lens switching."
            )
        }
        driver.switchLens(facing)
        val currentState = mutableState.value
        mutableState.value = when (currentState) {
            is CameraState.Previewing -> CameraState.Previewing(driver.backend, driver.capabilities)
            else -> CameraState.Bound(driver.backend, driver.capabilities)
        }
    }

    private suspend fun handleSwitchToNextCamera() {
        val driver = requireDriver()
        driver.switchToNextCamera()
        val currentState = mutableState.value
        mutableState.value = when (currentState) {
            is CameraState.Previewing -> CameraState.Previewing(driver.backend, driver.capabilities)
            else -> CameraState.Bound(driver.backend, driver.capabilities)
        }
    }

    private suspend fun handleQueryAvailableCameras(): List<AvailableCamera> {
        return requireDriver().queryAvailableCameras()
    }

    private suspend fun handleCapture(request: CaptureRequest): CaptureResult {
        val driver = requireDriver()
        validateCaptureRequest(driver, request)
        val result = driver.capture(request)
        emitEvent(CameraEvent.CaptureCompleted(result))
        return result
    }

    private suspend fun handleCloseInternal() {
        releaseActiveDriver()
        mutableCapabilities.value = CameraCapability.NONE
        mutableState.value = CameraState.Closed
    }

    private suspend fun resolveFactory(): CameraDriverFactory {
        val orderedBackends = when (config.backendPreference) {
            CameraBackendPreference.AUTO -> listOf(
                CameraBackend.CAMERA_X,
                CameraBackend.CAMERA_2,
                CameraBackend.CAMERA_1,
            )
            CameraBackendPreference.CAMERA_X -> listOf(CameraBackend.CAMERA_X)
            CameraBackendPreference.CAMERA_2 -> listOf(CameraBackend.CAMERA_2)
            CameraBackendPreference.CAMERA_1 -> listOf(CameraBackend.CAMERA_1)
            CameraBackendPreference.UVC -> listOf(CameraBackend.UVC)
        }
        val factoryMap = driverFactories.associateBy { it.backend }
        for (backend in orderedBackends) {
            val factory = factoryMap[backend] ?: continue
            if (factory.isSupported(appContext, config)) {
                logger.debug("CameraController", "Selected backend $backend")
                return factory
            }
        }
        throw CameraException.DeviceUnavailableException(
            "No supported backend is available for preference ${config.backendPreference}."
        )
    }

    private fun attachDriverBridges(driver: CameraDriver) {
        bridgeJobs = listOf(
            scope.launch {
                driver.events.collect { event ->
                    emitEvent(event)
                    when (event) {
                        is CameraEvent.Error -> {
                            mutableState.value = CameraState.Error(activeBackend, event.exception)
                        }

                        is CameraEvent.PreviewStarted -> {
                            mutableState.value = CameraState.Previewing(driver.backend, driver.capabilities)
                        }

                        is CameraEvent.PreviewStopped -> {
                            mutableState.value = CameraState.Stopped(driver.backend, driver.capabilities)
                        }

                        else -> Unit
                    }
                }
            },
            scope.launch {
                driver.frames.collect { frame ->
                    mutableFrames.emit(frame)
                }
            }
        )
    }

    private suspend fun releaseActiveDriver() {
        bridgeJobs.forEach(Job::cancel)
        bridgeJobs = emptyList()

        val driver = activeDriver
        activeDriver = null
        activeBackend = null
        activeHost = null
        if (driver != null) {
            try {
                driver.close()
            } catch (exception: Throwable) {
                if (exception !is CancellationException) {
                    logger.error("CameraController", "Failed to close driver.", exception)
                }
            }
        }
    }

    private fun closeActiveDriverForInterrupt() {
        val closeAction = {
            activeDriver?.let { driver ->
                try {
                    driver.close()
                } catch (exception: Throwable) {
                    if (exception !is CancellationException) {
                        logger.error("CameraController", "Failed to interrupt active driver.", exception)
                    }
                }
            }
        }
        if (!hasMainLooper() || isMainThread()) {
            closeAction()
        } else {
            runBlocking(Dispatchers.Main.immediate) {
                closeAction()
            }
        }
    }

    private suspend fun emitEvent(event: CameraEvent) {
        mutableEvents.emit(event)
    }

    private fun requireDriver(): CameraDriver {
        ensureOpen()
        return activeDriver ?: throw CameraException.ConfigurationException(
            "CameraController must be bound before use."
        )
    }

    private fun validateCaptureRequest(driver: CameraDriver, request: CaptureRequest) {
        val capability = driver.capabilities
        when (request) {
            is CaptureRequest.Snapshot -> if (!capability.snapshotCapture) {
                throw CameraException.CaptureFailureException(
                    "Backend ${driver.backend} does not support snapshots."
                )
            }
        }
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw CameraException.ClosedException()
        }
    }

    private fun isMainThread(): Boolean {
        return try {
            val mainLooper = Looper.getMainLooper() ?: return false
            Looper.myLooper() == mainLooper
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasMainLooper(): Boolean {
        return try {
            Looper.getMainLooper() != null
        } catch (_: Throwable) {
            false
        }
    }

    private sealed interface ControllerCommand {
        val completion: CompletableDeferred<*>

        class Bind(
            val host: PreviewHost,
            override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : ControllerCommand

        class Start(
            override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : ControllerCommand

        class Stop(
            override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : ControllerCommand

        class SwitchLens(
            val facing: com.holderzone.hardware.camera.LensFacing,
            override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : ControllerCommand

        class SwitchToNextCamera(
            override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : ControllerCommand

        class QueryAvailableCameras(
            override val completion: CompletableDeferred<List<AvailableCamera>> = CompletableDeferred(),
        ) : ControllerCommand

        class Capture(
            val request: CaptureRequest,
            override val completion: CompletableDeferred<CaptureResult> = CompletableDeferred(),
        ) : ControllerCommand

        class Close(
            override val completion: CompletableDeferred<Unit>,
        ) : ControllerCommand
    }

    private suspend fun <T> submit(command: ControllerCommand): T {
        ensureOpen()
        commandChannel.send(command)
        @Suppress("UNCHECKED_CAST")
        return (command.completion as CompletableDeferred<T>).await()
    }

    private suspend fun ControllerCommand.finishUnit(block: suspend () -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val deferred = completion as CompletableDeferred<Unit>
        runCatching { block() }
            .onSuccess { deferred.complete(Unit) }
            .onFailure { failure ->
                val cameraError = failure.asCameraException()
                mutableState.value = CameraState.Error(activeBackend, cameraError)
                scope.launch { emitEvent(CameraEvent.Error(cameraError)) }
                deferred.completeExceptionally(cameraError)
            }
    }

    private suspend fun <T> ControllerCommand.finishResult(block: suspend () -> T) {
        @Suppress("UNCHECKED_CAST")
        val deferred = completion as CompletableDeferred<T>
        runCatching { block() }
            .onSuccess { deferred.complete(it) }
            .onFailure { failure ->
                val cameraError = failure.asCameraException()
                mutableState.value = CameraState.Error(activeBackend, cameraError)
                scope.launch { emitEvent(CameraEvent.Error(cameraError)) }
                deferred.completeExceptionally(cameraError)
            }
    }

    private fun Throwable.asCameraException(): CameraException {
        return this as? CameraException
            ?: CameraException.ConfigurationException(message ?: "Unexpected camera failure.", this)
    }
}
