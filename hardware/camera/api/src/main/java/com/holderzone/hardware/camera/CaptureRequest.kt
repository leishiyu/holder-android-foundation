package com.holderzone.hardware.camera

import java.io.File

/**
 * Capture mode requested from [CameraController.capture].
 */
sealed interface CaptureRequest {

    /**
     * Optional destination file chosen by the caller.
     *
     * When null, the SDK writes into its default app cache directory.
     */
    val outputFile: File?

    /**
     * Captures the current camera output frame as a JPEG snapshot.
     */
    data class Snapshot(
        override val outputFile: File? = null,
    ) : CaptureRequest
}
