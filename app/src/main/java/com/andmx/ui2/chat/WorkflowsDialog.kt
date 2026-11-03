package com.andmx.ui2.chat

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.workflow.WorkflowDashboard
import com.andmx.agent.workflow.WorkflowDefinition
import com.andmx.agent.workflow.WorkflowEvent
import com.andmx.agent.workflow.WorkflowNodeStatus
import com.andmx.agent.workflow.WorkflowRunSnapshot
import com.andmx.agent.workflow.WorkflowRunStatus
import com.andmx.data.WorkflowStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorkflowsDialog(
    defs: List<WorkflowDefinition>,
    runs: List<WorkflowStore.RunListItem>,
    onPickRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("工作流")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(tr("定义"), style = MaterialTheme.typography.titleSmall)
                defs.forEach { def ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            "${def.title}  ·  ${def.definitionId}@${def.definitionVersion}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${def.kind} · ${def.phaseOrder.size} phases · ${def.description.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (defs.isEmpty()) {
                    Text(tr("没有已保存的工作流定义"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.padding(6.dp))
                HorizontalDivider()
                Spacer(Modifier.padding(6.dp))
                Text(tr("运行"), style = MaterialTheme.typography.titleSmall)
                runs.forEach { run ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickRun(run.runId) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            run.task.take(48),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${run.status.name} · ${run.definitionId} · ${fmt.format(Date(run.updatedAtMs))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
                if (runs.isEmpty()) {
                    Text(tr("还没有工作流运行。让 agent 用 CreateWorkflow 起一个，或用 expert 定义。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭")) }
        },
    )
}

@Composable
fun WorkflowRunDetailDialog(
    snapshot: WorkflowRunSnapshot,
    events: List<WorkflowEvent>,
    openHoles: List<ChatViewModel.WorkflowOpenHole> = emptyList(),
    onRefresh: () -> Unit = {},
    onOpenArtifact: (String, String) -> Unit = { _, _ -> },
    onCancel: (String) -> Unit,
    onResume: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(snapshot.task.take(40)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "runId: ${snapshot.runId}\n" +
                        "status: ${snapshot.status}  ·  kind: ${snapshot.kind}\n" +
                        snapshot.currentPhase?.let { "current: $it\n" }.orEmpty() +
                        snapshot.reportPath?.let { "report: $it\n" }.orEmpty() +
                        snapshot.resumedFrom?.let { "resumedFrom: $it\n" }.orEmpty() +
                        snapshot.supersededBy?.let { "supersededBy: $it\n" }.orEmpty() +
                        snapshot.pauseReason?.let { "paused: $it\n" }.orEmpty() +
                        snapshot.failure?.message?.let { "failure: $it\n" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (snapshot.kind == "dynamic") {
                    Text(
                        tr("动态脚本在设备侧 WebView 沙箱执行；节点与产物写入 workflow journal。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (openHoles.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        tr("待补全留白") + " (${openHoles.size})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val now = System.currentTimeMillis()
                    openHoles.forEach { hole ->
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    hole.name.ifBlank { hole.siteId },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    buildString {
                                        append(hole.siteId)
                                        append(" · #").append(hole.ordinal)
                                        if (hole.since > 0) {
                                            append(" · 等待 ")
                                            append(((now - hole.since).coerceAtLeast(0)) / 1000)
                                            append("s")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.padding(4.dp))
                Text(tr("阶段"), style = MaterialTheme.typography.titleSmall)
                snapshot.phases.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            p.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (p.status) {
                                WorkflowNodeStatus.completed -> MaterialTheme.colorScheme.primary
                                WorkflowNodeStatus.failed, WorkflowNodeStatus.cancelled ->
                                    MaterialTheme.colorScheme.error
                                WorkflowNodeStatus.active -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(p.phase, style = MaterialTheme.typography.bodySmall)
                    }
                }
                val graphNodes = snapshot.graph.nodes
                if (graphNodes.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    Text(tr("图节点 (${graphNodes.size})"), style = MaterialTheme.typography.titleSmall)
                    graphNodes.take(40).forEach { n ->
                        Text(
                            "${n.status.name.padEnd(9)} ${n.id} — ${n.title}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (snapshot.artifacts.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    Text(tr("产物") + " (${snapshot.artifacts.size})", style = MaterialTheme.typography.titleSmall)
                    snapshot.artifacts.forEach { a ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable(enabled = a.path.isNotBlank()) {
                                    onOpenArtifact(a.label, a.path)
                                },
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    a.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append(a.contentType)
                                        if (a.path.isNotBlank()) append(" · ").append(a.path)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (snapshot.presets.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    Text(tr("看板"), style = MaterialTheme.typography.titleSmall)
                    snapshot.presets.forEach { preset ->
                        val spec = WorkflowDashboard.parseSpec(preset.specJson)
                        val items = WorkflowDashboard.parseItems(
                            snapshot.reports
                                .filter { it.artifactId == preset.id }
                                .map { it.itemJson },
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    preset.title ?: preset.id,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (spec == null) {
                                    Text(
                                        tr("无效的看板定义"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    when (preset.kind) {
                                        "table" -> WorkflowDashboard.table(spec, items)?.let { table ->
                                            Text(
                                                table.columns.joinToString(" · ") { it.label },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            table.rows.take(20).forEach { row ->
                                                Text(
                                                    row.joinToString(" · "),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                        "metrics" -> WorkflowDashboard.metrics(spec, items).forEach { metric ->
                                            Text(
                                                buildString {
                                                    append(metric.label).append(": ").append(metric.value)
                                                    metric.unit?.let { append(' ').append(it) }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        "board" -> WorkflowDashboard.board(spec, items).forEach { column ->
                                            Text(
                                                buildString {
                                                    append(column.name).append(" (")
                                                    append(column.cards.size).append(")")
                                                    if (column.cards.isNotEmpty()) {
                                                        append(" · ").append(column.cards.joinToString(", "))
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        "chart" -> WorkflowDashboard.chart(spec, items)?.let { chart ->
                                            WorkflowChart(chart)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                snapshot.resultJson?.let { result ->
                    Spacer(Modifier.padding(4.dp))
                    Text(tr("返回值"), style = MaterialTheme.typography.titleSmall)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Text(
                            result.take(4_000),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (events.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    Text(tr("事件"), style = MaterialTheme.typography.titleSmall)
                    events.takeLast(30).forEach { e ->
                        Text(
                            "${e.type} ${e.nodeId ?: e.phase ?: ""} ${e.message.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (snapshot.pauseReason != "superseded" &&
                    (snapshot.status == WorkflowRunStatus.paused ||
                        snapshot.status == WorkflowRunStatus.failed ||
                        snapshot.status == WorkflowRunStatus.cancelled)
                ) {
                    TextButton(onClick = { onResume(snapshot.runId) }) {
                        Text(tr("恢复运行"))
                    }
                }
                if (snapshot.status == WorkflowRunStatus.running ||
                    snapshot.status == WorkflowRunStatus.paused
                ) {
                    TextButton(onClick = { onCancel(snapshot.runId) }) {
                        Text(tr("取消运行"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRefresh) { Text(tr("刷新")) }
                TextButton(onClick = onDismiss) { Text(tr("返回")) }
            }
        },
    )
}

@Composable
fun WorkflowArtifactDialog(
    artifact: ChatViewModel.WorkflowArtifactView,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(artifact.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    artifact.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    artifact.content,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭")) }
        },
    )
}
