package com.holderzone.hardware.camera.internal.spi

import com.holderzone.hardware.camera.CameraBackend
import com.holderzone.hardware.camera.CameraCapability
import com.holderzone.hardware.camera.CameraConfig
import com.holderzone.hardware.camera.CameraEvent
import com.holderzone.hardware.camera.CameraFrame
import com.holderzone.hardware.camera.CaptureRequest
import com.holderzone.hardware.camera.CaptureResult
import com.holderzone.hardware.camera.AvailableCamera
import com.holderzone.hardware.camera.LensFacing
import com.holderzone.hardware.camera.PreviewHost
import kotlinx.coroutines.flow.Flow

/**
 * Internal backend driver contract consumed by the controller.
 */
interface CameraDriver : AutoCloseable {
    val backend: CameraBackend

    val capabilities: CameraCapability

    val frames: Flow<CameraFrame>

    val events: Flow<CameraEvent>

    suspend fun bind(host: PreviewHost, config: CameraConfig)

    suspend fun start()

    suspend fun stop()

    suspend fun setFrameRotationDegrees(degrees: Int)

    suspend fun switchLens(facing: LensFacing)

    suspend fun switchToNextCamera()

    suspend fun queryAvailableCameras(): List<AvailableCamera>

    suspend fun capture(request: CaptureRequest): CaptureResult

    override fun close()
}
