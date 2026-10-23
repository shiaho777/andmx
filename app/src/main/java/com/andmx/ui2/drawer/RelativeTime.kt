package com.andmx.ui2.drawer

import java.util.Calendar
import java.util.concurrent.TimeUnit

fun activityTimestamp(createdAt: Long, updatedAt: Long, preferCreated: Boolean): Long {
    val primary = if (preferCreated) createdAt else updatedAt
    val fallback = if (preferCreated) updatedAt else createdAt
    return when {
        primary > 0L -> primary
        fallback > 0L -> fallback
        else -> 0L
    }
}

fun historySentLabel(sentAt: Long, now: Long = System.currentTimeMillis()): String {
    if (sentAt <= 0L) return ""
    val sent = Calendar.getInstance().apply { timeInMillis = sentAt }
    val clock = String.format("%02d:%02d", sent.get(Calendar.HOUR_OF_DAY), sent.get(Calendar.MINUTE))
    val today = Calendar.getInstance().apply { timeInMillis = now }
    if (sent.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        sent.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    ) {
        return clock
    }
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sent.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        sent.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) {
        return com.andmx.ui2.settings.tr("昨天 $clock")
    }
    return if (sent.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
        com.andmx.ui2.settings.tr("${sent.get(Calendar.MONTH) + 1}月${sent.get(Calendar.DAY_OF_MONTH)}日 $clock")
    } else {
        com.andmx.ui2.settings.tr("${sent.get(Calendar.YEAR)}年${sent.get(Calendar.MONTH) + 1}月${sent.get(Calendar.DAY_OF_MONTH)}日 $clock")
    }
}

fun relativeTaskTime(ts: Long): String {
    if (ts <= 0L) return ""
    val now = System.currentTimeMillis()
    val delta = (now - ts).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> com.andmx.ui2.settings.tr("刚刚")
        minutes < 60 -> com.andmx.ui2.settings.tr("${minutes}分")
        hours < 24 -> com.andmx.ui2.settings.tr("${hours}小时")
        else -> com.andmx.ui2.settings.tr("${days}天")
    }
}

enum class TimelineBucket(private val zh: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    THIS_WEEK("本周"),
    LAST_WEEK("上周"),
    THIS_MONTH("本月"),
    OLDER("更早"),
    ;
    val label: String get() = com.andmx.ui2.settings.tr(zh)
}

fun timelineBucket(ts: Long): TimelineBucket {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = ts }
    fun startOfDay(c: Calendar): Calendar = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val today = startOfDay(now)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val weekStart = (today.clone() as Calendar).apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    }
    val lastWeekStart = (weekStart.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, -1) }
    val monthStart = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
    return when {
        !target.before(today) -> TimelineBucket.TODAY
        !target.before(yesterday) -> TimelineBucket.YESTERDAY
        !target.before(weekStart) -> TimelineBucket.THIS_WEEK
        !target.before(lastWeekStart) -> TimelineBucket.LAST_WEEK
        !target.before(monthStart) -> TimelineBucket.THIS_MONTH
        else -> TimelineBucket.OLDER
    }
}

enum class ViewMode { GROUPED, BY_PROJECT, TIMELINE }
enum class SortMode { UPDATED, CREATED }

val groupColors = mapOf(
    "gray" to androidx.compose.ui.graphics.Color(0xFF9E9E9E),
    "red" to androidx.compose.ui.graphics.Color(0xFFEF5350),
    "orange" to androidx.compose.ui.graphics.Color(0xFFFFA726),
    "yellow" to androidx.compose.ui.graphics.Color(0xFFFFEE58),
    "green" to androidx.compose.ui.graphics.Color(0xFF66BB6A),
    "blue" to androidx.compose.ui.graphics.Color(0xFF42A5F5),
    "purple" to androidx.compose.ui.graphics.Color(0xFFAB47BC),
)
