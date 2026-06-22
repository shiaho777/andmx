package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andmx.diff.DiffEngine
import com.andmx.diff.DiffLine
import com.andmx.diff.GitDiffParser
import com.andmx.exec.ProcessSpec
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.LocalProotEnvironment
import com.andmx.exec.proot.ProotRuntime
import com.andmx.workspace.ChangeTracker
import com.andmx.workspace.FileChange
import com.andmx.workspace.GuestPaths
import com.andmx.ui.theme.AndmxCodeTextStyle
import com.andmx.ui.components.DiffLineRow
import com.andmx.ui.components.DiffAddFg
import com.andmx.ui.components.DiffDelFg
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing
import kotlinx.coroutines.launch

// Diff colors now live in com.andmx.ui.components; alias for local stats text.
private val AddFg get() = DiffAddFg
private val DelFg get() = DiffDelFg

private enum class DiffSource { AGENT, GIT }

@Composable
fun DiffPane(
    selectedPath: String? = null,
    onOpenFile: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val changes by ChangeTracker.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val guestFs = remember(context) { GuestFs(ProotRuntime(context)) }
    var source by remember { mutableStateOf(DiffSource.AGENT) }
    var message by remember { mutableStateOf<String?>(null) }

    fun discard(change: FileChange) {
        scope.launch {
            message = restoreChange(guestFs, change)
        }
    }

    fun accept(change: FileChange) {
        ChangeTracker.accept(change.path)
        message = "已保留 ${change.path}"
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        // source toggle
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegToggle("Agent 变更 (${changes.size})", source == DiffSource.AGENT) { source = DiffSource.AGENT }
            Spacer(Modifier.width(Spacing.xs))
            SegToggle("Git Diff", source == DiffSource.GIT) { source = DiffSource.GIT }
            Spacer(Modifier.weight(1f))
            if (source == DiffSource.AGENT && changes.isNotEmpty()) {
                Text(
                    "全部保留", style = AndmxTheme.typography.labelMedium, color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable {
                        val count = changes.size
                        ChangeTracker.acceptAll()
                        message = "已保留 $count 个变更"
                    }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    "全部丢弃", style = AndmxTheme.typography.labelMedium, color = colors.warning,
                    modifier = Modifier.clip(Radii.sm).clickable {
                        scope.launch {
                            val restored = changes.toList().map { restoreChange(guestFs, it) }
                            message = "已丢弃 ${restored.size} 个变更"
                        }
                    }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        message?.let {
            Row(
                Modifier.fillMaxWidth().background(colors.sunken).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, style = AndmxTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text(
                    "关闭",
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.clip(Radii.sm).clickable { message = null }
                        .padding(horizontal = Spacing.xs, vertical = 2.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        when (source) {
            DiffSource.AGENT -> AgentChanges(
                changes = changes,
                selectedPath = selectedPath,
                onOpenFile = onOpenFile,
                onAccept = ::accept,
                onDiscard = ::discard,
            )
            DiffSource.GIT -> GitChanges(context, selectedPath, onOpenFile)
        }
    }
}

@Composable
private fun SegToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Text(
        label,
        style = AndmxTheme.typography.labelLarge,
        color = if (selected) colors.textPrimary else colors.textSecondary,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.selected else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

@Composable
private fun AgentChanges(
    changes: List<FileChange>,
    selectedPath: String?,
    onOpenFile: (String) -> Unit,
    onAccept: (FileChange) -> Unit,
    onDiscard: (FileChange) -> Unit,
) {
    val colors = AndmxTheme.colors
    if (changes.isEmpty()) { EmptyHint("暂无变更", "agent 编辑文件后,变更会在此以 diff 呈现"); return }
    val ordered = remember(changes, selectedPath) {
        changes.sortedBy { if (GuestPaths.same(it.path, selectedPath.orEmpty())) 0 else 1 }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(ordered, key = { it.path }) { change ->
            val lines = remember(change.path, change.newContent, change.oldContent) {
                DiffEngine.diff(change.oldContent, change.newContent)
            }
            FileDiffBlock(
                change.path,
                lines,
                isNew = change.isNew,
                selected = GuestPaths.same(change.path, selectedPath.orEmpty()),
                onOpenFile = { onOpenFile(change.path) },
                onAccept = { onAccept(change) },
                onDiscard = { onDiscard(change) },
            )
        }
    }
}

@Composable
private fun GitChanges(
    context: android.content.Context,
    selectedPath: String?,
    onOpenFile: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    var loading by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf<List<com.andmx.diff.GitFileDiff>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val runtime = ProotRuntime(context)
        val env = LocalProotEnvironment(context, runtime)
        val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc",
            "cd /root 2>/dev/null; command -v git >/dev/null 2>&1 || apk add --no-cache git >/dev/null 2>&1; " +
                "git -c core.pager=cat diff 2>&1")))
        val out = res.stdout
        if (out.contains("not a git repository", true)) message = "/root 不是 git 仓库"
        else {
            files = GitDiffParser.parse(out)
            if (files.isEmpty()) message = if (out.isBlank()) "工作区无改动" else out.take(400)
        }
        loading = false
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
        }
        files.isNotEmpty() -> {
            val ordered = remember(files, selectedPath) {
                files.sortedBy { if (GuestPaths.same(it.path, selectedPath.orEmpty())) 0 else 1 }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(ordered, key = { it.path }) { f ->
                    val stats = remember(f) { DiffEngine.stats(f.lines) }
                    FileDiffBlock(
                        f.path,
                        f.lines,
                        isNew = false,
                        precomputedStats = stats,
                        selected = GuestPaths.same(f.path, selectedPath.orEmpty()),
                        onOpenFile = { onOpenFile(f.path) },
                    )
                }
            }
        }
        else -> EmptyHint("Git", message ?: "无差异")
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    val colors = AndmxTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Difference, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(Spacing.md))
            Text(title, style = AndmxTheme.typography.titleSmall, color = colors.textSecondary)
            Spacer(Modifier.height(Spacing.xs))
            Text(subtitle, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
        }
    }
}

@Composable
private fun FileDiffBlock(
    path: String,
    lines: List<DiffLine>,
    isNew: Boolean,
    precomputedStats: com.andmx.diff.DiffStats? = null,
    selected: Boolean = false,
    onOpenFile: (() -> Unit)? = null,
    onAccept: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
) {
    val colors = AndmxTheme.colors
    val stats = precomputedStats ?: remember(lines) { DiffEngine.stats(lines) }
    val hScroll = rememberScrollState()

    Column(
        Modifier.fillMaxWidth().padding(Spacing.md)
            .then(if (selected) Modifier.border(1.dp, colors.accent, Radii.md).padding(Spacing.sm) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).clip(Radii.sm)
                    .then(if (onOpenFile != null) Modifier.clickable(onClick = onOpenFile) else Modifier)
                    .padding(vertical = 2.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(path, style = AndmxTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1)
            }
            if (selected) {
                Spacer(Modifier.width(Spacing.sm))
                Text("当前文件", style = AndmxTheme.typography.labelSmall, color = colors.accent)
            }
            if (isNew) Text("新建  ", style = AndmxTheme.typography.labelSmall, color = colors.accent)
            Text("+${stats.added}", style = AndmxTheme.typography.labelMedium, color = AddFg)
            Spacer(Modifier.width(Spacing.sm))
            Text("-${stats.removed}", style = AndmxTheme.typography.labelMedium, color = DelFg)
            if (onOpenFile != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "打开文件",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable(onClick = onOpenFile)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
            if (onAccept != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "保留",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable(onClick = onAccept)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
            if (onDiscard != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "丢弃",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.warning,
                    modifier = Modifier.clip(Radii.sm).clickable(onClick = onDiscard)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().background(colors.codeBackground, Radii.sm)
                .horizontalScroll(hScroll).padding(vertical = Spacing.xs),
        ) {
            for (line in lines) DiffRow(line)
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

private fun restoreChange(fs: GuestFs, change: FileChange): String {
    return runCatching {
        if (change.existedBefore) {
            fs.writeText(change.path, change.oldContent)
        } else {
            fs.deleteFile(change.path)
        }
        ChangeTracker.remove(change.path)
        if (change.existedBefore) "已还原 ${change.path}" else "已删除新文件 ${change.path}"
    }.getOrElse { "丢弃失败 ${change.path}: ${it.message}" }
}

@Composable
private fun DiffRow(line: DiffLine) = DiffLineRow(line)
