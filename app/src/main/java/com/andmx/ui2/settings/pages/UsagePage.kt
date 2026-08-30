package com.andmx.ui2.settings.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Token
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.ui2.settings.backAppBar
import com.andmx.ui2.usage.DayBucket
import com.andmx.ui2.usage.ModelUsage
import com.andmx.ui2.usage.UsageCalculator
import com.andmx.ui2.usage.UsageChartLogic
import com.andmx.ui2.usage.UsageRange
import com.andmx.ui2.usage.UsageStats
import java.util.Calendar
import kotlin.math.max

/**
 * Palette order mirrors ZCode `--color-usage-chart-1..6`
 * (sky-600, teal-600, violet-600, rose-500, indigo-500, cyan-600).
 */
private val ModelPalette = listOf(
    Color(0xFF0084CC),
    Color(0xFF0D9488),
    Color(0xFF7C3AED),
    Color(0xFFF43F5E),
    Color(0xFF6366F1),
    Color(0xFF0891B2),
)

private val CardBg = Color(0xFF1C1C1C)
private val CardBgAlt = Color(0xFF202020)
private val ChipBg = Color(0xFF2A2A2A)
private val ChipSelectedBg = Color(0xFF3A3A3A)
private val Muted = Color(0xFF9A9A9A)
private val Subtle = Color(0xFF6E6E6E)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UsagePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var range by remember { mutableStateOf(UsageRange.D30) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val stats by produceState(
        initialValue = UsageStats(),
        key1 = range,
        key2 = refreshKey,
    ) {
        value = runCatching { UsageCalculator.compute(context, range) }
            .getOrDefault(UsageStats(loaded = true))
    }
    val loading = !stats.loaded
    val surface = MaterialTheme.colorScheme.surface
    val isDark = (0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue) < 0.5f
    val pageBg = if (isDark) Color(0xFF141414) else MaterialTheme.colorScheme.surface
    val cardBg = if (isDark) CardBg else MaterialTheme.colorScheme.surfaceContainerHigh
    val cardBgAlt = if (isDark) CardBgAlt else MaterialTheme.colorScheme.surfaceContainer
    val muted = if (isDark) Muted else MaterialTheme.colorScheme.onSurfaceVariant
    val subtle = if (isDark) Subtle else MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val chipBg = if (isDark) ChipBg else MaterialTheme.colorScheme.surfaceVariant
    val chipSelected = if (isDark) ChipSelectedBg else MaterialTheme.colorScheme.secondaryContainer

    Scaffold(
        containerColor = pageBg,
        contentColor = onSurface,
        topBar = { backAppBar("使用统计", onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "使用统计",
                style = MaterialTheme.typography.titleMedium,
                color = onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("应用用量", color = onSurface, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(chipBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("应用用量", color = muted, style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(14.dp))

            SummaryRow(
                stats = stats,
                muted = muted,
                onSurface = onSurface,
                divider = if (isDark) Color(0xFF2E2E2E) else MaterialTheme.colorScheme.outlineVariant,
            )

            Spacer(Modifier.height(16.dp))

            HeatmapCard(
                stats = stats,
                cardBg = cardBg,
                muted = muted,
                subtle = subtle,
                onSurface = onSurface,
            )

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("时间范围", color = onSurface, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageRange.entries.forEach { r ->
                        val selected = range == r
                        Text(
                            r.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) onSurface else muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) chipSelected else chipBg)
                                .clickable { range = r }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (loading) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("正在统计中", color = onSurface, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "正在读取本地应用会话历史，可能需要一点时间。",
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                    )
                }
                return@Column
            }

            if (stats.messages == 0 && stats.totalTokens == 0L) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .padding(vertical = 48.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有可展示的数据", color = onSurface, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "当前区间暂无可展示的用量数据。",
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                DailyTrendCard(
                    daily = stats.daily,
                    cardBg = cardBg,
                    muted = muted,
                    subtle = subtle,
                    onSurface = onSurface,
                )

                Spacer(Modifier.height(14.dp))

                ModelUsageCard(
                    stats = stats,
                    cardBg = cardBg,
                    cardInner = cardBgAlt,
                    muted = muted,
                    onSurface = onSurface,
                )
            }

            RefreshRow(onRefresh = { refreshKey++ }, muted = muted, onSurface = onSurface)
        }
    }
}

@Composable
private fun RefreshRow(onRefresh: () -> Unit, muted: Color, onSurface: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, muted.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .clickable(onClick = onRefresh)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
            Text("刷新", color = onSurface, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * One rounded card with 5 centered stats separated by hairlines, mirroring
 * ZCode's summary section (c7): 累计 Token 数 / 峰值 Token 数 / 最长聊天时长 /
 * 当前连续天数 / 最长连续天数.
 */
@Composable
private fun SummaryRow(
    stats: UsageStats,
    muted: Color,
    onSurface: Color,
    divider: Color,
) {
    val zhDays = "天"
    val items = listOf(
        SummaryItem(
            UsageCalculator.formatCount(stats.lifetimeTotalTokens),
            "累计 Token 数",
        ),
        SummaryItem(
            UsageCalculator.formatCount(stats.peakDayTokens),
            "峰值 Token 数",
        ),
        SummaryItem(
            UsageChartLogic.formatDuration(stats.longestSessionMs, zhDays, "小时", "分钟"),
            "最长聊天时长",
        ),
        SummaryItem("${stats.currentStreak} $zhDays", "当前连续天数"),
        SummaryItem("${stats.longestStreak} $zhDays", "最长连续天数"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (muted == Muted) CardBg else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(divider),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    item.value,
                    color = onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    color = muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class SummaryItem(val value: String, val label: String)

/**
 * 52-week × 7-day activity heatmap with a month label row underneath,
 * matching ZCode's grid (`i7=52`, gap-0.5, 4px corner radius, hover scale).
 */
@Composable
private fun HeatmapCard(
    stats: UsageStats,
    cardBg: Color,
    muted: Color,
    subtle: Color,
    onSurface: Color,
) {
    val isDark = (0.299f * onSurface.red + 0.587f * onSurface.green + 0.114f * onSurface.blue) > 0.5f
    var mode by remember { mutableStateOf(UsageChartLogic.HeatMode.DAILY) }
    val baseGrid = remember(stats.heatmapDayTokens) {
        UsageChartLogic.heatGrid(
            stats.heatmapDayTokens,
            maxTokens = (stats.heatmapDayTokens.values.maxOrNull() ?: 0L),
        )
    }
    val grid = remember(baseGrid, mode) { UsageChartLogic.applyHeatMode(baseGrid, mode) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Token 活动", color = onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(
                    if (isDark) Color(0xFF2A2A2A) else MaterialTheme.colorScheme.surfaceVariant,
                ).padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                val modes = listOf(
                    "每日" to UsageChartLogic.HeatMode.DAILY,
                    "每周" to UsageChartLogic.HeatMode.WEEKLY,
                    "累计" to UsageChartLogic.HeatMode.CUMULATIVE,
                )
                modes.forEach { (label, m) ->
                    val selected = mode == m
                    Text(
                        label,
                        color = if (selected) onSurface else muted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                when {
                                    !selected -> Color.Transparent
                                    isDark -> Color(0xFF141414)
                                    else -> MaterialTheme.colorScheme.surface
                                },
                            )
                            .clickable { mode = m }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val surface = MaterialTheme.colorScheme.surface
        val heatBase = if ((0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue) < 0.5f) {
            Color(0xFF0D0D0D)
        } else {
            Color(0xFFF1F5F9)
        }
        val accent = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                grid.columns.forEach { col ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        col.levels.forEach { level ->
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (level == 0) {
                                            heatBase.copy(alpha = if (isDark) 0.6f else 1f)
                                        } else {
                                            mixHeat(accent, heatBase, UsageChartLogic.heatLevelRatios[level])
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                grid.monthLabels.forEach { label ->
                    Box(Modifier.width((label.span * 11).dp)) {
                        Text(
                            label.label,
                            color = subtle,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("较少", color = subtle, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            UsageChartLogic.heatLevelRatios.forEachIndexed { i, ratio ->
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i == 0) heatBase.copy(alpha = if (isDark) 0.6f else 1f) else mixHeat(accent, heatBase, ratio)),
                )
                Spacer(Modifier.width(3.dp))
            }
            Spacer(Modifier.width(2.dp))
            Text("较多", color = subtle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Mix accent into base at `ratio` in linear RGB — mirrors color-mix(in oklab, sky, surface). */
private fun mixHeat(accent: Color, base: Color, ratio: Float): Color {
    val r = base.red + (accent.red - base.red) * ratio
    val g = base.green + (accent.green - base.green) * ratio
    val b = base.blue + (accent.blue - base.blue) * ratio
    return Color(r, g, b, 1f)
}

/**
 * Per-model smooth (monotone) daily token trend, one polyline per top model,
 * dashed horizontal guides and first/last-style ticks — ZCode
 * `AppUsageDailyModelTrendChart` semantics on a phone-sized canvas.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DailyTrendCard(
    daily: List<DayBucket>,
    cardBg: Color,
    muted: Color,
    subtle: Color,
    onSurface: Color,
) {
    val topModels = remember(daily) {
        val totals = HashMap<String, Long>()
        daily.forEach { day ->
            day.byModel.forEach { (m, t) -> totals[m] = (totals[m] ?: 0L) + t }
        }
        totals.entries.sortedByDescending { it.value }.take(UsageChartLogic.CHART_SERIES)
            .map { it.key }
    }
    val series = remember(daily, topModels) {
        topModels.map { model -> model to daily.map { it.byModel[model] ?: 0L } }
    }
    val maxTokens = remember(series) {
        series.flatMap { it.second }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    }
    val legend = series.mapIndexed { i, (model, _) -> model to ModelPalette[i % ModelPalette.size] }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .padding(14.dp),
    ) {
        Text("每日 Token 趋势图", color = onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))

        if (legend.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                legend.forEach { (model, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(model, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        val guide = if (subtle == Subtle) Color(0xFF3A3A3A) else MaterialTheme.colorScheme.outlineVariant
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            val w = size.width
            val h = size.height
            listOf(0.25f, 0.5f, 0.75f).forEach { g ->
                val y = h * (1f - g)
                drawLine(
                    color = guide.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
            series.forEachIndexed { si, (_, values) ->
                val pts = UsageChartLogic.seriesPoints(
                    values = values,
                    maxTokens = maxTokens.toFloat(),
                    width = w,
                    height = h,
                )
                if (pts.size >= 2) {
                    val path = Path().apply {
                        moveTo(pts.first().x, h - pts.first().y)
                        pts.drop(1).forEach { lineTo(it.x, (h - it.y).coerceIn(0f, h)) }
                    }
                    drawPath(
                        path,
                        color = ModelPalette[si % ModelPalette.size],
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        if (daily.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val dates = daily.map { UsageCalculator.formatDayLabel(it.dayStart) }
                val shown = linkedMapOf<Int, String>()
                daily.indices.forEach { i ->
                    if (UsageChartLogic.showTick(i, daily.size)) shown[i] = dates[i]
                }
                shown.entries.forEach { (_, label) ->
                    Text(label, color = subtle, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ModelUsageCard(
    stats: UsageStats,
    cardBg: Color,
    cardInner: Color,
    muted: Color,
    onSurface: Color,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .padding(14.dp),
    ) {
        Text("模型用量", color = onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cardInner)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(132.dp)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                DonutChart(models = stats.models, total = stats.totalTokens)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        UsageCalculator.formatCount(stats.totalTokens),
                        color = onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                    Text("tokens", color = muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                stats.models.forEachIndexed { i, m ->
                    val color = ModelPalette[i % ModelPalette.size]
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.model,
                                color = onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${UsageCalculator.formatCount(m.tokens)} tokens",
                                color = muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            UsageCalculator.formatShare(m.share),
                            color = muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (i < stats.models.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .height(1.dp)
                                .background(muted.copy(alpha = 0.12f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(models: List<ModelUsage>, total: Long) {
    val safeTotal = max(total, 1L)
    Canvas(Modifier.fillMaxSize()) {
        val stroke = size.minDimension * 0.18f
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        if (models.isEmpty() || total <= 0L) {
            drawArc(
                color = Color(0xFF3A3A3A),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            return@Canvas
        }
        var start = -90f
        models.forEachIndexed { i, m ->
            val sweep = (m.tokens.toFloat() / safeTotal.toFloat()) * 360f
            drawArc(
                color = ModelPalette[i % ModelPalette.size],
                startAngle = start,
                sweepAngle = sweep.coerceAtLeast(0.8f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            start += sweep
        }
    }
}
