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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.diff.DiffEngine
import com.andmx.diff.DiffStats
import com.andmx.workspace.FileChange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileRewindDialog(
    changes: List<FileChange>,
    onRevertOne: (String) -> Unit,
    onRevertAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmAll by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val statsByPath = remember(changes) {
        changes.associate { it.path to DiffEngine.lineStats(it.oldContent, it.newContent) }
    }
    val totals = remember(statsByPath) {
        statsByPath.values.fold(DiffStats(0, 0)) { acc, s ->
            DiffStats(acc.added + s.added, acc.removed + s.removed)
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(tr("全部还原")) },
            text = {
                Text(tr("将把 ${changes.size} 个文件的改动还原为 agent 修改前的内容") +
                        "（外部改动过的文件会跳过）。此操作不可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    onRevertAll()
                }) { Text(tr("全部还原"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text(tr("返回")) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("文件改动")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (changes.isEmpty()) {
                    Text(tr("当前没有待还原的文件改动。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        tr("${changes.size} 个文件 · +${totals.added}/-${totals.removed}"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                changes.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val stats = statsByPath[c.path] ?: DiffStats(0, 0)
                            val kind = if (c.isNew) "新建" else "修改"
                            Text(
                                tr("$kind · +${stats.added}/-${stats.removed} · ${fmt.format(Date(c.timestamp))}"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                c.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { onRevertOne(c.path) }) { Text(tr("还原")) }
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.padding(2.dp))
            }
        },
        confirmButton = {
            if (changes.isNotEmpty()) {
                TextButton(onClick = { confirmAll = true }) {
                    Text(tr("全部还原"), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭")) }
        },
    )
}
