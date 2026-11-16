package com.andmx.ui2.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.workflow.WorkflowDashboard
import com.andmx.ui2.theme.AndMXType

@Composable
private fun statusColor(value: String): Color = when (WorkflowDashboard.statusTone(value)) {
    "done" -> MaterialTheme.colorScheme.primary
    "running" -> MaterialTheme.colorScheme.tertiary
    "failed" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun cardContainer(value: String): Color = when (WorkflowDashboard.statusTone(value)) {
    "done" -> MaterialTheme.colorScheme.primaryContainer
    "running" -> MaterialTheme.colorScheme.tertiaryContainer
    "failed" -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surface
}

@Composable
private fun cardContent(value: String): Color = when (WorkflowDashboard.statusTone(value)) {
    "done" -> MaterialTheme.colorScheme.onPrimaryContainer
    "running" -> MaterialTheme.colorScheme.onTertiaryContainer
    "failed" -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun WorkflowTableView(
    table: WorkflowDashboard.Table,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            table.columns.forEach { column ->
                Text(
                    buildString {
                        append(column.label)
                        column.unit?.let { append(" (").append(it).append(')') }
                    },
                    style = AndMXType.cardTitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            items(table.rows) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    table.columns.indices.forEach { index ->
                        val cell = row.getOrElse(index) { "" }
                        Text(
                            cell,
                            style = AndMXType.cardMeta,
                            color = if (WorkflowDashboard.statusTone(cell) == "none") {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                statusColor(cell)
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkflowMetricsView(
    metrics: List<WorkflowDashboard.Metric>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        metrics.forEach { metric ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            metric.value,
                            style = AndMXType.statLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        metric.unit?.let { unit ->
                            Text(
                                unit,
                                style = AndMXType.micro,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                    Text(
                        metric.label,
                        style = AndMXType.cardMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun WorkflowBoardView(
    lanes: List<WorkflowDashboard.BoardLane>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lanes.forEach { lane ->
            Column(Modifier.width(136.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            lane.name,
                            style = AndMXType.cardTitle,
                            color = statusColor(lane.name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${lane.cards.size}",
                            style = AndMXType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                lane.cards.forEach { card ->
                    Surface(
                        color = cardContainer(card.status),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Text(
                            card.title,
                            style = AndMXType.cardMeta,
                            color = cardContent(card.status),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                if (lane.cards.isEmpty()) {
                    Text(
                        "—",
                        style = AndMXType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}