package com.andmx.ui2.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andmx.ui2.settings.SegmentedRow
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.backAppBar
import com.andmx.ui2.usage.UsageCalculator
import com.andmx.ui2.usage.UsageRange
import com.andmx.ui2.usage.UsageStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsagePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var range by remember { mutableStateOf(UsageRange.ALL) }
    val stats by produceState(initialValue = UsageStats(), key1 = range) {
        value = runCatching { UsageCalculator.compute(context, range) }.getOrDefault(UsageStats())
    }

    Scaffold(topBar = { backAppBar("使用统计", onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SegmentedRow(
                options = UsageRange.entries.map { it.name to it.label },
                selected = range.name,
                onSelect = { range = UsageRange.valueOf(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )

            Text(
                "根据本地会话历史估算",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )

            if (stats.messages == 0) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "当前区间暂无可展示的用量数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                return@Column
            }

            SettingsGroup("概览") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("tokens 用量", formatNum(stats.totalTokens), Modifier.weight(1f))
                    StatCard("消息数量", stats.messages.toString(), Modifier.weight(1f))
                }
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard("会话数量", stats.sessions.toString(), Modifier.weight(1f))
                    StatCard("活跃天数", stats.activeDays.toString(), Modifier.weight(1f))
                }
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard("当前连续", "${stats.currentStreak} 天", Modifier.weight(1f))
                    StatCard("最长连续", "${stats.longestStreak} 天", Modifier.weight(1f))
                }
            }

            if (stats.peakHour >= 0) {
                SettingsGroup("峰值时段") {
                    StatCard(
                        "${stats.peakHour}:00 – ${stats.peakHour + 1}:00",
                        "${formatNum(stats.peakHourTokens)} tokens",
                        Modifier.fillMaxWidth()
                    )
                }
            }

            if (stats.daily.isNotEmpty()) {
                SettingsGroup("按天 Token 趋势") {
                    DailyChart(stats)
                }
            }

            if (stats.models.isNotEmpty()) {
                SettingsGroup("模型用量") {
                    stats.models.take(6).forEach { m ->
                        ModelBar(m.model, m.tokens, m.share)
                    }
                }
            }
        }
    }
}

private fun formatNum(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(n / 1_000f)
    else -> n.toString()
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun DailyChart(stats: UsageStats) {
    val max = stats.daily.maxOf { it.tokens }.coerceAtLeast(1)
    val bars = stats.daily.takeLast(14)
    Row(
        Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEach { d ->
            val frac = d.tokens.toFloat() / max
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height((frac * 120).dp.coerceAtLeast(3.dp))
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun ModelBar(model: String, tokens: Int, share: Float) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(model, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                "${formatNum(tokens)} · ${(share * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(share.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
