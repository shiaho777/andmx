package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.BackgroundTasks
import com.andmx.ui2.settings.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BackgroundTasksDialog(
    tasks: List<BackgroundTasks.Snapshot>,
    onDismiss: () -> Unit,
    onStop: (String) -> Unit,
    loadOutput: (String) -> String?,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val outputs = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(expanded) {
        val id = expanded ?: return@LaunchedEffect
        if (outputs[id] == null) {
            outputs[id] = withContext(Dispatchers.IO) { loadOutput(id) }.orEmpty()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("后台任务")) },
        text = {
            if (tasks.isEmpty()) {
                Text(
                    tr("暂无后台任务"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            expanded = expanded == task.id,
                            output = outputs[task.id],
                            onToggle = { expanded = if (expanded == task.id) null else task.id },
                            onStop = { onStop(task.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭")) }
        },
    )
}

@Composable
private fun TaskRow(
    task: BackgroundTasks.Snapshot,
    expanded: Boolean,
    output: String?,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    val running = task.state == BackgroundTasks.TaskState.RUNNING
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (task.state) {
                            BackgroundTasks.TaskState.RUNNING -> MaterialTheme.colorScheme.primary
                            BackgroundTasks.TaskState.COMPLETED -> MaterialTheme.colorScheme.tertiary
                            BackgroundTasks.TaskState.KILLED -> MaterialTheme.colorScheme.outline
                            BackgroundTasks.TaskState.FAILED -> MaterialTheme.colorScheme.error
                        },
                    ),
            )
            Text(
                statusLabel(task),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
            Text(
                task.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            task.command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onToggle) {
                Text(if (expanded) tr("收起输出") else tr("查看输出"))
            }
            if (running) {
                TextButton(onClick = onStop) { Text(tr("停止")) }
            }
        }
        if (expanded) {
            Text(
                output?.ifBlank { tr("暂无输出") } ?: tr("正在读取输出…"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
            )
        }
    }
}

private fun statusLabel(task: BackgroundTasks.Snapshot): String = when (task.state) {
    BackgroundTasks.TaskState.RUNNING -> tr("运行中")
    BackgroundTasks.TaskState.COMPLETED -> tr("已完成") + (task.exitCode?.let { " · exit=$it" } ?: "")
    BackgroundTasks.TaskState.FAILED -> tr("失败") + (task.exitCode?.let { " · exit=$it" } ?: "")
    BackgroundTasks.TaskState.KILLED -> tr("已停止")
}
