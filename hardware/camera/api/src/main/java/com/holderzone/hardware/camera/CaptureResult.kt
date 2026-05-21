package com.holderzone.hardware.camera

/**
 * The saved output returned from [CameraController.capture].
 */
data class CaptureResult(
    val path: String,
    val kind: CaptureKind,
    val backend: CameraBackend,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/**
 * Distinguishes the saved capture output kind.
 */
enum class CaptureKind {
    SNAPSHOT,
}
