package com.holderzone.hardware.camera.core

import android.content.Context
import android.view.View
import com.holderzone.hardware.camera.AvailableCamera
import com.holderzone.hardware.camera.CameraBackend
import com.holderzone.hardware.camera.CameraCapability
import com.holderzone.hardware.camera.CameraConfig
import com.holderzone.hardware.camera.CameraEvent
import com.holderzone.hardware.camera.CameraFrame
import com.holderzone.hardware.camera.CameraState
import com.holderzone.hardware.camera.CaptureKind
import com.holderzone.hardware.camera.CaptureRequest
import com.holderzone.hardware.camera.CaptureResult
import com.holderzone.hardware.camera.LensFacing
import com.holderzone.hardware.camera.PreviewHost
import com.holderzone.hardware.camera.internal.log.NoopCameraLogger
import com.holderzone.hardware.camera.internal.spi.CameraDriver
import com.holderzone.hardware.camera.internal.spi.CameraDriverFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCameraControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun repeatedStartAndStop_remainIdempotent() = runTest(dispatcher) {
        val driver = FakeDriver()
        val context = newContext()
        val controller = DefaultCameraController(
            context = context,
            config = CameraConfig(),
            driverFactories = listOf(FakeDriverFactory(driver)),
            logger = NoopCameraLogger,
        )

        controller.bind(FakePreviewHost(context))
        controller.start()
        controller.start()
        controller.stop()
        controller.stop()
        advanceUntilIdle()

        assertTrue(driver.bindCount >= 1)
        assertEquals(2, driver.startCount)
        assertEquals(2, driver.stopCount)
        assertTrue(controller.state.value is CameraState.Stopped)

        controller.close()
        advanceUntilIdle()
    }

    @Test
    fun captureDelegatesToDriver() = runTest(dispatcher) {
        val driver = FakeDriver()
        val context = newContext()
        val controller = DefaultCameraController(
            context = context,
            config = CameraConfig(),
            driverFactories = listOf(FakeDriverFactory(driver)),
            logger = NoopCameraLogger,
        )

        controller.bind(FakePreviewHost(context))
        val expectedOutput = File("build/test-custom-output.jpg")
        val result = controller.capture(CaptureRequest.Snapshot(outputFile = expectedOutput))

        assertEquals(CaptureKind.SNAPSHOT, result.kind)
        assertEquals(1, driver.captureCount)
        assertEquals(expectedOutput, driver.lastRequest?.outputFile)
        controller.close()
        advanceUntilIdle()
    }

    @Test
    fun queryAndSwitchToNextCamera_delegateToDriver() = runTest(dispatcher) {
        val driver = FakeDriver().apply {
            availableCameras = listOf(
                AvailableCamera(
                    index = 0,
                    id = "0",
                    displayName = "Back",
                    backend = backend,
                    isActive = true,
                ),
                AvailableCamera(
                    index = 1,
                    id = "1",
                    displayName = "Front",
                    backend = backend,
                ),
            )
        }
        val context = newContext()
        val controller = DefaultCameraController(
            context = context,
            config = CameraConfig(),
            driverFactories = listOf(FakeDriverFactory(driver)),
            logger = NoopCameraLogger,
        )

        controller.bind(FakePreviewHost(context))
        val cameras = controller.queryAvailableCameras()
        controller.switchToNextCamera()

        assertEquals(driver.availableCameras, cameras)
        assertEquals(1, driver.queryCameraCount)
        assertEquals(1, driver.switchNextCameraCount)
        controller.close()
        advanceUntilIdle()
    }

    private class FakeDriverFactory(
        private val driver: FakeDriver,
    ) : CameraDriverFactory {
        override val backend: CameraBackend = CameraBackend.CAMERA_X

        override suspend fun isSupported(appContext: Context, config: CameraConfig): Boolean = true

        override fun create(
            appContext: Context,
            logger: com.holderzone.hardware.camera.internal.log.CameraLogger,
        ): CameraDriver = driver
    }

    private class FakeDriver : CameraDriver {
        override val backend: CameraBackend = CameraBackend.CAMERA_X
        override val capabilities: CameraCapability = CameraCapability(
            switchLens = true,
            switchCamera = true,
            snapshotCapture = true,
            frameStreaming = true,
        )
        override val frames: Flow<CameraFrame> = emptyFlow()
        override val events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 8)

        var bindCount = 0
        var startCount = 0
        var stopCount = 0
        var captureCount = 0
        var switchNextCameraCount = 0
        var queryCameraCount = 0
        var lastRequest: CaptureRequest? = null
        var availableCameras: List<AvailableCamera> = emptyList()

        override suspend fun bind(host: PreviewHost, config: CameraConfig) {
            bindCount += 1
        }

        override suspend fun start() {
            startCount += 1
            events.emit(CameraEvent.PreviewStarted(backend))
        }

        override suspend fun stop() {
            stopCount += 1
            events.emit(CameraEvent.PreviewStopped(backend))
        }

        override suspend fun switchLens(facing: LensFacing) = Unit

        override suspend fun switchToNextCamera() {
            switchNextCameraCount += 1
        }

        override suspend fun queryAvailableCameras(): List<AvailableCamera> {
            queryCameraCount += 1
            return availableCameras
        }

        override suspend fun capture(request: CaptureRequest): CaptureResult {
            captureCount += 1
            lastRequest = request
            return CaptureResult(
                path = "test.jpg",
                kind = CaptureKind.SNAPSHOT,
                backend = backend,
            )
        }

        override fun close() = Unit
    }

    private class FakePreviewHost(
        override val previewContext: Context,
    ) : PreviewHost {

        override fun attachPreview(view: View) = Unit

        override fun detachPreview(view: View) = Unit
    }

    private fun newContext(): Context {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        return context
    }
}
