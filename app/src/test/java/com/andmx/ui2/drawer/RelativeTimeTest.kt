package com.andmx.ui2.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class RelativeTimeTest {

    private fun ago(ms: Long): Long = System.currentTimeMillis() - ms

    @Test
    fun relativeTaskTimeBuckets() {
        assertEquals("刚刚", relativeTaskTime(ago(30_000L)))
        assertEquals("刚刚", relativeTaskTime(System.currentTimeMillis() + 60_000L))
        assertEquals("5分", relativeTaskTime(ago(TimeUnit.MINUTES.toMillis(5))))
        assertEquals("59分", relativeTaskTime(ago(TimeUnit.MINUTES.toMillis(59))))
        assertEquals("3小时", relativeTaskTime(ago(TimeUnit.HOURS.toMillis(3))))
        assertEquals("23小时", relativeTaskTime(ago(TimeUnit.HOURS.toMillis(23))))
        assertEquals("2天", relativeTaskTime(ago(TimeUnit.DAYS.toMillis(2))))
        assertEquals("30天", relativeTaskTime(ago(TimeUnit.DAYS.toMillis(30))))
    }

    @Test
    fun nowIsToday() {
        assertEquals(TimelineBucket.TODAY, timelineBucket(System.currentTimeMillis()))
    }

    @Test
    fun startOfTodayIsToday() {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(TimelineBucket.TODAY, timelineBucket(todayStart))
    }

    @Test
    fun noonYesterdayIsYesterday() {
        val yesterdayNoon = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(TimelineBucket.YESTERDAY, timelineBucket(yesterdayNoon))
    }

    @Test
    fun fortyDaysAgoIsOlder() {
        val ts = ago(TimeUnit.DAYS.toMillis(40))
        assertEquals(TimelineBucket.OLDER, timelineBucket(ts))
    }

    @Test
    fun ninetyDaysAgoIsOlder() {
        val ts = ago(TimeUnit.DAYS.toMillis(90))
        assertEquals(TimelineBucket.OLDER, timelineBucket(ts))
    }

    @Test
    fun currentWeekStartFallsInTodayOrThisWeek() {
        val weekStart = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // 周一当天 weekStart == 今天(今天 bucket)；周二起 weekStart 是"昨天"(yesterday bucket)
        // 或更早——两种都合法，bucket 永不应早于本周的上一档。
        assertTrue(
            timelineBucket(weekStart) in setOf(
                TimelineBucket.TODAY,
                TimelineBucket.YESTERDAY,
                TimelineBucket.THIS_WEEK,
            ),
        )
    }

    @Test
    fun currentMonthStartNeverOlderThanThisMonth() {
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(timelineBucket(monthStart) != TimelineBucket.OLDER)
    }
}
