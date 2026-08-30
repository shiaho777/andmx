package com.andmx.ui2.usage

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sign

/**
 * Chart logic aligned with ZCode desktop's usage stats renderer
 * (`AppUsageDailyModelTrendChart` + heatmap grid in the settings bundle).
 *
 * Key behaviors ported verbatim from the reversed implementation:
 * - Heat level: `level = clamp(ceil(tokens / max * 4), 1, 4)`, 0 when tokens <= 0.
 * - Weekly/cumulative heatmap modes: per-week sums or running prefix sums,
 *   then `fill = clamp(ceil(v / max * 7), 1, 7)` cells painted from the
 *   bottom of each column with one shared level.
 * - Trend chart keeps only the top 6 models, zero-fills every day of the
 *   range, and draws one d3 `curveMonotoneX` polyline per model
 *   (linear x parametrization, slope3/slope2 tangents, strokeWidth 2).
 * - X tick: always show first and last, then every 5th point (every 7th beyond 45).
 * - Month labels under the heatmap take the week's contained first-of-month
 *   (`vzt`), merge consecutive weeks in the same year-month, and all but
 *   the last 12 labels are blanked.
 */
object UsageChartLogic {

    const val WEEKS = 52
    const val DAYS_PER_WEEK = 7
    private const val DAY_MS = 86_400_000L
    private const val MAX_MONTH_LABELS = 12

    /** 5 levels, matches `--color-usage-heatmap-0..4` consumers in ZCode. */
    fun heatLevel(tokens: Long, maxTokens: Long): Int {
        if (tokens <= 0L || maxTokens <= 0L) return 0
        return (ceil(tokens.toDouble() / maxTokens.toDouble() * 4.0)).toInt()
            .coerceIn(1, 4)
    }

    /** Heatmap view mode, ZCode `ozt = ['daily','weekly','cumulative']`. */
    enum class HeatMode { DAILY, WEEKLY, CUMULATIVE }

    /**
     * ZCode `bzt`: for non-daily modes each column shows the week total
     * (weekly) or the running prefix sum (cumulative), scaled by
     * `fill = clamp(ceil(v / max * 7), 1, 7)` cells painted from the bottom
     * row upward, all sharing one heat level. Daily mode is a pass-through.
     */
    fun applyHeatMode(grid: HeatGrid, mode: HeatMode): HeatGrid {
        if (mode == HeatMode.DAILY) return grid
        val sums = grid.columns.map { col -> col.tokens.sum() }
        val values = if (mode == HeatMode.WEEKLY) {
            sums
        } else {
            val acc = ArrayList<Long>(sums.size)
            var run = 0L
            sums.forEach { run += it; acc.add(run) }
            acc
        }
        val max = values.maxOrNull()?.coerceAtLeast(0L) ?: 0L
        val columns = grid.columns.mapIndexed { w, col ->
            val v = values[w]
            val fill = heatFill(v, max)
            val level = heatLevel(v, max)
            val tokens = List(DAYS_PER_WEEK) { r -> if (r >= DAYS_PER_WEEK - fill) v else 0L }
            val levels = List(DAYS_PER_WEEK) { r -> if (r >= DAYS_PER_WEEK - fill) level else 0 }
            WeekColumn(w, col.dates, tokens, levels)
        }
        return HeatGrid(columns, grid.monthLabels)
    }

    /** ZCode `_zt`: fill count for the bottom-up weekly/cumulative painting. */
    fun heatFill(value: Long, maxValue: Long): Int {
        if (value <= 0L || maxValue <= 0L) return 0
        return (ceil(value.toDouble() / maxValue.toDouble() * DAYS_PER_WEEK))
            .toInt().coerceIn(1, DAYS_PER_WEEK)
    }

    /** 0..4 → mixing ratio of sky accent into the surface (18% steps, ZCode palette). */
    val heatLevelRatios = floatArrayOf(0.00f, 0.18f, 0.36f, 0.58f, 0.82f)

    /**
     * X-axis tick visibility for the daily trend chart
     * (ZCode `S(index, count)`).
     */
    fun showTick(index: Int, count: Int): Boolean {
        if (count <= 14) return true
        val step = if (count > 45) 7 else 5
        return index == 0 || index == count - 1 || index % step == 0
    }

