package com.andmx.agent.zcode

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffPeakScheduleTest {
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun windowStartRollsToNextDayWhenPassed() {
        assertEquals(at(17, 2), OffPeakSchedule.windowStartAtOrAfter(at(16, 23), 2, zone))
        assertEquals(at(17, 2), OffPeakSchedule.windowStartAtOrAfter(at(16, 2), 2, zone))
        assertEquals(at(16, 2), OffPeakSchedule.windowStartAtOrAfter(at(16, 1, 59), 2, zone))
    }

    @Test
    fun firstTaskLandsInNextWindowWithoutDeferral() {
        val slot = OffPeakSchedule.planSlot(at(16, 12), 2, emptyList(), zone = zone)
        assertEquals(at(17, 2), slot.runAtMs)
        assertEquals(0, slot.deferredWindows)
        assertEquals(1, slot.dayCountAfter)
    }

    @Test
    fun concurrencyLimitQueuesToNextWindow() {
        val slot = OffPeakSchedule.planSlot(at(16, 12), 2, listOf(at(17, 2)), zone = zone)
        assertEquals(at(18, 2), slot.runAtMs)
        assertEquals(1, slot.deferredWindows)
        assertEquals(1, slot.dayCountAfter)
    }

    @Test
    fun higherConcurrencySharesWindow() {
        val slot = OffPeakSchedule.planSlot(at(16, 12), 2, listOf(at(17, 2)), maxConcurrent = 2, zone = zone)
        assertEquals(at(17, 2), slot.runAtMs)
        assertEquals(0, slot.deferredWindows)
        assertEquals(2, slot.dayCountAfter)
    }

    @Test
    fun dailyLimitQueuesToNextWindowEvenWithFreeConcurrency() {
        val existing = (0 until 8).map { at(17, 2) + it * 60_000L }
        val slot = OffPeakSchedule.planSlot(at(16, 12), 2, existing, maxConcurrent = 8, dailyLimit = 8, zone = zone)
        assertEquals(at(18, 2), slot.runAtMs)
        assertEquals(1, slot.deferredWindows)
        assertEquals(1, slot.dayCountAfter)
    }

    @Test
    fun tasksInOtherWindowsDoNotConsumeQuota() {
        val existing = listOf(at(15, 2), at(16, 2), at(19, 2))
        val slot = OffPeakSchedule.planSlot(at(16, 12), 2, existing, zone = zone)
        assertEquals(at(17, 2), slot.runAtMs)
        assertEquals(0, slot.deferredWindows)
    }

    @Test
    fun nonPositiveQuotasFallBackToOne() {
        val slot = OffPeakSchedule.planSlot(at(16, 12), 2, listOf(at(17, 2)), maxConcurrent = 0, dailyLimit = -3, zone = zone)
        assertEquals(at(18, 2), slot.runAtMs)
        assertEquals(1, slot.deferredWindows)
    }

    @Test
    fun offPeakDetectionUsesKindWithLegacyTitleFallback() {
        assertTrue(OffPeakSchedule.isOffPeak("off_peak", "普通标题"))
        assertTrue(OffPeakSchedule.isOffPeak("", "[闲时] 旧任务"))
        assertFalse(OffPeakSchedule.isOffPeak("", "普通任务"))
        assertFalse(OffPeakSchedule.isOffPeak("cron", "[闲时之外的标题"))
    }
}