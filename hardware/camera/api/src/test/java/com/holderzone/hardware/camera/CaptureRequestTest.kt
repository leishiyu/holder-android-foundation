package com.holderzone.hardware.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CaptureRequestTest {

    @Test
    fun snapshot_defaultsToSdkManagedOutput() {
        val request = CaptureRequest.Snapshot()

        assertNull(request.outputFile)
    }

    @Test
    fun snapshot_keepsCallerProvidedOutputFile() {
        val target = File("custom/output/path.jpg")
        val request = CaptureRequest.Snapshot(outputFile = target)

        assertEquals(target, request.outputFile)
    }
}