    /**
     * d3-shape `curveMonotoneX` tangents (Fritsch–Carlson style secant
     * averaging on x-spacing). Kept public for tests.
     */
    fun monotoneTangents(values: List<Float>): FloatArray {
        val n = values.size
        if (n < 2) return FloatArray(n)
        val delta = FloatArray(n - 1) { values[it + 1] - values[it] }
        val m = FloatArray(n)
        if (n == 2) {
            m[0] = delta[0]
            m[1] = delta[0]
            return m
        }
        m[0] = delta[0]
        m[n - 1] = delta[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (delta[i - 1] * delta[i] <= 0f) 0f else (delta[i - 1] + delta[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (delta[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                val a = m[i] / delta[i]
                val b = m[i + 1] / delta[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / kotlin.math.sqrt(s)
                    m[i] = t * a * delta[i]
                    m[i + 1] = t * b * delta[i]
                }
            }
        }
        return m
    }

    /** One point on the chart plane; y grows upward, in token units. */
    data class Point(val x: Float, val y: Float)

    /**
     * Sample a monotone cubic segment between p0 and p1 (Hermite form).
     */
    fun monotonePoint(
        p0: Point,
        p1: Point,
        m0: Float,
        m1: Float,
        t: Float,
    ): Point {
        val h = p1.x - p0.x
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        val y = h00 * p0.y + h10 * h * m0 + h01 * p1.y + h11 * h * m1
        return Point(p0.x + (p1.x - p0.x) * t, y)
    }

    /** d3-shape `slope3`: sign-compatible tangent bound at point 1. */
    private fun slope3(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
    ): Float {
        val h0 = x1 - x0
        val h1 = x2 - x1
        val s0 = if (h0 == 0f) (if (h1 < 0f) -0f else 0f) else (y1 - y0) / h0
        val s1 = if (h1 == 0f) (if (h0 < 0f) -0f else 0f) else (y2 - y1) / h1
        val p = (s0 * h1 + s1 * h0) / (h0 + h1)
        val s = (sign(s0) + sign(s1)) *
            minOf(abs(s0), abs(s1), 0.5f * abs(p))
        return if (s == 0f) 0f else s
    }

    /** d3-shape `slope2`: one-sided slope at the endpoints. */
    private fun slope2(x0: Float, y0: Float, x1: Float, y1: Float, t: Float): Float {
        val h = x1 - x0
        return if (h == 0f) t else (3f * (y1 - y0) / h - t) / 2f
    }

    /** d3-shape `point`: one cubic segment with control points 1/3 in x. */
    private fun bezierSegment(
        p0: Point, p1: Point, t0: Float, t1: Float,
        onPoint: (Float, Float) -> Unit,
    ) {
        val dx = (p1.x - p0.x) / 3f
        onPoint(p0.x + dx, p0.y + dx * t0)
        onPoint(p1.x - dx, p1.y - dx * t1)
        onPoint(p1.x, p1.y)
    }

    /**
     * Full d3 `curveMonotoneX` polyline over data points on the chart plane.
     * Segment p_k→p_{k+1} uses tangent slope3 at interior ends and the
     * one-sided slope2 at the two series endpoints; 2-point input is a
     * straight line (d3 `lineEnd` case 2).
     */
    fun monotonePolyline(points: List<Point>): List<Point> {
        val n = points.size
        if (n < 2) return points.toList()
        if (n == 2) return listOf(points[0], points[1])
        val out = mutableListOf(points[0])
        for (k in 0 until n - 1) {
            val p0 = points[k]
            val p1 = points[k + 1]
            val t0: Float
            val t1: Float
            if (k == 0) {
                val s1 = slope3(points[0].x, points[0].y, points[1].x, points[1].y, points[2].x, points[2].y)
                t0 = slope2(p0.x, p0.y, p1.x, p1.y, s1)
                t1 = s1
            } else if (k == n - 2) {
                val s0 = slope3(points[k - 1].x, points[k - 1].y, p0.x, p0.y, p1.x, p1.y)
                t0 = s0
                t1 = slope2(p0.x, p0.y, p1.x, p1.y, s0)
            } else {
                t0 = slope3(points[k - 1].x, points[k - 1].y, p0.x, p0.y, p1.x, p1.y)
                t1 = slope3(p0.x, p0.y, p1.x, p1.y, points[k + 2].x, points[k + 2].y)
            }
            bezierSegment(p0, p1, t0, t1) { x, y -> out.add(Point(x, y)) }
        }
        return out
    }

    /**
     * Build the d3 `curveMonotoneX` polyline for one model series with
     * y = h * v / max mapping (uniform data x spacing is assumed).
     */
    fun seriesPoints(
        values: List<Long>,
        maxTokens: Float,
        width: Float,
        height: Float,
    ): List<Point> {
        if (values.isEmpty() || maxTokens <= 0f) return emptyList()
        val stepX = if (values.size == 1) 0f else width / (values.size - 1)
        val pts = values.mapIndexed { i, v ->
            Point(i * stepX, height * (v.toFloat() / maxTokens))
        }
        return monotonePolyline(pts)
    }

    /** One column (week) of 7 heatmap cells, aligned Sunday-first like the grid. */
    data class WeekColumn(
        val weekIndex: Int,
        /** UTC yyyy-MM-dd for each of the 7 cells, Sunday first. */
        val dates: List<String>,
        val tokens: List<Long>,
        val levels: List<Int>,
    )

    data class HeatGrid(
        val columns: List<WeekColumn>,
        /** `yyyy-MM` → span; all but the last 12 entries have an empty label. */
        val monthLabels: List<MonthLabel>,
    )

    data class MonthLabel(val key: String, val label: String, val span: Int)

    /**
     * Rebuild a fixed 52-week × 7-day grid aligned Sunday-first (row 0 =
     * Sunday, like ZCode's `getUTCDay` alignment): the last row ends exactly
     * on the latest day with usage.
     */
    fun heatGrid(
        dayTokens: Map<String, Long>,
        maxTokens: Long,
        nowUtc: Long = System.currentTimeMillis(),
    ): HeatGrid {
        val lastWithUsage = dayTokens.entries
            .filter { it.value > 0L }
            .maxByOrNull { it.key }
            ?.key
            ?: todayUtc(nowUtc)
        val lastDayIndex = utcDayIndex(lastWithUsage)
        val firstDayIndex = lastDayIndex - sundayOffset(lastWithUsage) - (WEEKS - 1) * DAYS_PER_WEEK
        val columns = (0 until WEEKS).map { w ->
            val dates = (0 until DAYS_PER_WEEK).map { d -> utcDate(firstDayIndex + w * DAYS_PER_WEEK + d) }
            val tokens = dates.map { dayTokens[it] ?: 0L }
            val levels = tokens.map { heatLevel(it, maxTokens) }
            WeekColumn(w, dates, tokens, levels)
        }
        return HeatGrid(columns, monthLabels(columns))
    }

    /** Days back to the row-start Sunday (ZCode `fzt` = getUTCDay, 0=Sunday). */
    private fun sundayOffset(date: String): Long = (utcDayIndex(date) + 4) % 7

    private fun monthLabels(columns: List<WeekColumn>): List<MonthLabel> {
        val merged = mutableListOf<MonthLabel>()
        for (col in columns) {
            // vzt: prefer the week that contains a first-of-month, else first day
            val monthDate = col.dates.firstOrNull { it.endsWith("-01") } ?: col.dates.first()
            val key = monthDate.take(7)
            val last = merged.lastOrNull()
            if (last != null && last.key == key) {
                merged[merged.lastIndex] = last.copy(span = last.span + 1)
            } else {
                merged.add(MonthLabel(key, monthShort(key), 1))
            }
        }
        val blankCount = (merged.size - MAX_MONTH_LABELS).coerceAtLeast(0)
        return merged.mapIndexed { i, l ->
            if (i < blankCount) l.copy(label = "") else l
        }
    }

    /** `yyyy-MM` → `M月` / `Sep`, UTC — same shape as ZCode's month row. */
    fun monthShort(yearMonth: String, zh: Boolean = Locale.getDefault().language == "zh"): String {
        val month = yearMonth.getOrNull(5)?.digitToIntOrNull()?.times(10)
            ?.plus((yearMonth.getOrNull(6)?.digitToIntOrNull() ?: 0)) ?: return ""
        return if (zh) "${month}月"
        else Locale.US.displayName.let {
            java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
        }
    }

    /** Days since 1970-01-01 (Hinnant `days_from_civil`, proleptic Gregorian). */
    private fun utcDayIndex(date: String): Long {
        var y = date.substring(0, 4).toInt()
        val m = date.substring(5, 7).toInt()
        val d = date.substring(8, 10).toInt()
        y -= if (m <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    /** 0=Monday..6=Sunday for a UTC `yyyy-MM-dd` date. */
    private fun utcDayOfWeek(date: String): Int =
        (((utcDayIndex(date) + 3) % 7) + 7).toInt() % 7

    private fun utcDate(dayIndex: Long): String {
        // inverse of utcDayIndex (Hinnant civil_from_days), timezone-free
        val z = dayIndex + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = (y + (if (m <= 2) 1 else 0)).toInt()
        return "%04d-%02d-%02d".format(Locale.US, year, m.toInt(), d.toInt())
    }

    private fun todayUtc(nowMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        return "%04d-%02d-%02d".format(
            Locale.US,
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** UTC `yyyy-MM-dd` key for an epoch-milli timestamp. */
    fun utcDayKey(ts: Long): String = utcDate(floor(ts / DAY_MS.toDouble()).toLong())

    /**
     * Chart palette order (ZCode `--color-usage-chart-1..6`):
     * sky-600, teal-600, violet-600, rose-500, indigo-500, cyan-600.
     * The caller supplies concrete Color values; this only fixes the order.
     */
    const val CHART_SERIES = 6

    /** Log-ish compression is NOT applied by ZCode; kept for reference only. */
    fun linearFraction(value: Long, max: Long): Float =
        if (max <= 0L) 0f else (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)

    /**
     * Duration formatting (ZCode `Nzt`): days/hours/minutes, dropping zero
     * leading units, always at least minutes.
     */
    fun formatDuration(ms: Long, dayLabel: String, hourLabel: String, minuteLabel: String): String {
        val totalMinutes = (ms.coerceAtLeast(0L) / 60_000L).toInt()
        val days = totalMinutes / (24 * 60)
        val hours = totalMinutes % (24 * 60) / 60
        val minutes = totalMinutes % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts.add("$days $dayLabel")
        if (hours > 0) parts.add("$hours $hourLabel")
        if (minutes > 0 || parts.isEmpty()) parts.add("$minutes $minuteLabel")
        return parts.joinToString(" ")
    }

    /**
     * Compact number formatting matching `Intl.NumberFormat(compact)` used by
     * ZCode for the summary cards (e.g. 92.5亿, 8.1亿, 1.2万).
     */
    fun formatCompact(n: Long): String {
        if (n < 0) return "0"
        val abs = n.toDouble()
        return when {
            abs >= 1e8 -> trim(n / 1e8) + "亿"
            abs >= 1e3 -> trim(n / 1e3) + "k"
            else -> n.toString()
        }
    }

    private fun trim(v: Double): String {
        val rounded = (v * 10).let { if (it - floor(it) >= 0.95) (it + 1).toLong() else it.toLong() }
        val oneDecimal = (rounded / 10.0)
        return if (oneDecimal == floor(oneDecimal)) oneDecimal.toLong().toString() else "%.1f".format(oneDecimal)
    }

    /**
     * Day-of-week adjust so weeks start on Monday (0=Mon..6=Sun), matching
     * the grid layout whose last row is Sunday.
     */
    fun mondayIndex(utcDayOfWeekSundayFirst: Int): Int = (utcDayOfWeekSundayFirst + 6) % 7

    /**
     * Longest consecutive-usage streak and the current streak ending today or
     * yesterday, computed on local day starts (ZCode summary semantics).
     */
    fun streaks(activeDayStarts: List<Long>, todayStart: Long): Pair<Int, Int> {
        if (activeDayStarts.isEmpty()) return 0 to 0
        val sorted = activeDayStarts.distinct().sorted()
        val oneDay = DAY_MS
        var longest = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] - sorted[i - 1] == oneDay) run + 1 else 1
            if (run > longest) longest = run
        }
        var current = 0
        if (sorted.last() == todayStart || sorted.last() == todayStart - oneDay) {
            current = 1
            for (i in sorted.size - 1 downTo 1) {
                if (sorted[i] - sorted[i - 1] == oneDay) current++ else break
            }
        }
        return current to longest
    }

    /** Natural-log helper shared by any future intensity scaling. */
    fun logScale(v: Float): Float = if (v <= 0f) 0f else kotlin.math.ln(v.toDouble()).toFloat()
}
