package com.holderzone.hardware.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level session controller for a single preview host.
 *
 * Each screen should create and own its own instance instead of sharing a singleton.
 */
interface CameraController : AutoCloseable {

    /**
     * Long-lived lifecycle state for the current session.
     */
    val state: StateFlow<CameraState>

    /**
     * One-shot events such as backend selection, preview lifecycle and capture completion.
     */
    val events: Flow<CameraEvent>

    /**
     * Preview frames for optional downstream analysis.
     */
    val frames: Flow<CameraFrame>

    /**
     * Currently active backend capability set.
     */
    val capabilities: StateFlow<CameraCapability>

    /**
     * Binds the controller to a preview host.
     */
    suspend fun bind(previewHost: PreviewHost)

    /**
     * Starts preview on the currently selected backend.
     */
    suspend fun start()

    /**
     * Stops preview while keeping the controller reusable.
     */
    suspend fun stop()

    /**
     * Switches the active lens when supported by the backend.
     */
    suspend fun switchLens(facing: LensFacing)

    /**
     * Switches to the next available camera exposed by the current backend.
     */
    suspend fun switchToNextCamera()

    /**
     * Returns the currently selectable cameras exposed by the active backend.
     *
     * The controller must already be bound so the backend is known.
     */
    suspend fun queryAvailableCameras(): List<AvailableCamera>

    /**
     * Captures a JPEG snapshot from the active camera output frame.
     */
    suspend fun capture(request: CaptureRequest): CaptureResult

    /**
     * Releases all resources held by this controller.
     */
    override fun close()
}
