package com.andmx.ui2.drawer

fun relativeTime(ts: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - ts).coerceAtLeast(0)
    val min = diff / 60_000
    val hour = diff / 3_600_000
    val day = diff / 86_400_000
    return when {
        min < 1 -> "刚刚"
        hour < 1 -> "$min 分钟前"
        day < 1 -> "$hour 小时前"
        day < 30 -> "$day 天前"
        else -> "${day / 30} 个月前"
    }
}
