package com.andmx.ui2.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModelSwitchGuardDialog(
    guard: ChatViewModel.ModelSwitchGuard,
    onCompressAndSwitch: () -> Unit,
    onForceSwitch: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("切换到 ${guard.modelLabel}") },
        text = {
            Column {
                if (guard.running) {
                    Text(
                        "当前会话已用 ${guard.used} tokens，超过 ${guard.modelLabel} 的可用上下文 ${guard.target}。" +
                            "任务运行中无法压缩，请等任务完成后再切换。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "当前会话已用 ${guard.used} tokens，超过 ${guard.modelLabel} 的可用上下文 ${guard.target}（已预留最大输出）。" +
                            "建议先压缩上下文再切换，避免切换后立即溢出。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.padding(2.dp))
                    Text(
                        "压缩后会用当前模型整理上下文，再切换到 ${guard.modelLabel}。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (!guard.running) {
                TextButton(onClick = onCompressAndSwitch) { Text("先压缩再切换") }
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (!guard.running) {
                    TextButton(onClick = onForceSwitch) { Text("仍然切换") }
                }
                TextButton(onClick = onCancel) { Text(if (guard.running) "知道了" else "取消") }
            }
        },
    )
}
