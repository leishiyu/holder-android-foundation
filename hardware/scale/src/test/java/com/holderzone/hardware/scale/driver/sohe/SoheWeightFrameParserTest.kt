package com.holderzone.hardware.scale.driver.sohe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SoheWeightFrameParserTest {

    @Test
    fun `parses stable net shijin frame into grams`() {
        val frame = parse("sn0001.23sj\r\n").single() as SoheParsedFrame.Weight

        assertEquals(615.0, frame.grams, 0.0001)
        assertTrue(frame.stable)
        assertTrue(frame.netMode)
        assertEquals("sn0001.23sj\r\n", frame.rawFrame)
    }

    @Test
    fun `parses unstable gross gram frame`() {
        val frame = parse("wg0001.23 g\r\n").single() as SoheParsedFrame.Weight

        assertEquals(1.23, frame.grams, 0.0001)
        assertTrue(!frame.stable)
        assertTrue(!frame.netMode)
    }

    @Test
    fun `parses kilogram and pound units`() {
        val frames = parse("sg0001.23kg\r\nsg0001.23lb\r\n")
            .filterIsInstance<SoheParsedFrame.Weight>()

        assertEquals(2, frames.size)
        assertEquals(1_230.0, frames[0].grams, 0.0001)
        assertEquals(1.23 * 453.59237, frames[1].grams, 0.0001)
    }

    @Test
    fun `parses tare response as kilograms regardless of status and unit`() {
        val frame = parse("Xt0001.23XX\r\n").single() as SoheParsedFrame.Tare

        assertEquals(1_230.0, frame.grams, 0.0001)
    }

    @Test
    fun `handles fragmented and concatenated frames`() {
        val parser = SoheWeightFrameParser()

        assertTrue(parser.append(ascii("sn000"), 5).isEmpty())
        val frames = parser.append(
            ascii("1.23sj\r\nwg0001.23 g\r\n"),
            "1.23sj\r\nwg0001.23 g\r\n".length,
        )

        assertEquals(2, frames.size)
    }

    @Test
    fun `skips malformed frame and resynchronizes at next crlf`() {
        val frames = parse("sn0001.23oz\r\nsn0001.23kg\r\n")
            .filterIsInstance<SoheParsedFrame.Weight>()

        assertEquals(1, frames.size)
        assertEquals(1_230.0, frames.single().grams, 0.0001)
    }

    @Test
    fun `exports zero and tare protocol commands`() {
        assertArrayEquals(byteArrayOf(0x5A), SoheProtocol.zeroCommand())
        assertArrayEquals(byteArrayOf(0x54), SoheProtocol.tareCommand())
    }

    private fun parse(value: String): List<SoheParsedFrame> {
        val parser = SoheWeightFrameParser()
        val bytes = ascii(value)
        return parser.append(bytes, bytes.size)
    }

    private fun ascii(value: String): ByteArray {
        return value.toByteArray(StandardCharsets.US_ASCII)
    }
}
