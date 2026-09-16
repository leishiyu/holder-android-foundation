package com.holderzone.hardware.camera.internal

import android.view.View
import com.holderzone.hardware.camera.CameraConfig
import kotlin.math.min

/**
 * Applies the SDK rotation to a backend-owned preview view.
 *
 * The concrete drivers create their own preview views, so keeping this transform in the shared
 * API source set lets every backend use the same layout behavior while remaining independently
 * compilable.
 */
class CameraPreviewRotation(
    private val previewView: View,
) : AutoCloseable {

    private var rotationDegrees = 0
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        apply()
    }

    init {
        previewView.addOnLayoutChangeListener(layoutChangeListener)
    }

    fun setRotationDegrees(degrees: Int) {
        require(degrees in CameraConfig.SUPPORTED_ROTATION_DEGREES) {
            "Preview rotation must be one of 0, 90, 180 or 270."
        }
        rotationDegrees = degrees
        apply()
        previewView.post(::apply)
    }

    override fun close() {
        previewView.removeOnLayoutChangeListener(layoutChangeListener)
        previewView.rotation = 0f
        previewView.scaleX = 1f
        previewView.scaleY = 1f
    }

    private fun apply() {
        previewView.rotation = rotationDegrees.toFloat()
        val parent = previewView.parent as? View
        if (parent == null ||
            parent.width <= 0 ||
            parent.height <= 0 ||
            previewView.width <= 0 ||
            previewView.height <= 0
        ) {
            return
        }

        previewView.pivotX = previewView.width / 2f
        previewView.pivotY = previewView.height / 2f
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            val rotatedWidth = previewView.height.toFloat()
            val rotatedHeight = previewView.width.toFloat()
            val fitScale = min(
                parent.width / rotatedWidth,
                parent.height / rotatedHeight,
            )
            previewView.scaleX = fitScale
            previewView.scaleY = fitScale
        } else {
            previewView.scaleX = 1f
            previewView.scaleY = 1f
        }
    }
}
