package com.andmx.agent.zcode

import java.util.Calendar
import java.util.TimeZone

data class OffPeakSlot(
    val runAtMs: Long,
    val deferredWindows: Int,
    val dayCountAfter: Int,
)

object OffPeakSchedule {
    const val KIND = "off_peak"
    const val LEGACY_TITLE_PREFIX = "[闲时]"
    const val DEFAULT_MAX_CONCURRENT = 1
    const val DEFAULT_DAILY_LIMIT = 8

    fun isOffPeak(kind: String, title: String): Boolean =
        kind == KIND || title.startsWith(LEGACY_TITLE_PREFIX)

    fun windowStartAtOrAfter(nowMs: Long, startHour: Int, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, startHour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun planSlot(
        nowMs: Long,
        startHour: Int,
        existingRunAts: List<Long>,
        maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
        dailyLimit: Int = DEFAULT_DAILY_LIMIT,
        zone: TimeZone = TimeZone.getDefault(),
    ): OffPeakSlot {
        val concurrency = maxConcurrent.coerceAtLeast(1)
        val perDay = dailyLimit.coerceAtLeast(1)
        var start = windowStartAtOrAfter(nowMs, startHour, zone)
        var deferred = 0
        while (deferred < 366) {
            val nextWindow = windowStartAtOrAfter(start, startHour, zone)
            val inDay = existingRunAts.count { it in start until nextWindow }
            val sameSlot = existingRunAts.count { it == start }
            if (sameSlot < concurrency && inDay < perDay) {
                return OffPeakSlot(start, deferred, inDay + 1)
            }
            start = nextWindow
            deferred++
        }
        return OffPeakSlot(start, deferred, existingRunAts.count { it == start } + 1)
    }
}