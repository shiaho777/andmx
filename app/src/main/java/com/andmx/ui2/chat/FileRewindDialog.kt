package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andmx.diff.DiffEngine
import com.andmx.diff.DiffStats
import com.andmx.ui2.settings.tr
import com.andmx.ui2.theme.Chrome
import com.andmx.workspace.FileChange
import com.andmx.workspace.GuestPaths

@Composable
fun FileRewindDialog(
    changes: List<FileChange>,
    onRevertOne: (String) -> Unit,
    onRevertAll: () -> Unit,
    onDismiss: () -> Unit,
    initialPath: String? = null,
    canRevert: Boolean = true,
    onOpenFile: (String) -> Unit = {},
) {
    var selectedPath by remember(initialPath) { mutableStateOf(initialPath) }
    var confirmation by remember { mutableStateOf<List<FileChange>?>(null) }
    val selectedIndex = changes.indexOfFirst { GuestPaths.same(it.path, selectedPath.orEmpty()) }
    val selected = changes.getOrNull(selectedIndex)
    val statsByPath = remember(changes) {
        changes.associate { it.path to DiffEngine.lineStats(it.oldContent, it.newContent) }
    }
    val targets = confirmation
    if (targets != null) {
        val single = targets.singleOrNull()
        val stillCurrent = if (single != null) single in changes else targets == changes
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (single == null) tr("全部还原") else tr("还原此文件")) },
            text = {
                Column {
                    if (single != null) Text(single.path, style = MaterialTheme.typography.bodySmall)
                    Text(tr("将把 ${targets.size} 个文件的改动还原为 agent 修改前的内容") +
                        tr("（外部改动过的文件会跳过）。此操作不可撤销。"))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canRevert && stillCurrent,
                    onClick = {
                        confirmation = null
                        if (single != null) onRevertOne(single.path) else onRevertAll()
                    },
                ) { Text(tr("还原"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(tr("返回")) } },
        )
        return
    }

    fun back() {
        if (selected != null) selectedPath = null else onDismiss()
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(12.dp).widthIn(max = Chrome.maxReadingWidth)
                .fillMaxWidth().fillMaxHeight(0.88f),
            shape = Chrome.panel,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (selected != null) {
                        IconButton(onClick = ::back) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("文件改动"))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            selected?.path?.substringAfterLast('/') ?: tr("文件改动"),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            selected?.path ?: tr("${changes.size} 个文件"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val stats = selected?.let { statsByPath[it.path] } ?: DiffStats(
                        statsByPath.values.sumOf { it.added },
                        statsByPath.values.sumOf { it.removed },
                    )
                    ChangeCounts(stats)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (selected == null) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        if (changes.isEmpty()) item {
                            Text(
                                tr("当前没有待还原的文件改动。"),
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(changes, key = { it.path }) { change ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) { selectedPath = change.path }
                                    .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Description, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(change.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${if (change.isNew) tr("新建") else tr("修改")} · ${change.path.substringBeforeLast('/', "")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                ChangeCounts(statsByPath.getValue(change.path))
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp))
                            }
                        }
                    }
                } else {
                    key(selected.path) {
                        RecordedChangeDiff(selected, Modifier.weight(1f).fillMaxWidth())
                    }
                    if (changes.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(enabled = selectedIndex > 0, onClick = { selectedPath = changes[selectedIndex - 1].path }) {
                                Text(tr("上一个文件"))
                            }
                            Text("${selectedIndex + 1} / ${changes.size}", style = MaterialTheme.typography.labelMedium)
                            TextButton(enabled = selectedIndex < changes.lastIndex, onClick = { selectedPath = changes[selectedIndex + 1].path }) {
                                Text(tr("下一个文件"))
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (selected == null) {
                        TextButton(enabled = canRevert && changes.isNotEmpty(), onClick = { confirmation = changes.toList() }) {
                            Text(tr("全部还原"))
                        }
                    } else {
                        TextButton(enabled = canRevert, onClick = { confirmation = listOf(selected) }) {
                            Text(tr("还原此文件"))
                        }
                        TextButton(onClick = { onOpenFile(selected.path) }) { Text(tr("打开文件")) }
                    }
                    TextButton(onClick = onDismiss) { Text(tr("关闭")) }
                }
            }
        }
    }
}

@Composable
private fun ChangeCounts(stats: DiffStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("+${stats.added}", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.secondary)
        Text("−${stats.removed}", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RecordedChangeDiff(change: FileChange, modifier: Modifier) {
    val oldLines = if (change.oldContent.isEmpty()) 0 else change.oldContent.count { it == '\n' } + 1
    val newLines = if (change.newContent.isEmpty()) 0 else change.newContent.count { it == '\n' } + 1
    val tooLarge = change.oldContent.length.toLong() + change.newContent.length.toLong() > 400_000L ||
        oldLines.toLong() * newLines.toLong() > 2_000_000L
    val hunks = remember(change, tooLarge) {
        if (tooLarge) emptyList() else DiffRender.reviewHunks(DiffEngine.diff(change.oldContent, change.newContent))
    }
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        if (tooLarge || hunks.isEmpty()) item {
            Text(
                if (tooLarge) tr("变更过大，未展开逐行 diff。可先查看文件或直接还原。")
                else tr("没有可显示的差异。"),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        hunks.forEach { hunk ->
            item { DiffHunkHeader(hunk.header) }
            items(hunk.lines) { DiffPreviewLine(it, showLineNumbers = true, wrapLongLines = true) }
        }
    }
}
