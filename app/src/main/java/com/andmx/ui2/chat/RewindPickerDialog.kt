package com.andmx.ui2.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RewindPickerDialog(
    checkpoints: List<ChatMessage>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember { mutableStateOf<ChatMessage?>(null) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    if (pending == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("回滚到检查点") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "选择一条用户消息，回到它发送前的状态：该消息及之后的对话会被移除，" +
                            "该时点之后 agent 做过的文件改动会一并还原（外部改动过的文件会跳过）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(4.dp))
                    checkpoints.asReversed().take(16).forEach { m ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pending = m }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                m.content.lineSequence().firstOrNull().orEmpty().take(48),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                fmt.format(Date(m.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
    } else {
        val m = pending!!
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("确认回滚") },
            text = {
                Text(
                    "将移除「${m.content.lineSequence().firstOrNull().orEmpty().take(40)}」及其后的全部对话，" +
                        "并还原该时点之后的文件改动。此操作不可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onPick(m.id)
                }) { Text("回滚", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("返回") }
            },
        )
    }
}
