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

/**
 * An NV21 buffer after applying a clockwise right-angle rotation.
 *
 * This type is public because the concrete backend modules are compiled separately from the API
 * module, but it is an implementation detail of the camera SDK.
 */
data class RotatedNv21(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Rotates an NV21 buffer without converting it through a Bitmap.
 *
 * The Y plane is rotated at full resolution and the interleaved VU plane is rotated by its
 * 2x2 chroma-block coordinates. This keeps the output suitable for [android.graphics.YuvImage]
 * and downstream YUV analyzers.
 */
fun rotateNv21(
    nv21: ByteArray,
    width: Int,
    height: Int,
    rotationDegrees: Int,
): RotatedNv21 {
    require(width > 0 && height > 0) {
        "NV21 dimensions must be positive."
    }
    require(width % 2 == 0 && height % 2 == 0) {
        "NV21 dimensions must be even."
    }

    val expectedSize = width * height * 3 / 2
    require(nv21.size >= expectedSize) {
        "NV21 buffer is too small for ${width}x$height: ${nv21.size} < $expectedSize."
    }

    return when (rotationDegrees.normalizeRotationDegrees()) {
        0 -> RotatedNv21(nv21, width, height)
        90 -> rotateNv21Clockwise90(nv21, width, height)
        180 -> rotateNv21180(nv21, width, height)
        270 -> rotateNv21Clockwise270(nv21, width, height)
        else -> error("Unreachable rotation branch.")
    }
}

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

private fun rotateNv21Clockwise90(
    source: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
): RotatedNv21 {
    val outputWidth = sourceHeight
    val outputHeight = sourceWidth
    val output = ByteArray(outputWidth * outputHeight * 3 / 2)
    val sourceFrameSize = sourceWidth * sourceHeight

    for (sourceY in 0 until sourceHeight) {
        for (sourceX in 0 until sourceWidth) {
            val outputX = sourceHeight - 1 - sourceY
            val outputY = sourceX
            output[outputY * outputWidth + outputX] = source[sourceY * sourceWidth + sourceX]
        }
    }

    val sourceChromaWidth = sourceWidth / 2
    val sourceChromaHeight = sourceHeight / 2
    val outputChromaWidth = outputWidth / 2
    for (sourceY in 0 until sourceChromaHeight) {
        for (sourceX in 0 until sourceChromaWidth) {
            val outputX = sourceChromaHeight - 1 - sourceY
            val outputY = sourceX
            val sourceIndex = sourceFrameSize + (sourceY * sourceChromaWidth + sourceX) * 2
            val outputIndex = outputWidth * outputHeight +
                (outputY * outputChromaWidth + outputX) * 2
            output[outputIndex] = source[sourceIndex]
            output[outputIndex + 1] = source[sourceIndex + 1]
        }
    }
    return RotatedNv21(output, outputWidth, outputHeight)
}

private fun rotateNv21180(
    source: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
): RotatedNv21 {
    val output = ByteArray(sourceWidth * sourceHeight * 3 / 2)
    val sourceFrameSize = sourceWidth * sourceHeight

    for (sourceY in 0 until sourceHeight) {
        for (sourceX in 0 until sourceWidth) {
            val outputX = sourceWidth - 1 - sourceX
            val outputY = sourceHeight - 1 - sourceY
            output[outputY * sourceWidth + outputX] = source[sourceY * sourceWidth + sourceX]
        }
    }

    val chromaWidth = sourceWidth / 2
    val chromaHeight = sourceHeight / 2
    for (sourceY in 0 until chromaHeight) {
        for (sourceX in 0 until chromaWidth) {
            val outputX = chromaWidth - 1 - sourceX
            val outputY = chromaHeight - 1 - sourceY
            val sourceIndex = sourceFrameSize + (sourceY * chromaWidth + sourceX) * 2
            val outputIndex = sourceFrameSize + (outputY * chromaWidth + outputX) * 2
            output[outputIndex] = source[sourceIndex]
            output[outputIndex + 1] = source[sourceIndex + 1]
        }
    }
    return RotatedNv21(output, sourceWidth, sourceHeight)
}

private fun rotateNv21Clockwise270(
    source: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
): RotatedNv21 {
    val outputWidth = sourceHeight
    val outputHeight = sourceWidth
    val output = ByteArray(outputWidth * outputHeight * 3 / 2)
    val sourceFrameSize = sourceWidth * sourceHeight

    for (sourceY in 0 until sourceHeight) {
        for (sourceX in 0 until sourceWidth) {
            val outputX = sourceY
            val outputY = sourceWidth - 1 - sourceX
            output[outputY * outputWidth + outputX] = source[sourceY * sourceWidth + sourceX]
        }
    }

    val sourceChromaWidth = sourceWidth / 2
    val sourceChromaHeight = sourceHeight / 2
    val outputChromaWidth = outputWidth / 2
    for (sourceY in 0 until sourceChromaHeight) {
        for (sourceX in 0 until sourceChromaWidth) {
            val outputX = sourceY
            val outputY = sourceChromaWidth - 1 - sourceX
            val sourceIndex = sourceFrameSize + (sourceY * sourceChromaWidth + sourceX) * 2
            val outputIndex = outputWidth * outputHeight +
                (outputY * outputChromaWidth + outputX) * 2
            output[outputIndex] = source[sourceIndex]
            output[outputIndex + 1] = source[sourceIndex + 1]
        }
    }
    return RotatedNv21(output, outputWidth, outputHeight)
}
