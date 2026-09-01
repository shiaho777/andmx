package com.andmx.ui2.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 上一 turn 的耗时/速度统计行（dsh StatsLine 对齐）。
 * 诚实显示规则：metrics 为 null（时间戳/用量不完整）时整行不渲染。
 */
@Composable
fun TurnMetricsLine(
    metrics: TurnMetrics.Reading?,
    modifier: Modifier = Modifier,
) {
    if (metrics == null) return
    val parts = buildList {
        metrics.ttftMs?.let { add("首字 ${TurnMetrics.formatMs(it)}") }
        metrics.tokensPerSecond?.let { add(TurnMetrics.formatTps(it)) }
    }
    if (parts.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}
