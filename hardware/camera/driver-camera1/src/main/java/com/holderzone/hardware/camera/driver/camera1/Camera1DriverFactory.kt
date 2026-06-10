@file:Suppress("DEPRECATION")

package com.holderzone.hardware.camera.driver.camera1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import androidx.core.content.ContextCompat
import com.holderzone.hardware.camera.CameraBackend
import com.holderzone.hardware.camera.CameraConfig
import com.holderzone.hardware.camera.internal.log.CameraLogger
import com.holderzone.hardware.camera.internal.spi.CameraDriver
import com.holderzone.hardware.camera.internal.spi.CameraDriverFactory

/**
 * Factory for the deprecated Camera1 compatibility backend.
 */
class Camera1DriverFactory : CameraDriverFactory {
    override val backend: CameraBackend = CameraBackend.CAMERA_1

    override suspend fun isSupported(appContext: Context, config: CameraConfig): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        return hasPermission && runCatching { Camera.getNumberOfCameras() > 0 }.getOrDefault(false)
    }

    override fun create(appContext: Context, logger: CameraLogger): CameraDriver {
        return Camera1CameraDriver(appContext, logger)
    }
}
