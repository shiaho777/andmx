package com.andmx.agent

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronScheduleTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply { set(y, mo - 1, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }
            .timeInMillis

    @Test
    fun fiveFieldCronParsesAndFindsNextMinute() {
        val now = at(2025, 6, 15, 10, 30)
        assertTrue(CronSchedule.isValid("*/5 * * * *"))
        assertEquals(at(2025, 6, 15, 10, 35), CronSchedule.nextAfter("*/5 * * * *", now))
        assertFalse(CronSchedule.isValid("0 9 * *"))
        assertFalse(CronSchedule.isValid("bogus * * * *"))
    }

    @Test
    fun weekdayAndMonthFieldsRespected() {
        // 2025-06-16 is a Monday.
        val fri = at(2025, 6, 13, 8, 0)
        assertEquals(at(2025, 6, 16, 9, 0), CronSchedule.nextAfter("0 9 * * 1", fri))
        assertEquals(at(2025, 7, 1, 0, 0), CronSchedule.nextAfter("0 0 1 * *", at(2025, 6, 15, 0, 0)))
    }

    @Test
    fun delayMinutesBuildsAnchoredMinuteRule() {
        val now = at(2025, 6, 15, 10, 30)
        val (spec, err) = CronSchedule.buildSpec(
            title = "in 3 minutes", prompt = "do x",
            cron = null, delayMinutes = 3, recurring = false, maxRuns = null,
            intervalUnit = null, interval = null, now = now,
        )
        assertNull(err)
        assertEquals("MINUTE", spec!!.intervalUnit)
        assertEquals(3, spec.interval)
        assertEquals(now, spec.anchorAt)
        assertEquals(at(2025, 6, 15, 10, 33), spec.nextRunAt)
        assertEquals(1, spec.maxRuns)
    }

    @Test
    fun intervalRuleUsesCronCalendarSlot() {
        // every 40 days at 09:00 — anchor day + 40k days at 09:00.
        val now = at(2025, 6, 15, 10, 0)
        val (spec, err) = CronSchedule.buildSpec(
            title = "every 40 days", prompt = "do x",
            cron = "0 9 * * *", delayMinutes = null, recurring = true, maxRuns = null,
            intervalUnit = "daily", interval = 40, now = now,
        )
        assertNull(err)
        assertEquals("DAILY", spec!!.intervalUnit)
        assertEquals(40, spec.interval)
        assertEquals(at(2025, 7, 25, 9, 0), spec.nextRunAt)
    }

    @Test
    fun staleOneShotRejectsInsteadOfRollingYear() {
        // "09:00 today" created at 09:02 — the only same-calendar occurrence passed;
        // naive nextAfter would roll to next year.
        val now = at(2025, 6, 15, 9, 2)
        val (spec, err) = CronSchedule.buildSpec(
            title = "at 9", prompt = "do x",
            cron = "0 9 15 6 *", delayMinutes = null, recurring = false, maxRuns = null,
            intervalUnit = null, interval = null, now = now,
        )
        assertNull(spec)
        assertNotNull(err)
    }

    @Test
    fun recurringCronStillRollsForward() {
        val now = at(2025, 6, 15, 10, 0)
        val (spec, err) = CronSchedule.buildSpec(
            title = "daily", prompt = "do x",
            cron = "0 9 * * *", delayMinutes = null, recurring = true, maxRuns = null,
            intervalUnit = null, interval = null, now = now,
        )
        assertNull(err)
        assertEquals(at(2025, 6, 16, 9, 0), spec!!.nextRunAt)
    }

    @Test
    fun recurringCannotCombineMaxRuns() {
        val (_, err) = CronSchedule.buildSpec(
            title = "t", prompt = "p",
            cron = "0 9 * * *", delayMinutes = null, recurring = true, maxRuns = 3,
            intervalUnit = null, interval = null, now = at(2025, 6, 15, 10, 0),
        )
        assertNotNull(err)
    }

    @Test
    fun oneShotCompletesAfterMaxRuns() {
        val (count, next, status) = CronSchedule.advanceAfterRun(
            cronExpr = "0 9 * * *", intervalUnit = "", interval = 0, anchorAt = 0L,
            recurring = false, maxRuns = 2, runCount = 0, now = at(2025, 6, 15, 10, 0),
        )
        assertEquals(1, count)
        assertEquals("active", status)
        assertEquals(at(2025, 6, 16, 9, 0), next)

        val (count2, next2, status2) = CronSchedule.advanceAfterRun(
            cronExpr = "0 9 * * *", intervalUnit = "", interval = 0, anchorAt = 0L,
            recurring = false, maxRuns = 2, runCount = 1, now = at(2025, 6, 16, 10, 0),
        )
        assertEquals(2, count2)
        assertEquals("completed", status2)
        assertEquals(0L, next2)
    }
}
