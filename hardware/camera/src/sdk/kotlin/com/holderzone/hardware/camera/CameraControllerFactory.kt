package com.holderzone.hardware.camera

import android.content.Context
import com.holderzone.hardware.camera.core.AndroidCameraLogger
import com.holderzone.hardware.camera.core.DefaultCameraController
import com.holderzone.hardware.camera.driver.camera1.Camera1DriverFactory
import com.holderzone.hardware.camera.driver.camera2.Camera2DriverFactory
import com.holderzone.hardware.camera.driver.camerax.CameraXDriverFactory
import com.holderzone.hardware.camera.driver.uvc.UvcDriverFactory

/**
 * Creates isolated [CameraController] instances for each host screen.
 *
 * The factory never caches controllers globally. Every call returns a fresh controller that owns
 * its own backend driver lifecycle and must be released by calling [CameraController.close].
 */
object CameraControllerFactory {

    /**
     * Creates a new [CameraController] using the default backend family wiring.
     */
    @JvmStatic
    fun create(
        context: Context,
        config: CameraConfig = CameraConfig(),
    ): CameraController {
        return DefaultCameraController(
            context = context,
            config = config,
            driverFactories = listOf(
                CameraXDriverFactory(),
                Camera2DriverFactory(),
                Camera1DriverFactory(),
                UvcDriverFactory(),
            ),
            logger = AndroidCameraLogger(enabled = config.enableLogging),
        )
    }
}
