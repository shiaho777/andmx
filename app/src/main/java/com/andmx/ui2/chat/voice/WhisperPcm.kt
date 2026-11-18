package com.andmx.ui2.chat.voice

object WhisperPcm {
    const val SAMPLE_RATE = 16_000

    fun pcm16leToFloat(bytes: ByteArray): FloatArray {
        val count = bytes.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            out[i] = sample.toShort() / 32768f
        }
        return out
    }

    fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty() || fromRate <= 0 || toRate <= 0) return FloatArray(0)
        if (fromRate == toRate) return input
        val outLength = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        val step = fromRate.toDouble() / toRate
        val last = input.size - 1
        for (i in 0 until outLength) {
            val pos = i * step
            val idx = pos.toInt().coerceAtMost(last)
            val frac = (pos - idx).toFloat()
            val next = (idx + 1).coerceAtMost(last)
            out[i] = input[idx] + (input[next] - input[idx]) * frac
        }
        return out
    }

    fun prepare(bytes: ByteArray, sourceRate: Int): FloatArray =
        resampleLinear(pcm16leToFloat(bytes), sourceRate, SAMPLE_RATE)
}

fun whisperLanguageFor(localeTag: String): String = when {
    localeTag.startsWith("zh") -> "zh"
    localeTag.startsWith("en") -> "en"
    else -> "auto"
}
