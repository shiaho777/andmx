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
import androidx.compose.material.icons.outlined.CalendarMonth
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
import com.andmx.ui2.usage.UsageRange
import com.andmx.ui2.usage.UsageStats
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val ModelPalette = listOf(
    Color(0xFF4C8DFF),
    Color(0xFF34C759),
    Color(0xFFAF52DE),
    Color(0xFFFF453A),
    Color(0xFFFF9F0A),
    Color(0xFF64D2FF),
    Color(0xFFFFD60A),
    Color(0xFFBF5AF2),
)

private val HeatEmpty = Color(0xFF2B2B2B)
private val HeatLevels = listOf(
    Color(0xFF2B2B2B),
    Color(0xFF2F4A63),
    Color(0xFF3A6FA0),
    Color(0xFF4C8DFF),
    Color(0xFF7AB0FF),
    Color(0xFFA8CBFF),
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
    val heatEmpty = if (isDark) HeatEmpty else Color(0xFFE8E8E8)

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
                "应用用量",
                style = MaterialTheme.typography.titleSmall,
                color = onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            Text(
                "来自本地应用会话历史。",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(bottom = 14.dp),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("时间范围", style = MaterialTheme.typography.bodySmall, color = muted)
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
                RefreshRow(onRefresh = { refreshKey++ }, muted = muted, onSurface = onSurface)
                return@Column
            }

            MetricGrid(
                stats = stats,
                cardBg = cardBg,
                muted = muted,
                onSurface = onSurface,
            )

            Spacer(Modifier.height(14.dp))

            HeatmapCard(
                daily = stats.daily,
                cardBg = cardBg,
                muted = muted,
                subtle = subtle,
                onSurface = onSurface,
                empty = heatEmpty,
            )

            Spacer(Modifier.height(14.dp))

            DailyTrendCard(
                daily = stats.daily,
                models = stats.models,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricGrid(
    stats: UsageStats,
    cardBg: Color,
    muted: Color,
    onSurface: Color,
) {
    val items = listOf(
        MetricItem(Icons.Outlined.Token, "tokens 用量", UsageCalculator.formatCount(stats.totalTokens), null),
        MetricItem(Icons.Outlined.Forum, "会话数量", stats.sessions.toString(), null),
        MetricItem(Icons.Outlined.ChatBubbleOutline, "消息数量", stats.messages.toString(), null),
        MetricItem(Icons.Outlined.CalendarMonth, "活跃天数", stats.activeDays.toString(), null),
        MetricItem(Icons.Outlined.LocalFireDepartment, "当前连续天数", stats.currentStreak.toString(), null),
        MetricItem(
            Icons.AutoMirrored.Outlined.ShowChart,
            "最常用模型",
            stats.favoriteModel.ifBlank { "—" },
            if (stats.favoriteModel.isNotBlank()) "占比 ${UsageCalculator.formatShare(stats.favoriteShare)}" else null,
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { item ->
                    MetricCard(
                        item = item,
                        modifier = Modifier.weight(1f),
                        cardBg = cardBg,
                        muted = muted,
                        onSurface = onSurface,
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class MetricItem(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val sub: String?,
)

@Composable
private fun MetricCard(
    item: MetricItem,
    modifier: Modifier,
    cardBg: Color,
    muted: Color,
    onSurface: Color,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                item.label,
                color = muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.value,
            color = onSurface,
            fontSize = if (item.value.length > 10) 16.sp else 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 24.sp,
        )
        if (item.sub != null) {
            Text(
                item.sub,
                color = muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeatmapCard(
    daily: List<DayBucket>,
    cardBg: Color,
    muted: Color,
    subtle: Color,
    onSurface: Color,
    empty: Color,
) {
    val maxTok = daily.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("活跃热力图", color = onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("较少", color = subtle, style = MaterialTheme.typography.labelSmall)
                HeatLevels.forEachIndexed { i, c ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i == 0) empty else c),
                    )
                }
                Text("较多", color = subtle, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(12.dp))

        val weeks = remember(daily) { buildHeatWeeks(daily) }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            weeks.forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { cell ->
                        val level = if (cell == null || cell.tokens <= 0L) 0
                        else {
                            val r = cell.tokens.toFloat() / maxTok.toFloat()
                            when {
                                r < 0.15f -> 1
                                r < 0.35f -> 2
                                r < 0.55f -> 3
                                r < 0.8f -> 4
                                else -> 5
                            }
                        }
                        val color = if (level == 0) empty else HeatLevels[level.coerceIn(0, HeatLevels.lastIndex)]
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(color),
                        )
                    }
                }
            }
        }
    }
}

private fun buildHeatWeeks(daily: List<DayBucket>): List<List<DayBucket?>> {
    if (daily.isEmpty()) return emptyList()
    val byDay = daily.associateBy { it.dayStart }
    val first = daily.first().dayStart
    val last = daily.last().dayStart
    val cal = Calendar.getInstance().apply { timeInMillis = first }
    val dow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7)
    cal.add(Calendar.DAY_OF_YEAR, -dow)
    val start = UsageCalculator.dayStart(cal.timeInMillis)
    val weeks = mutableListOf<List<DayBucket?>>()
    var cursor = start
    while (cursor <= last + 6 * 86_400_000L) {
        val week = (0 until 7).map { offset ->
            val d = cursor + offset * 86_400_000L
            if (d < first || d > last) null else byDay[d]
        }
        if (week.any { it != null }) weeks.add(week)
        cursor += 7 * 86_400_000L
        if (weeks.size > 12) break
    }
    return weeks
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyTrendCard(
    daily: List<DayBucket>,
    models: List<ModelUsage>,
    cardBg: Color,
    muted: Color,
    subtle: Color,
    onSurface: Color,
) {
    val modelOrder = models.map { it.model }
    val colorOf = remember(modelOrder) {
        modelOrder.mapIndexed { i, m -> m to ModelPalette[i % ModelPalette.size] }.toMap()
    }
    val maxTok = daily.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L
    val dateFmt = remember { SimpleDateFormat("M月d日", Locale.CHINA) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .padding(14.dp),
    ) {
        Text("按天 Token 趋势", color = onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF181818).copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height
                val w = size.width
                val guides = listOf(0.25f, 0.5f, 0.75f)
                guides.forEach { g ->
                    val y = h * (1f - g)
                    drawLine(
                        color = Color.White.copy(alpha = 0.06f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                    )
                }
            }
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                daily.forEach { day ->
                    val frac = (day.tokens.toFloat() / maxTok.toFloat()).coerceIn(0f, 1f)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        if (day.tokens <= 0L) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(subtle.copy(alpha = 0.25f)),
                            )
                        } else {
                            val parts = if (day.byModel.isEmpty()) {
                                listOf("" to day.tokens)
                            } else {
                                day.byModel.entries.sortedByDescending { it.value }.map { it.key to it.value }
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(frac.coerceAtLeast(0.02f)),
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                parts.asReversed().forEach { (model, tok) ->
                                    val share = tok.toFloat() / day.tokens.toFloat()
                                    val c = colorOf[model] ?: ModelPalette.first()
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(share.coerceAtLeast(0.01f), fill = true)
                                            .background(c),
                                    )
                                }
                            }
                        }
                    }
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
                val first = daily.first().dayStart
                val mid = daily[daily.size / 2].dayStart
                val last = daily.last().dayStart
                listOf(first, mid, last).distinct().forEach { ts ->
                    Text(dateFmt.format(Date(ts)), color = subtle, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (models.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                models.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colorOf[m.model] ?: ModelPalette.first()),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(m.model, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
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
