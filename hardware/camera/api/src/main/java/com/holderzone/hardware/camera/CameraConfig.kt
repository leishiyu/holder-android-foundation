package com.holderzone.hardware.camera

/**
 * Immutable SDK configuration used when creating a new [CameraController].
 */
data class CameraConfig(
    val backendPreference: CameraBackendPreference = CameraBackendPreference.AUTO,
    val lensFacing: LensFacing = LensFacing.BACK,
    val frameDeliveryConfig: FrameDeliveryConfig = FrameDeliveryConfig.DISABLED,
    val usbDeviceSelector: UsbDeviceSelector? = null,
    val captureSize: CameraSize? = null,
    val jpegQuality: Int = 95,
    val frameRotationDegrees: Int = 0,
    val uvcFrameConfig: UvcFrameConfig = UvcFrameConfig(),
    val enableLogging: Boolean = false,
) {
    init {
        require(jpegQuality in 1..100) {
            "jpegQuality must be in the range 1..100."
        }
        require(frameRotationDegrees in SUPPORTED_ROTATION_DEGREES) {
            "frameRotationDegrees must be one of 0, 90, 180 or 270."
        }
        require(
            backendPreference == CameraBackendPreference.UVC || usbDeviceSelector == null
        ) {
            "usbDeviceSelector can only be used with the UVC backend."
        }
        require(
            backendPreference == CameraBackendPreference.UVC || lensFacing != LensFacing.EXTERNAL
        ) {
            "LensFacing.EXTERNAL can only be used with the UVC backend."
        }
    }

    private companion object {
        val SUPPORTED_ROTATION_DEGREES = setOf(0, 90, 180, 270)
    }
}

/**
 * Desired camera output size in pixels.
 *
 * When [CameraConfig.captureSize] is null, built-in cameras default to 1280x720
 * and UVC cameras default to 640x360.
 */
data class CameraSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) {
            "CameraSize width and height must be positive."
        }
    }

    companion object {
        val HD_720P = CameraSize(width = 1280, height = 720)
        val NHD_360P = CameraSize(width = 640, height = 360)
    }
}

/**
 * UVC-specific frame decoding options.
 */
data class UvcFrameConfig(
    val yuvLayout: UvcYuvLayout = UvcYuvLayout.AUTO,
)

/**
 * Describes how UVC YUV420SP callback buffers should be normalized to NV21.
 */
enum class UvcYuvLayout {
    /**
     * Tries to infer the UV order from early frames, then falls back to [NV12_TO_NV21].
     */
    AUTO,

    /**
     * Treats callback buffers as NV12 and swaps UV bytes into NV21.
     */
    NV12_TO_NV21,

    /**
     * Treats callback buffers as already NV21-compatible.
     */
    NV21_DIRECT,
}
