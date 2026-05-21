package com.holderzone.hardware.camera

/**
 * Capabilities exposed by the currently selected backend.
 */
data class CameraCapability(
    val switchLens: Boolean = false,
    val switchCamera: Boolean = false,
    val snapshotCapture: Boolean = false,
    val frameStreaming: Boolean = false,
    val uvcSelection: Boolean = false,
) {
    companion object {
        val NONE = CameraCapability()
    }
}
