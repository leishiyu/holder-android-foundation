package com.holderzone.hardware.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraConfigTest {

    @Test
    fun defaultConfig_keepsAutoBackendAndBackLens() {
        val config = CameraConfig()

        assertEquals(CameraBackendPreference.AUTO, config.backendPreference)
        assertEquals(LensFacing.BACK, config.lensFacing)
        assertEquals(FrameDeliveryConfig.DISABLED, config.frameDeliveryConfig)
        assertEquals(null, config.captureSize)
        assertEquals(95, config.jpegQuality)
        assertEquals(0, config.frameRotationDegrees)
        assertEquals(UvcYuvLayout.AUTO, config.uvcFrameConfig.yuvLayout)
    }

    @Test(expected = IllegalArgumentException::class)
    fun usbSelector_requiresUvcBackend() {
        CameraConfig(
            backendPreference = CameraBackendPreference.CAMERA_X,
            usbDeviceSelector = UsbDeviceSelector.ByVidPid(vendorId = 1, productId = 2),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun externalLens_requiresUvcBackend() {
        CameraConfig(
            backendPreference = CameraBackendPreference.CAMERA_2,
            lensFacing = LensFacing.EXTERNAL,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun jpegQuality_requiresValidRange() {
        CameraConfig(jpegQuality = 101)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameRotation_requiresRightAngleDegrees() {
        CameraConfig(frameRotationDegrees = 45)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cameraSize_requiresPositiveDimensions() {
        CameraSize(width = 0, height = 720)
    }

    @Test
    fun uvcFrameConfig_keepsCallerSelectedLayout() {
        val config = CameraConfig(
            backendPreference = CameraBackendPreference.UVC,
            uvcFrameConfig = UvcFrameConfig(yuvLayout = UvcYuvLayout.NV21_DIRECT),
        )

        assertEquals(UvcYuvLayout.NV21_DIRECT, config.uvcFrameConfig.yuvLayout)
    }
}
