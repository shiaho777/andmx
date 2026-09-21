package com.andmx.agent

import java.util.Calendar

/**
 * ZCode `automationCron.ts` 对齐：5 段 cron 解析 + scheduleRule 的 nextRunAt 计算。
 * 全部本地时区语义（android 本地时间 = 用户时区），不做 UTC 换算。
 */
object CronSchedule {

    enum class IntervalUnit { MINUTE, HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY }

    /**
     * 「每 N 单位」规则（上游 carrier cron + scheduleRule）：间隔从 anchorAt 锚定，
     * 不做墙钟对齐；hour/minute 只在 daily 及以上粒度有意义。
     */
    data class ScheduleRule(
        val unit: IntervalUnit,
        val interval: Int,
        val hour: Int = 0,
        val minute: Int = 0,
        val anchorAt: Long = 0L,
    )

    // ── 5-field cron ────────────────────────────────────────

    private fun parseField(spec: String, min: Int, max: Int): IntArray? {
        val out = sortedSetOf<Int>()
        for (part in spec.split(',')) {
            val p = part.trim()
            if (p.isEmpty()) return null
            val stepSplit = p.split('/')
            if (stepSplit.size > 2) return null
            val step = stepSplit.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            if (stepSplit.size == 2 && stepSplit[1].toIntOrNull() == null) return null
            val range = stepSplit[0].trim()
            val (lo, hi) = when {
                range == "*" -> min to max
                range.contains('-') -> {
                    val r = range.split('-')
                    if (r.size != 2) return null
                    val a = r[0].toIntOrNull() ?: return null
                    val b = r[1].toIntOrNull() ?: return null
                    if (a > b) return null
                    a to b
                }
                else -> {
                    val v = range.toIntOrNull() ?: return null
                    if (stepSplit.size == 2) v to max else v to v
                }
            }
            if (lo < min || hi > max) return null
            var v = lo
            while (v <= hi) {
                out += v
                v += step
            }
        }
        return out.toIntArray()
    }

    class CronExpr private constructor(
        val minutes: IntArray,
        val hours: IntArray,
        val doms: IntArray,
        val months: IntArray,
        val dows: IntArray,
        val domRestricted: Boolean,
        val dowRestricted: Boolean,
    ) {
        fun matches(cal: Calendar): Boolean {
            val dow = (cal.get(Calendar.DAY_OF_WEEK) + 6) % 7
            val domOk = cal.get(Calendar.DAY_OF_MONTH) in doms
            val dowOk = dow in dows
            val dayOk = when {
                domRestricted && dowRestricted -> domOk || dowOk
                else -> domOk && dowOk
            }
            return dayOk &&
                cal.get(Calendar.MINUTE) in minutes &&
                cal.get(Calendar.HOUR_OF_DAY) in hours &&
                (cal.get(Calendar.MONTH) + 1) in months
        }

        companion object {
            fun parse(expr: String): CronExpr? {
                val f = expr.trim().split(Regex("\\s+"))
                if (f.size != 5) return null
                val minutes = parseField(f[0], 0, 59) ?: return null
                val hours = parseField(f[1], 0, 23) ?: return null
                val doms = parseField(f[2], 1, 31) ?: return null
                val months = parseField(f[3], 1, 12) ?: return null
                val dows = parseField(f[4], 0, 7) ?: return null
                if (minutes.isEmpty() || hours.isEmpty() || doms.isEmpty() ||
                    months.isEmpty() || dows.isEmpty()
                ) {
                    return null
                }
                val dowNorm = if (7 in dows) {
                    (dows.toList() - 7 + 0).distinct().toIntArray()
                } else dows
                return CronExpr(
                    minutes, hours, doms, months, dowNorm,
                    domRestricted = f[2].trim() != "*",
                    dowRestricted = f[4].trim() != "*",
                )
            }
        }
    }

    fun isValid(expr: String): Boolean = CronExpr.parse(expr) != null

