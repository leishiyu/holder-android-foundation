package com.holderzone.hardware.camera

import com.holderzone.hardware.camera.internal.rotateNv21
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFrameTransformsTest {

    private val source = byteArrayOf(
        0, 1, 2, 3,
        4, 5, 6, 7,
        8, 9, 10, 11,
    )

    @Test
    fun rotateNv21_clockwise90_rotatesYAndChromaAndSwapsSize() {
        val result = rotateNv21(source, width = 4, height = 2, rotationDegrees = 90)

        assertEquals(2, result.width)
        assertEquals(4, result.height)
        assertArrayEquals(
            byteArrayOf(
                4, 0,
                5, 1,
                6, 2,
                7, 3,
                8, 9,
                10, 11,
            ),
            result.nv21,
        )
    }

    @Test
    fun rotateNv21_clockwise180_preservesSizeAndRotatesChromaBlocks() {
        val result = rotateNv21(source, width = 4, height = 2, rotationDegrees = 180)

        assertEquals(4, result.width)
        assertEquals(2, result.height)
        assertArrayEquals(
            byteArrayOf(
                7, 6, 5, 4,
                3, 2, 1, 0,
                10, 11, 8, 9,
            ),
            result.nv21,
        )
    }

    @Test
    fun rotateNv21_clockwise270_rotatesYAndChromaAndSwapsSize() {
        val result = rotateNv21(source, width = 4, height = 2, rotationDegrees = 270)

        assertEquals(2, result.width)
        assertEquals(4, result.height)
        assertArrayEquals(
            byteArrayOf(
                3, 7,
                2, 6,
                1, 5,
                0, 4,
                10, 11,
                8, 9,
            ),
            result.nv21,
        )
    }

    @Test
    fun rotateNv21_zeroReturnsOriginalBufferAndSize() {
        val result = rotateNv21(source, width = 4, height = 2, rotationDegrees = 360)

        assertEquals(source, result.nv21)
        assertEquals(4, result.width)
        assertEquals(2, result.height)
    }
}
