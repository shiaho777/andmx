package com.andmx.ui2.chat

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        title = { Text("工作流") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("定义", style = MaterialTheme.typography.titleSmall)
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
                    Text(
                        "没有已保存的工作流定义",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.padding(6.dp))
                HorizontalDivider()
                Spacer(Modifier.padding(6.dp))
                Text("运行", style = MaterialTheme.typography.titleSmall)
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
                    Text(
                        "还没有工作流运行。让 agent 用 CreateWorkflow 起一个，或用 expert 定义。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
fun WorkflowRunDetailDialog(
    snapshot: WorkflowRunSnapshot,
    events: List<WorkflowEvent>,
    onCancel: (String) -> Unit,
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
                        snapshot.pauseReason?.let { "paused: $it\n" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.padding(4.dp))
                Text("阶段", style = MaterialTheme.typography.titleSmall)
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
                    Text("图节点 (${graphNodes.size})", style = MaterialTheme.typography.titleSmall)
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
                    Text("产物 (${snapshot.artifacts.size})", style = MaterialTheme.typography.titleSmall)
                    snapshot.artifacts.forEach { a ->
                        Text(
                            a.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (events.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    Text("事件", style = MaterialTheme.typography.titleSmall)
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
            if (snapshot.status == WorkflowRunStatus.running ||
                snapshot.status == WorkflowRunStatus.paused
            ) {
                TextButton(onClick = { onCancel(snapshot.runId) }) {
                    Text("取消运行", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("返回") }
        },
    )
}
