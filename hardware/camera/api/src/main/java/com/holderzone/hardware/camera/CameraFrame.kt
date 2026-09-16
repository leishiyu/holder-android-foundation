package com.holderzone.hardware.camera

import android.graphics.ImageFormat

/**
 * Normalized preview frame transported to analysis pipelines.
 *
 * The SDK applies the configured rotation to [nv21] before emitting this frame. Therefore
 * [width] and [height] describe the rotated pixel buffer and [rotationDegrees] is always `0`
 * for frames emitted by [CameraController]. The field remains available for compatibility with
 * callers that construct [CameraFrame] instances themselves.
 */
data class CameraFrame(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val format: Int = ImageFormat.NV21,
    val timestampNs: Long = System.nanoTime(),
)
