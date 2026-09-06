package com.holderzone.hardware.scale.driver.sohe

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 首衡协议的解析结果。
 *
 * 皮重响应不是实时重量，driver 会单独处理 [Tare]，避免把皮重误推送到重量流。
 */
internal sealed interface SoheParsedFrame {
    data class Weight(
        val grams: Double,
        val stable: Boolean,
        val netMode: Boolean,
        val zero: Boolean,
        val rawFrame: String,
    ) : SoheParsedFrame

    data class Tare(
        val grams: Double,
        val rawFrame: String,
    ) : SoheParsedFrame
}

internal object SoheProtocol {
    const val FRAME_LENGTH = 13
    const val DEFAULT_BAUD_RATE = 9_600

    fun zeroCommand(): ByteArray = byteArrayOf(0x5A)

    fun tareCommand(): ByteArray = byteArrayOf(0x54)
}

/**
 * 首衡 13 字节 ASCII 帧解析器。
 *
 * 帧格式：
 * [稳定标志][重量类型][7 位重量][2 位单位][CR][LF]
 */
internal class SoheWeightFrameParser {
    private val dataPool = ByteArray(MAX_BUFFER_SIZE)
    private var currentSize = 0

    fun append(source: ByteArray, size: Int): List<SoheParsedFrame> {
        if (size <= 0) {
            return emptyList()
        }

        appendToPool(source, size)

        val frames = mutableListOf<SoheParsedFrame>()
        var consumedUntil = 0
        var scanIndex = 0
        while (scanIndex + 1 < currentSize) {
            if (dataPool[scanIndex] == CR && dataPool[scanIndex + 1] == LF) {
                val frameEnd = scanIndex + 2
                val frameStart = frameEnd - SoheProtocol.FRAME_LENGTH
                if (frameStart >= 0) {
                    parseFrame(dataPool.copyOfRange(frameStart, frameEnd))?.let(frames::add)
                }
                consumedUntil = frameEnd
                scanIndex = frameEnd
            } else {
                scanIndex += 1
            }
        }

        if (consumedUntil > 0) {
            val remaining = currentSize - consumedUntil
            if (remaining > 0) {
                dataPool.copyInto(
                    destination = dataPool,
                    destinationOffset = 0,
                    startIndex = consumedUntil,
                    endIndex = currentSize,
                )
            }
            currentSize = remaining
        }

        return frames
    }

    fun reset() {
        currentSize = 0
    }

    private fun appendToPool(source: ByteArray, size: Int) {
        val sourceStart = (size - MAX_BUFFER_SIZE).coerceAtLeast(0)
        val appendSize = size - sourceStart
        val overflow = currentSize + appendSize - MAX_BUFFER_SIZE
        if (overflow > 0) {
            if (overflow < currentSize) {
                dataPool.copyInto(
                    destination = dataPool,
                    destinationOffset = 0,
                    startIndex = overflow,
                    endIndex = currentSize,
                )
            }
            currentSize = (currentSize - overflow).coerceAtLeast(0)
        }

        source.copyInto(
            destination = dataPool,
            destinationOffset = currentSize,
            startIndex = sourceStart,
            endIndex = size,
        )
        currentSize += appendSize
    }

    private fun parseFrame(frame: ByteArray): SoheParsedFrame? {
        if (frame.size != SoheProtocol.FRAME_LENGTH ||
            frame[11] != CR ||
            frame[12] != LF
        ) {
            return null
        }

        val rawFrame = String(frame, StandardCharsets.US_ASCII)
        val type = rawFrame[1].lowercaseChar()
        val weight = rawFrame.substring(2, 9).trim().toDoubleOrNull() ?: return null
        if (!weight.isFinite()) {
            return null
        }

        return when (type) {
            'n', 'g' -> {
                val status = rawFrame[0].lowercaseChar()
                if (status != 's' && status != 'w') {
                    return null
                }
                val multiplier = unitMultiplier(rawFrame.substring(9, 11)) ?: return null
                val grams = weight * multiplier
                SoheParsedFrame.Weight(
                    grams = grams,
                    stable = status == 's',
                    netMode = type == 'n',
                    zero = grams == 0.0,
                    rawFrame = rawFrame,
                )
            }

            // 首衡文档规定皮重返回帧首位和单位位均可为任意值，数值单位固定为 kg。
            't' -> SoheParsedFrame.Tare(
                grams = weight * GRAMS_PER_KILOGRAM,
                rawFrame = rawFrame,
            )

            else -> null
        }
    }

    private fun unitMultiplier(unit: String): Double? {
        return when (unit.lowercase(Locale.US)) {
            "kg" -> GRAMS_PER_KILOGRAM
            "sj" -> GRAMS_PER_SHIJIN
            " g" -> 1.0
            "lb" -> GRAMS_PER_POUND
            else -> null
        }
    }

    private companion object {
        const val MAX_BUFFER_SIZE = 2_048
        const val GRAMS_PER_KILOGRAM = 1_000.0
        const val GRAMS_PER_SHIJIN = 500.0
        const val GRAMS_PER_POUND = 453.59237
        const val CR: Byte = 0x0D
        const val LF: Byte = 0x0A
    }
}
