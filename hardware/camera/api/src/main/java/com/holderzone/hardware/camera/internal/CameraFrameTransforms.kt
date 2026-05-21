package com.holderzone.hardware.camera.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import com.holderzone.hardware.camera.CameraException
import com.holderzone.hardware.camera.CameraFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

fun CameraFrame.saveAsJpeg(
    file: File,
    jpegQuality: Int,
    rotationDegrees: Int = 0,
) {
    val normalizedRotation = rotationDegrees.normalizeRotationDegrees()
    if (normalizedRotation != 0) {
        val source = toBitmap(jpegQuality = 95)
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(normalizedRotation.toFloat()) },
            true,
        )
        rotated.saveAsJpeg(file, jpegQuality)
        if (rotated !== source) {
            rotated.recycle()
        }
        source.recycle()
        return
    }

    val output = ByteArrayOutputStream()
    val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    if (!image.compressToJpeg(Rect(0, 0, width, height), jpegQuality, output)) {
        throw CameraException.CaptureFailureException("Failed to encode camera snapshot from NV21.")
    }
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { stream ->
        stream.write(output.toByteArray())
    }
}

private fun Bitmap.saveAsJpeg(
    file: File,
    jpegQuality: Int,
) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { output ->
        if (!compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
            throw CameraException.CaptureFailureException("Failed to compress camera snapshot bitmap.")
        }
    }
}

private fun CameraFrame.toBitmap(jpegQuality: Int): Bitmap {
    val output = ByteArrayOutputStream()
    val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    if (!image.compressToJpeg(Rect(0, 0, width, height), jpegQuality, output)) {
        throw CameraException.CaptureFailureException("Failed to convert camera frame to bitmap.")
    }
    return BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())
        ?: throw CameraException.CaptureFailureException("Failed to decode camera frame bitmap.")
}

private fun Int.normalizeRotationDegrees(): Int {
    return ((this % 360) + 360) % 360
}