    /** cron 的下次触发（严格晚于 from），逐分钟探测，上限 ~366 天。 */
    fun nextAfter(expr: String, from: Long): Long? {
        val cron = CronExpr.parse(expr) ?: return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, 1)
        val deadline = from + 366L * 24 * 3600 * 1000
        while (cal.timeInMillis <= deadline) {
            if (cron.matches(cal)) return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    // ── ScheduleRule nextRunAt（上游 computeScheduleRuleNextRunAt 移植）─────

    fun ruleNextAfter(rule: ScheduleRule, from: Long): Long? {
        val interval = rule.interval.coerceAtLeast(1)
        val cal = Calendar.getInstance()

        when (rule.unit) {
            IntervalUnit.MINUTE -> {
                val step = interval * 60_000L
                val steps = ((from - rule.anchorAt) / step) + 1
                return rule.anchorAt + steps.coerceAtLeast(1) * step
            }
            IntervalUnit.HOURLY -> {
                cal.timeInMillis = rule.anchorAt
                cal.set(Calendar.MINUTE, rule.minute.coerceIn(0, 59))
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val step = interval * 3_600_000L
                val steps = ((from - cal.timeInMillis) / step) + 1
                return cal.timeInMillis + steps.coerceAtLeast(0) * step
            }
            IntervalUnit.DAILY -> {
                cal.timeInMillis = rule.anchorAt
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH)
                val d = cal.get(Calendar.DAY_OF_MONTH)
                for (i in 0..36_600) {
                    cal.set(y, m, d + i * interval, rule.hour, rule.minute, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    if (cal.timeInMillis > from) return cal.timeInMillis
                }
                return null
            }
            IntervalUnit.WEEKLY -> {
                cal.timeInMillis = rule.anchorAt
                cal.set(Calendar.HOUR_OF_DAY, rule.hour)
                cal.set(Calendar.MINUTE, rule.minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                for (w in 0..5_220 step interval) {
                    val cand = cal.clone() as Calendar
                    cand.add(Calendar.DAY_OF_YEAR, w * 7)
                    if (cand.timeInMillis > from) return cand.timeInMillis
                }
                return null
            }
            IntervalUnit.MONTHLY -> {
                cal.timeInMillis = rule.anchorAt
                val anchorDay = cal.get(Calendar.DAY_OF_MONTH)
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH)
                for (i in 0..1_200) {
                    cal.set(y, m + i * interval, 1, rule.hour, rule.minute, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(maxDay))
                    if (cal.timeInMillis > from) return cal.timeInMillis
                }
                return null
            }
            IntervalUnit.YEARLY -> {
                cal.timeInMillis = rule.anchorAt
                val month = cal.get(Calendar.MONTH)
                val day = cal.get(Calendar.DAY_OF_MONTH)
                val y = cal.get(Calendar.YEAR)
                for (i in 0..200) {
                    cal.set(y + i * interval, month, 1, rule.hour, rule.minute, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(maxDay))
                    if (cal.timeInMillis > from) return cal.timeInMillis
                }
                return null
            }
        }
    }

    /** 一次性固定日历 cron（minute hour dom month 全数字、dow=*）。 */
    private val FIXED_CALENDAR = Regex("^\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\*$")

    fun isFixedCalendarCron(expr: String): Boolean = FIXED_CALENDAR.matches(expr.trim())

    /**
     * 新建任务首次触发：非循环固定日历 cron 若目标刚过去会滚到明年——
     * 30 分钟窗口内视为模型自算误差（上游 StaleOneShotAutomationScheduleError），
     * <1 分钟立即补跑，否则返回 null 让上层拒绝。
     */
    fun initialNextRunAt(
        cronExpr: String,
        rule: ScheduleRule?,
        recurring: Boolean,
        now: Long,
    ): Pair<Long?, Boolean> {
        val next = if (rule != null) ruleNextAfter(rule, now) else nextAfter(cronExpr, now)
        if (recurring || rule != null || !isFixedCalendarCron(cronExpr)) return next to false
        // find previous occurrence by probing backwards
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val cron = CronExpr.parse(cronExpr) ?: return next to false
        var prev: Long? = null
        for (i in 0..30) {
            if (cron.matches(cal)) {
                prev = cal.timeInMillis
                break
            }
            cal.add(Calendar.MINUTE, -1)
        }
        val stale = prev != null && now - prev <= 30 * 60_000L &&
            (next == null || next - now > 30 * 60_000L)
        if (!stale) return next to false
        return if (now - prev!! < 60_000L) now to false else null to true
    }

    // ── 输入校验 + 规范化（上游 CronCreate schema 语义）───────

    data class Spec(
        val title: String,
        val prompt: String,
        val cronExpr: String,
        val intervalUnit: String = "",
        val interval: Int = 0,
        val anchorAt: Long = 0L,
        val recurring: Boolean,
        val maxRuns: Int,
        val nextRunAt: Long,
    )

    /**
     * 归一化 CronCreate 输入：delayMinutes → 分钟锚定规则；intervalUnit+interval →
     * 间隔规则（cron 仅作日历槽位）；否则纯 cron。返回错误串或 Spec。
     */
    fun buildSpec(
        title: String,
        prompt: String,
        cron: String?,
        delayMinutes: Int?,
        recurring: Boolean,
        maxRuns: Int?,
        intervalUnit: String?,
        interval: Int?,
        now: Long,
    ): Pair<Spec?, String?> {
        if (title.isBlank()) return null to "title is required"
        if (prompt.isBlank()) return null to "prompt is required"
        if (maxRuns != null && maxRuns <= 0) return null to "maxRuns must be >= 1"
        if (recurring && maxRuns != null) {
            return null to "recurring=true cannot be combined with maxRuns"
        }

        var rule: ScheduleRule? = null
        var cronExpr = cron?.trim().orEmpty()

        val delay = delayMinutes?.takeIf { it > 0 }
        if (delay != null) {
            val target = java.util.Calendar.getInstance().apply { timeInMillis = now + delay * 60_000L }
            cronExpr = "${target.get(java.util.Calendar.MINUTE)} " +
                "${target.get(java.util.Calendar.HOUR_OF_DAY)} " +
                "${target.get(java.util.Calendar.DAY_OF_MONTH)} " +
                "${target.get(java.util.Calendar.MONTH) + 1} *"
            rule = ScheduleRule(
                unit = IntervalUnit.MINUTE,
                interval = delay,
                hour = target.get(java.util.Calendar.HOUR_OF_DAY),
                minute = target.get(java.util.Calendar.MINUTE),
                anchorAt = now,
            )
        } else if (!intervalUnit.isNullOrBlank() || interval != null) {
            val unit = runCatching {
                IntervalUnit.valueOf(intervalUnit.orEmpty().trim().uppercase())
            }.getOrNull() ?: return null to "intervalUnit must be minute|hourly|daily|weekly|monthly|yearly"
            val n = interval ?: return null to "interval is required with intervalUnit"
            if (n !in 1..200) return null to "interval must be 1..200"
            if (cronExpr.isBlank() || !isValid(cronExpr)) {
                return null to "interval schedules still need a legal 5-field cron for the calendar slot"
            }
            val (h, m) = cronClockFields(cronExpr)
            rule = ScheduleRule(
                unit = unit,
                interval = n,
                hour = h,
                minute = m,
                anchorAt = now,
            )
        } else {
            if (cronExpr.isBlank()) return null to "cron or delayMinutes is required"
            if (!isValid(cronExpr)) return null to "invalid 5-field cron expression"
        }

        val effMaxRuns = when {
            recurring -> 0
            else -> maxRuns ?: 1
        }
        val (next, stale) = initialNextRunAt(cronExpr, rule, recurring, now)
        if (stale) {
            return null to "一次性定时任务的目标时间已过去；相对时间请用 delayMinutes，绝对时间请确认未来时刻后重试"
        }
        if (next == null) return null to "schedule produces no future run"
        return Spec(
            title = title.trim(),
            prompt = prompt,
            cronExpr = cronExpr,
            intervalUnit = rule?.unit?.name.orEmpty(),
            interval = rule?.interval ?: 0,
            anchorAt = rule?.anchorAt ?: 0L,
            recurring = recurring,
            maxRuns = effMaxRuns,
            nextRunAt = next,
        ) to null
    }

    /** carrier cron 的 (hour, minute) 数字槽位；`*` 时回退 0。 */
    private fun cronClockFields(cronExpr: String): Pair<Int, Int> {
        val f = cronExpr.trim().split(Regex("\\s+"))
        val m = f.getOrNull(0)?.toIntOrNull() ?: 0
        val h = f.getOrNull(1)?.toIntOrNull() ?: 0
        return h to m
    }

    /** 触发后推进：runCount+1、到达上限 → completed；否则重算 nextRunAt。 */
    fun advanceAfterRun(
        cronExpr: String,
        intervalUnit: String,
        interval: Int,
        anchorAt: Long,
        recurring: Boolean,
        maxRuns: Int,
        runCount: Int,
        now: Long,
    ): Triple<Int, Long, String> {
        val newCount = runCount + 1
        val done = !recurring && maxRuns > 0 && newCount >= maxRuns
        if (done) return Triple(newCount, 0L, "completed")
        val next = if (intervalUnit.isNotBlank() && interval > 0) {
            runCatching { IntervalUnit.valueOf(intervalUnit) }.getOrNull()?.let {
                val (h, m) = cronClockFields(cronExpr)
                ruleNextAfter(ScheduleRule(it, interval, hour = h, minute = m, anchorAt = anchorAt), now)
            }
        } else {
            nextAfter(cronExpr, now)
        }
        return Triple(newCount, next ?: 0L, if (next == null) "completed" else "active")
    }
}
