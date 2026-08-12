package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.workspace.FileChange

@Composable
fun RewindBar(
    changes: List<FileChange>,
    rewindResult: ChatController.RewindResult?,
    onRewind: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (changes.isEmpty() && rewindResult == null) return

    var confirm by remember { mutableStateOf(false) }

    if (changes.isNotEmpty()) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { confirm = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Undo,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "撤回 ${changes.size} 项文件改动",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                "恢复",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("撤回文件改动") },
            text = {
                Column {
                    Text(
                        "将把这 ${changes.size} 项 agent 改动恢复到编辑前的原始内容。" +
                            "已被外部（shell / 你手动 / 其它工具）改动的文件会跳过，不会覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        "涉及文件：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    changes.take(8).forEach {
                        Text(
                            "· ${it.path}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (changes.size > 8) {
                        Text(
                            "… 另有 ${changes.size - 8} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onRewind()
                }) { Text("撤回") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            },
        )
    }

    val res = rewindResult
    if (res != null) {
        AlertDialog(
            onDismissRequest = onDismissResult,
            title = { Text("撤回结果") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("已恢复 ${res.reverted} 项文件改动。", style = MaterialTheme.typography.bodySmall)
                    if (res.unsafe > 0) {
                        Spacer(Modifier.padding(3.dp))
                        Text(
                            "${res.unsafe} 项因被外部改动而跳过：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        res.unsafePaths.forEach {
                            Text(
                                "· $it",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissResult) { Text("知道了") }
            },
        )
    }
}
