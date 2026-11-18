package com.andmx.ui2.chat.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperPcmTest {

    @Test
    fun pcm16leConvertsKnownSamples() {
        val bytes = byteArrayOf(
            0x00, 0x00,
            0x00, 0x40,
            0x00, 0xC0.toByte(),
            0xFF.toByte(), 0x7F,
            0x00, 0x80.toByte(),
        )
        val out = WhisperPcm.pcm16leToFloat(bytes)
        assertEquals(5, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(-0.5f, out[2], 1e-6f)
        assertEquals(32767f / 32768f, out[3], 1e-6f)
        assertEquals(-1f, out[4], 1e-6f)
    }

    @Test
    fun pcm16leIgnoresTrailingOddByte() {
        val out = WhisperPcm.pcm16leToFloat(byteArrayOf(0x00, 0x40, 0x12))
        assertEquals(1, out.size)
        assertEquals(0.5f, out[0], 1e-6f)
    }

    @Test
    fun pcm16leEmptyInput() {
        assertEquals(0, WhisperPcm.pcm16leToFloat(ByteArray(0)).size)
    }

    @Test
    fun resampleSameRateReturnsInput() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertTrue(WhisperPcm.resampleLinear(input, 16_000, 16_000) === input)
    }

    @Test
    fun resampleDownsampleHalvesLength() {
        val input = FloatArray(480) { it / 480f }
        val out = WhisperPcm.resampleLinear(input, 48_000, 16_000)
        assertEquals(160, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(input[3], out[1], 1e-6f)
        assertEquals(input[6], out[2], 1e-6f)
    }

    @Test
    fun resampleUpsampleInterpolatesLinearly() {
        val input = floatArrayOf(0f, 1f)
        val out = WhisperPcm.resampleLinear(input, 8_000, 16_000)
        assertEquals(4, out.size)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 1f), out, 1e-6f)
    }

    @Test
    fun resampleNonIntegerRatio() {
        val input = FloatArray(441) { 0.25f }
        val out = WhisperPcm.resampleLinear(input, 44_100, 16_000)
        assertEquals(160, out.size)
        out.forEach { assertEquals(0.25f, it, 1e-6f) }
    }

    @Test
    fun resampleEmptyOrInvalidRates() {
        assertEquals(0, WhisperPcm.resampleLinear(FloatArray(0), 48_000, 16_000).size)
        assertEquals(0, WhisperPcm.resampleLinear(floatArrayOf(1f), 0, 16_000).size)
        assertEquals(0, WhisperPcm.resampleLinear(floatArrayOf(1f), 48_000, 0).size)
    }

    @Test
    fun prepareChopsHeaderlessPcmToWhisperRate() {
        val samples = ShortArray(480) { (it * 60) .toShort() }
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            bytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }
        val out = WhisperPcm.prepare(bytes, 48_000)
        assertEquals(160, out.size)
        assertEquals(samples[0] / 32768f, out[0], 1e-6f)
        assertEquals(samples[3] / 32768f, out[1], 1e-6f)
    }

    @Test
    fun whisperLanguageMapping() {
        assertEquals("zh", whisperLanguageFor("zh-CN"))
        assertEquals("en", whisperLanguageFor("en-US"))
        assertEquals("auto", whisperLanguageFor("fr-FR"))
        assertEquals("auto", whisperLanguageFor(""))
    }
}
