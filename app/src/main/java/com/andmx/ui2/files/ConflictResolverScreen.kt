package com.andmx.ui2.files

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andmx.ui2.theme.ZCodeSuccess
import com.andmx.ui2.theme.ZCodeWarning
import com.andmx.workspace.ConflictDocument
import com.andmx.workspace.ConflictFile
import com.andmx.workspace.ConflictLine
import com.andmx.workspace.ConflictParser
import com.andmx.workspace.ConflictResolution
import com.andmx.workspace.ConflictSegment
import com.andmx.workspace.GitBaseline
import kotlinx.coroutines.launch

data class ConflictOps(
    val listFiles: suspend () -> List<ConflictFile>,
    val readFile: suspend (String) -> String?,
    val resolveFile: suspend (String, String) -> GitBaseline.BaselineResult,
    val markResolved: suspend (String) -> GitBaseline.BaselineResult,
    val removeFile: suspend (String) -> GitBaseline.BaselineResult,
    val continueOperation: suspend () -> GitBaseline.BaselineResult,
    val operation: suspend () -> GitBaseline.MergeOperation,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolverDialog(
    workspacePath: String,
    ops: ConflictOps,
    onDismiss: () -> Unit,
    onAllResolved: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<ConflictFile>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeOk by remember { mutableStateOf(true) }
    var openTarget by remember { mutableStateOf<ConflictFile?>(null) }
    var operation by remember { mutableStateOf(GitBaseline.MergeOperation.NONE) }

    suspend fun reload() {
        loading = true
        val list = runCatching { ops.listFiles() }.getOrDefault(emptyList())
        files = list
        if (list.size > total) total = list.size
        operation = runCatching { ops.operation() }.getOrDefault(GitBaseline.MergeOperation.NONE)
        loading = false
    }

    LaunchedEffect(workspacePath) { reload() }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(tr("解决合并冲突"), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                workspacePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(enabled = !busy, onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("关闭"))
                        }
                    },
                    actions = {
                        IconButton(enabled = !busy && !loading, onClick = { scope.launch { reload() } }) {
                            Icon(Icons.Outlined.Refresh, tr("刷新"))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                val resolvedCount = (total - files.size).coerceAtLeast(0)
                Text(
                    tr("已解决 $resolvedCount/$total 个文件"),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
                LinearProgressIndicator(
                    progress = { if (total == 0) 1f else resolvedCount.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
                notice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (noticeOk) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(28.dp)) }

                    files.isEmpty() -> {
                        Text(
                            tr("所有冲突已解决。"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZCodeSuccess,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        val opLabel = when (operation) {
                            GitBaseline.MergeOperation.MERGE -> tr("合并")
                            GitBaseline.MergeOperation.REBASE -> tr("变基")
                            GitBaseline.MergeOperation.CHERRY_PICK -> tr("拣选")
                            GitBaseline.MergeOperation.REVERT -> tr("还原")
                            GitBaseline.MergeOperation.NONE -> ""
                        }
                        if (operation != GitBaseline.MergeOperation.NONE) {
                            Text(
                                tr("可以继续完成$opLabel 操作。"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    notice = null
                                    scope.launch {
                                        val result = runCatching { ops.continueOperation() }
                                            .getOrElse { GitBaseline.BaselineResult(false, it.message ?: tr("执行失败")) }
                                        busy = false
                                        noticeOk = result.ok
                                        notice = result.message
                                        if (result.ok) {
                                            onAllResolved()
                                            reload()
                                        }
                                    }
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text(tr("继续$opLabel")) }
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                onAllResolved()
                                onDismiss()
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text(tr("完成")) }
                    }

                    else -> {
                        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            items(files, key = { it.path }) { file ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !busy) { openTarget = file }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        file.status,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.errorContainer,
                                                RoundedCornerShape(4.dp),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                        Text(
                                            file.path,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            file.statusLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
            }
        }
    }

    openTarget?.let { target ->
        ConflictFileEditorDialog(
            file = target,
            ops = ops,
            onDismiss = { openTarget = null },
            onResolved = {
                openTarget = null
                scope.launch {
                    reload()
                    if (files.isEmpty()) {
                        noticeOk = true
                        notice = tr("所有冲突文件已标记解决。")
                    }
                }
            },
        )
    }
}

@Composable
private fun ConflictFileEditorDialog(
    file: ConflictFile,
    ops: ConflictOps,
    onDismiss: () -> Unit,
    onResolved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf<ConflictDocument?>(null) }
    var choices by remember { mutableStateOf<Map<Int, ConflictResolution>>(emptyMap()) }
    var manual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    LaunchedEffect(file.path) {
        val raw = runCatching { ops.readFile(file.path) }.getOrNull()
        loading = false
        when {
            raw == null -> error = tr("无法读取文件内容，可能已被删除。")
            else -> {
                val doc = ConflictParser.parse(raw)
                text = raw
                document = doc
                manualText = raw
                if (!doc.hasConflicts && !doc.malformed) manual = true
            }
        }
    }

    fun applyStrategy(strategy: ConflictResolution) {
        val doc = document ?: return
        busy = true
        error = null
        val resolved = ConflictParser.resolve(doc, doc.blocks.associate { it.index to strategy })
        scope.launch {
            val result = runCatching { ops.resolveFile(file.path, resolved) }
                .getOrElse { GitBaseline.BaselineResult(false, it.message ?: tr("写入失败")) }
            if (!result.ok) {
                busy = false
                error = result.message
                return@launch
            }
            val mark = runCatching { ops.markResolved(file.path) }
                .getOrElse { GitBaseline.BaselineResult(false, it.message ?: tr("标记失败")) }
            busy = false
            if (mark.ok) onResolved() else error = mark.message
        }
    }

    fun markManual() {
        val d = document
        val useManual = manual || d == null || !d.hasConflicts || d.malformed
        val candidate = if (useManual) manualText else ConflictParser.resolve(d, choices)
        if (!ConflictParser.canMarkResolved(candidate)) {
            error = tr("仍有冲突块未选择处理方式，或文本中残留冲突标记。")
            return
        }
        busy = true
        error = null
        scope.launch {
            val write = runCatching { ops.resolveFile(file.path, candidate) }
                .getOrElse { GitBaseline.BaselineResult(false, it.message ?: tr("写入失败")) }
            if (!write.ok) {
                busy = false
                error = write.message
                return@launch
            }
            val mark = runCatching { ops.markResolved(file.path) }
                .getOrElse { GitBaseline.BaselineResult(false, it.message ?: tr("标记失败")) }
            busy = false
            if (mark.ok) onResolved() else error = mark.message
        }
    }

    fun removeAsResolved() {
        busy = true
        error = null
        scope.launch {
            val mark = runCatching { ops.removeFile(file.path) }
                .getOrElse { GitBaseline.BaselineResult(false, it.message ?: tr("标记失败")) }
            busy = false
            if (mark.ok) onResolved() else error = mark.message
        }
    }

    val doc = document
    val showManual = manual || doc == null || !doc.hasConflicts || doc.malformed
    val allChosen = doc != null && ConflictParser.isFullyChosen(doc, choices)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Column {
                Text(
                    file.path,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${file.status} · ${file.statusLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        text = {
            Column {
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(24.dp)) }

                    showManual -> {
                        if (doc?.malformed == true) {
                            Text(
                                tr("冲突标记不完整，请手动编辑后标记已解决。"),
                                style = MaterialTheme.typography.labelSmall,
                                color = ZCodeWarning,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        OutlinedTextField(
                            value = manualText,
                            onValueChange = { manualText = it },
                            enabled = !busy,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        )
                    }

                    else -> {
                        Text(
                            tr("冲突块：${choices.size}/${doc!!.blocks.size} 已选择"),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) {
                            doc.segments.forEach { segment ->
                                when (segment) {
                                    is ConflictSegment.Context -> item(key = "ctx-${segment.lines.first().number}") {
                                        Column(Modifier.padding(vertical = 4.dp)) {
                                            segment.lines.takeLast(3).forEach { line ->
                                                Text(
                                                    "${line.number}  ${line.text}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }

                                    is ConflictSegment.Block -> item(key = "blk-${segment.index}") {
                                        ConflictBlockCard(
                                            block = segment,
                                            choice = choices[segment.index],
                                            enabled = !busy,
                                            onChoose = { strategy ->
                                                choices = choices + (segment.index to strategy)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (file.deletedByUs || file.deletedByThem) {
                    TextButton(enabled = !busy, onClick = { removeAsResolved() }) {
                        Text(tr("按删除解决"))
                    }
                }
                TextButton(
                    enabled = !busy && !loading && (showManual || allChosen),
                    onClick = { markManual() },
                ) { Text(tr("标记已解决")) }
            }
        },
        dismissButton = {
            Row {
                if (!showManual) {
                    TextButton(enabled = !busy, onClick = { applyStrategy(ConflictResolution.OURS) }) {
                        Text(tr("全部保留当前"))
                    }
                    TextButton(enabled = !busy, onClick = { applyStrategy(ConflictResolution.THEIRS) }) {
                        Text(tr("全部保留传入"))
                    }
                }
                if (!showManual) {
                    TextButton(enabled = !busy, onClick = { manual = true; manualText = text.orEmpty() }) {
                        Text(tr("手动编辑"))
                    }
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("取消")) }
            }
        },
    )
}

@Composable
private fun ConflictBlockCard(
    block: ConflictSegment.Block,
    choice: ConflictResolution?,
    enabled: Boolean,
    onChoose: (ConflictResolution) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text(
            tr("冲突块 ${block.index + 1} · 行 ${block.startLine}-${block.endLine}"),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            tr("当前（ours）") + (block.oursLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = ZCodeSuccess,
            modifier = Modifier.padding(top = 6.dp),
        )
        ConflictSideLines(block.ours, ZCodeSuccess)
        block.base?.let { base ->
            Text(
                tr("基准（base）") + (block.baseLabel?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = ZCodeWarning,
                modifier = Modifier.padding(top = 6.dp),
            )
            ConflictSideLines(base, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            tr("传入（theirs）") + (block.theirsLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
        ConflictSideLines(block.theirs, MaterialTheme.colorScheme.error)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = choice == ConflictResolution.OURS,
                enabled = enabled,
                onClick = { onChoose(ConflictResolution.OURS) },
                label = { Text(tr("保留当前")) },
            )
            FilterChip(
                selected = choice == ConflictResolution.THEIRS,
                enabled = enabled,
                onClick = { onChoose(ConflictResolution.THEIRS) },
                label = { Text(tr("保留传入")) },
            )
            FilterChip(
                selected = choice == ConflictResolution.BOTH,
                enabled = enabled,
                onClick = { onChoose(ConflictResolution.BOTH) },
                label = { Text(tr("都保留")) },
            )
        }
    }
}

@Composable
private fun ConflictSideLines(lines: List<ConflictLine>, color: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        if (lines.isEmpty()) {
            Text(
                tr("（空）"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lines.forEach { line ->
            Text(
                "${line.number}  ${line.text}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}