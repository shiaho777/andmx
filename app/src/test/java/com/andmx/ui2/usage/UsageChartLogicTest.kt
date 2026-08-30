package com.andmx.ui2.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageChartLogicTest {

    // ── heat level: clamp(ceil(tokens/max*4), 1, 4), 0 when no usage ──

    @Test
    fun heatLevel_zeroTokensIsZero() {
        assertEquals(0, UsageChartLogic.heatLevel(0L, 100L))
        assertEquals(0, UsageChartLogic.heatLevel(-5L, 100L))
    }

    @Test
    fun heatLevel_zeroMaxIsZero() {
        assertEquals(0, UsageChartLogic.heatLevel(10L, 0L))
    }

    @Test
    fun heatLevel_ceilBucketsMatchZcode() {
        // ceil(v/max*4): 1% of max → 1; 26% → 2; 51% → 3; 76% → 4
        assertEquals(1, UsageChartLogic.heatLevel(1L, 100L))
        assertEquals(2, UsageChartLogic.heatLevel(26L, 100L))
        assertEquals(3, UsageChartLogic.heatLevel(51L, 100L))
        assertEquals(4, UsageChartLogic.heatLevel(76L, 100L))
        assertEquals(4, UsageChartLogic.heatLevel(100L, 100L))
        assertEquals(4, UsageChartLogic.heatLevel(500L, 100L))
    }

    // ── tick visibility: first/last always, step 5 (7 beyond 45), all when ≤14 ──

    @Test
    fun showTick_smallRangeShowsAll() {
        assertTrue(UsageChartLogic.showTick(3, 10))
        assertTrue(UsageChartLogic.showTick(7, 14))
    }

    @Test
    fun showTick_stepFiveMidRange() {
        assertTrue(UsageChartLogic.showTick(0, 30))
        assertTrue(UsageChartLogic.showTick(29, 30))
        assertTrue(UsageChartLogic.showTick(5, 30))
        assertTrue(UsageChartLogic.showTick(25, 30))
        assertFalse(UsageChartLogic.showTick(3, 30))
        assertFalse(UsageChartLogic.showTick(7, 30))
    }

    @Test
    fun showTick_stepSevenLongRange() {
        assertTrue(UsageChartLogic.showTick(0, 60))
        assertTrue(UsageChartLogic.showTick(59, 60))
        assertTrue(UsageChartLogic.showTick(7, 60))
        assertTrue(UsageChartLogic.showTick(49, 60))
        assertFalse(UsageChartLogic.showTick(5, 60))
    }

    // ── monotone interpolation: d3 curveMonotoneX port ──

    @Test
    fun monotoneTangents_zeroAtLocalExtrema() {
        val m = UsageChartLogic.monotoneTangents(listOf(1f, 3f, 1f))
        assertEquals(0f, m[1], 1e-6f)
    }

    @Test
    fun monotoneTangents_twoPointsShareSlope() {
        val m = UsageChartLogic.monotoneTangents(listOf(0f, 4f))
        assertEquals(m[0], m[1], 1e-6f)
        assertEquals(4f, m[0], 1e-6f)
    }

    @Test
    fun monotonePoint_atEndpointsMatchesData() {
        val p0 = UsageChartLogic.Point(0f, 0f)
        val p1 = UsageChartLogic.Point(10f, 20f)
        val start = UsageChartLogic.monotonePoint(p0, p1, 5f, 5f, 0f)
        val end = UsageChartLogic.monotonePoint(p0, p1, 5f, 5f, 1f)
        assertEquals(p0.x, start.x, 1e-4f)
        assertEquals(p0.y, start.y, 1e-4f)
        assertEquals(p1.x, end.x, 1e-4f)
        assertEquals(p1.y, end.y, 1e-4f)
    }

    @Test
    fun monotonePoint_xIsLinearInT() {
        val p0 = UsageChartLogic.Point(0f, 0f)
        val p1 = UsageChartLogic.Point(10f, 20f)
        val mid = UsageChartLogic.monotonePoint(p0, p1, 8f, -2f, 0.5f)
        assertEquals(5f, mid.x, 1e-4f)
    }

    @Test
    fun seriesPoints_endpointsOnDataAndBounded() {
        val values = listOf(0L, 40L, 10L, 100L, 0L)
        val pts = UsageChartLogic.seriesPoints(values, 100f, 400f, 200f)
        // 3 bezier control points per segment + final data point
        assertEquals((values.size - 1) * 3 + 1, pts.size)
        assertEquals(0f, pts.first().y, 1e-3f)
        assertEquals(0f, pts.last().y, 1e-3f)
        // data point index 3 (value=100) sits at sample 3*3 of the polyline
        assertEquals(200f, pts[9].y, 1e-3f)
        // curveMonotoneX never overshoots the data range in y
        assertTrue(pts.all { it.y >= -1e-3f && it.y <= 200f + 1e-3f })
        // x only moves forward and stays inside the plot
        assertTrue(pts.all { it.x >= -1e-3f && it.x <= 400f + 1e-3f })
        assertEquals(0f, pts.first().x, 1e-3f)
        assertEquals(400f, pts.last().x, 1e-3f)
    }

    @Test
    fun seriesPoints_twoValuesIsStraightLine() {
        val pts = UsageChartLogic.seriesPoints(listOf(0L, 100L), 100f, 100f, 50f)
        assertEquals(listOf(0f, 100f), pts.map { it.x })
        assertEquals(listOf(0f, 50f), pts.map { it.y })
    }

    // ── heat modes: daily / weekly / cumulative (ZCode bzt) ──

    private fun modeGrid(): UsageChartLogic.HeatGrid =
        UsageChartLogic.heatGrid(
            mapOf(
                "2026-08-23" to 100L,
                "2026-08-24" to 20L,
                "2026-08-29" to 80L,
            ),
            100L,
        )

    @Test
    fun applyHeatMode_dailyIsPassThrough() {
        val grid = modeGrid()
        assertEquals(grid, UsageChartLogic.applyHeatMode(grid, UsageChartLogic.HeatMode.DAILY))
    }

    @Test
    fun applyHeatMode_weeklyFillsBottomWithWeekSum() {
        val grid = UsageChartLogic.applyHeatMode(modeGrid(), UsageChartLogic.HeatMode.WEEKLY)
        val last = grid.columns.last()
        // last week sums to 200 → fill = ceil(200/200*7)=7, level = 4
        assertEquals(7, last.levels.count { it > 0 })
        assertEquals(4, last.levels[0])
        // an empty week paints nothing
        assertTrue(grid.columns.first().levels.all { it == 0 })
    }

    @Test
    fun applyHeatMode_cumulativeIsRunningSum() {
        val sums = modeGrid().columns.map { it.tokens.sum() }
        val grid = UsageChartLogic.applyHeatMode(modeGrid(), UsageChartLogic.HeatMode.CUMULATIVE)
        grid.columns.forEachIndexed { i, col ->
            if (sums[i] > 0) {
                // painted value on the filled cells equals the running sum
                val painted = col.tokens.filter { it > 0 }.distinct()
                val run = sums.take(i + 1).sum()
                assertEquals(listOf(run), painted)
            }
        }
        // monotone non-decreasing painted mass, final column fully filled
        val last = grid.columns.last()
        assertEquals(7, last.levels.count { it > 0 })
    }

    @Test
    fun heatFill_matchesZcodeCeil7() {
        assertEquals(0, UsageChartLogic.heatFill(0L, 100L))
        assertEquals(1, UsageChartLogic.heatFill(1L, 100L))
        assertEquals(4, UsageChartLogic.heatFill(50L, 100L))
        assertEquals(7, UsageChartLogic.heatFill(100L, 100L))
        assertEquals(7, UsageChartLogic.heatFill(500L, 100L))
    }

    // ── 52×7 heat grid ──

    @Test
    fun heatGrid_fixedFiftyTwoColumnsOfSeven() {
        val grid = UsageChartLogic.heatGrid(mapOf("2026-08-29" to 10L), 10L)
        assertEquals(52, grid.columns.size)
        grid.columns.forEach { col ->
            assertEquals(7, col.dates.size)
            assertEquals(7, col.levels.size)
        }
        // rows are Sunday-first; 2026-08-29 is a Saturday, so the last row
        // starts on 2026-08-23 (Sunday) and its last filled cell is Saturday
        assertEquals("2026-08-23", grid.columns.last().dates.first())
        assertEquals("2026-08-29", grid.columns.last().dates.last())
        assertEquals(4, grid.columns.last().levels.last())
    }

    @Test
    fun heatGrid_emptyDataEndsToday() {
        val grid = UsageChartLogic.heatGrid(emptyMap(), 0L, nowUtc = 1_787_865_600_000L)
        assertEquals(52, grid.columns.size)
        assertTrue(grid.columns.last().levels.all { it == 0 })
    }

    @Test
    fun heatGrid_monthLabelsMergeAndBlankOldOnes() {
        val grid = UsageChartLogic.heatGrid(mapOf("2026-08-29" to 10L), 10L)
        val labeled = grid.monthLabels.filter { it.label.isNotEmpty() }
        assertTrue(labeled.size <= 12)
        assertEquals(grid.monthLabels.sumOf { it.span }, 52)
        // blanking applies only to the oldest prefix: empty head, non-empty tail
        val firstFilled = grid.monthLabels.indexOfFirst { it.label.isNotEmpty() }
        assertTrue(firstFilled == -1 || grid.monthLabels.take(firstFilled).all { it.label.isEmpty() })
    }

    @Test
    fun monthShort_zhAndUs() {
        assertEquals("8月", UsageChartLogic.monthShort("2026-08", zh = true))
        assertEquals("Jan", UsageChartLogic.monthShort("2026-01", zh = false))
    }

    // ── formatting ──

    @Test
    fun formatDuration_matchesZcodeShape() {
        // 15h13m
        assertEquals("15 小时 13 分钟", UsageChartLogic.formatDuration((15 * 60 + 13) * 60_000L, "天", "小时", "分钟"))
        // 2d 3h 0m → zero minutes dropped
        assertEquals("2 天 3 小时", UsageChartLogic.formatDuration((2 * 1440 + 3 * 60) * 60_000L, "天", "小时", "分钟"))
        assertEquals("0 分钟", UsageChartLogic.formatDuration(0L, "天", "小时", "分钟"))
    }

    @Test
    fun formatCompact_units() {
        assertEquals("92.5k", UsageChartLogic.formatCompact(92_500L))
        assertEquals("925k", UsageChartLogic.formatCompact(925_000L))
        assertEquals("9.2亿", UsageChartLogic.formatCompact(925_000_000L))
        assertEquals("0", UsageChartLogic.formatCompact(0L))
    }

    @Test
    fun streaks_currentAndLongest() {
        val day = 86_400_000L
        val today = 1_000_000L * day
        val days = listOf(today - 2 * day, today - day, today, today - 5 * day)
        val (current, longest) = UsageChartLogic.streaks(days, today)
        assertEquals(3, current)
        assertEquals(3, longest)
    }

    @Test
    fun streaks_emptyInput() {
        assertEquals(0 to 0, UsageChartLogic.streaks(emptyList(), 0L))
    }
}
