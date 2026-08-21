package com.andmx.ui2.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class UsageFormatTest {

    @Test
    fun formatCountSmallNumbers() {
        assertEquals("0", UsageCalculator.formatCount(0L))
        assertEquals("9999", UsageCalculator.formatCount(9_999L))
    }

    @Test
    fun formatCountWan() {
        assertEquals("1万", UsageCalculator.formatCount(10_000L))
        assertEquals("1.5万", UsageCalculator.formatCount(15_000L))
        assertEquals("12.3万", UsageCalculator.formatCount(123_456L))
        assertEquals("100万", UsageCalculator.formatCount(1_000_000L))
    }

    @Test
    fun formatCountYi() {
        assertEquals("1亿", UsageCalculator.formatCount(100_000_000L))
        assertEquals("2.5亿", UsageCalculator.formatCount(250_000_000L))
    }

    @Test
    fun formatCountNegativeIsZero() {
        assertEquals("0", UsageCalculator.formatCount(-5L))
    }

    @Test
    fun formatShare() {
        assertEquals("0%", UsageCalculator.formatShare(0f))
        assertEquals("50%", UsageCalculator.formatShare(0.5f))
        assertEquals("15%", UsageCalculator.formatShare(0.154f))
        assertEquals("0.1%", UsageCalculator.formatShare(0.001f))
    }

    @Test
    fun dayStartZeroesTimeFields() {
        val ts = System.currentTimeMillis()
        val expected = Calendar.getInstance().apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expected, UsageCalculator.dayStart(ts))
    }

    @Test
    fun dayStartIdempotent() {
        val day = UsageCalculator.dayStart(System.currentTimeMillis())
        assertEquals(day, UsageCalculator.dayStart(day))
    }
}
