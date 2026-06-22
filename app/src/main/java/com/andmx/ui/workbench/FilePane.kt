package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.diff.DiffEngine
import com.andmx.exec.proot.ProotRuntime
import com.andmx.ui.theme.AndmxCodeTextStyle
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing
import com.andmx.workspace.ChangeTracker
import com.andmx.workspace.FileChange
import com.andmx.workspace.GuestPaths
import java.io.File

/**
 * Browses the proot guest filesystem (the rootfs lives in the app's data dir,
 * so plain File access is enough to read it) and views text files. Mirrors
 * Codex's file pane: breadcrumb header, dir-first listing, simple viewer.
 */
@Composable
fun FilePane(
    state: FilePaneState,
    selectedGuestPath: String? = null,
    onOpenDiff: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val rootfs = remember { ProotRuntime(context).rootfsDir }
    val changes by ChangeTracker.changes.collectAsState()

    if (!rootfs.exists()) {
        Box(modifier.fillMaxSize().background(colors.canvas), contentAlignment = Alignment.Center) {
            Text("rootfs 未安装 · 先打开终端完成首次安装", style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
        }
        return
    }

    var pathNotice by remember { mutableStateOf<String?>(null) }

    fun guestPath(f: File): String = GuestPaths.fromRootFile(rootfs, f)
    fun fileForGuestPath(guestPath: String): File = GuestPaths.resolve(rootfs, guestPath)
    fun updateStateForTarget(target: File) {
        if (target.isFile) {
            state.currentGuestPath = guestPath(target.parentFile ?: rootfs)
            state.viewingGuestPath = guestPath(target)
        } else {
            state.currentGuestPath = guestPath(target)
            state.viewingGuestPath = null
        }
    }

    LaunchedEffect(rootfs, selectedGuestPath) {
        val guest = selectedGuestPath?.trim().orEmpty()
        if (guest.isBlank()) return@LaunchedEffect
        val resolved = runCatching {
            fileForGuestPath(guest)
        }
        val target = resolved.getOrNull()
        val root = rootfs.canonicalFile
        if (target == null || (target.path != root.path && !target.path.startsWith(root.path + File.separator))) {
            pathNotice = "路径无法打开: $guest"
        } else {
            when {
                target.isFile -> {
                    updateStateForTarget(target)
                    pathNotice = null
                }
                target.isDirectory -> {
                    updateStateForTarget(target)
                    pathNotice = null
                }
                else -> pathNotice = "未找到: $guest"
            }
        }
    }

    val current = remember(rootfs, state.currentGuestPath, state.refreshToken) {
        runCatching { fileForGuestPath(state.currentGuestPath) }.getOrDefault(rootfs)
    }
    val viewing = remember(rootfs, state.viewingGuestPath, state.refreshToken) {
        state.viewingGuestPath?.let { runCatching { fileForGuestPath(it) }.getOrNull()?.takeIf(File::isFile) }
    }
    val activePath = viewing?.let { guestPath(it) } ?: guestPath(current)
    val activeChange = remember(changes, activePath, viewing) {
        if (viewing?.isFile == true) changes.firstOrNull { GuestPaths.same(it.path, activePath) } else null
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        // breadcrumb / toolbar
        Row(
            Modifier.fillMaxWidth().height(42.dp).padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (viewing != null || current != rootfs) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp).clip(Radii.sm).clickable {
                        if (viewing != null) state.viewingGuestPath = null
                        else state.currentGuestPath = guestPath(current.parentFile ?: rootfs)
                    },
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text = activePath,
                style = AndmxTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (viewing?.isFile == true) {
                HeaderIconAction(Icons.Outlined.ContentCopy, "复制引用") {
                    val reference = GuestPaths.reference(activePath)
                    clipboard.setText(AnnotatedString(reference))
                    pathNotice = "已复制引用 ${reference.trim()}"
                }
                Spacer(Modifier.width(Spacing.xs))
            }
            if (activeChange != null) {
                HeaderIconAction(Icons.Outlined.Difference, "打开差异", accent = true) { onOpenDiff(activePath) }
                Spacer(Modifier.width(Spacing.xs))
            }
            Icon(
                Icons.Outlined.Refresh, contentDescription = "刷新",
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp).clip(Radii.sm).clickable { state.refreshToken++ },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        pathNotice?.let { notice ->
            Row(
                Modifier.fillMaxWidth().background(colors.warningSoft)
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice,
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.warning,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "关闭",
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.clip(Radii.sm).clickable { pathNotice = null }
                        .padding(horizontal = Spacing.xs, vertical = 2.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        val target = viewing
        if (target != null) {
            FileContextBar(
                file = target,
                guestPath = activePath,
                change = activeChange,
                onOpenDiff = onOpenDiff,
            )
            FileViewer(target)
        } else {
            val entries = remember(current, state.refreshToken) {
                current.listFiles()?.sortedWith(
                    compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
                ).orEmpty()
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries) { f ->
                    val guest = guestPath(f)
                    FileRow(
                        file = f,
                        guestPath = guest,
                        change = if (f.isFile) changes.firstOrNull { GuestPaths.same(it.path, guest) } else null,
                        dragText = if (f.isFile) GuestPaths.reference(guest) else null,
                        onClick = { updateStateForTarget(f) },
                    )
                }
            }
        }
    }
}

@Stable
class FilePaneState {
    var currentGuestPath by mutableStateOf("/")
    var viewingGuestPath by mutableStateOf<String?>(null)
    var refreshToken by mutableStateOf(0)
}

@Composable
fun rememberFilePaneState(): FilePaneState = remember { FilePaneState() }

@Composable
private fun HeaderIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = AndmxTheme.colors
    Box(
        Modifier.size(28.dp).clip(Radii.sm).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (accent) colors.warning else colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FileContextBar(
    file: File,
    guestPath: String,
    change: FileChange?,
    onOpenDiff: (String?) -> Unit,
) {
    val colors = AndmxTheme.colors
    val stats = remember(change?.oldContent, change?.newContent) {
        change?.let { DiffEngine.stats(DiffEngine.diff(it.oldContent, it.newContent)) }
    }
    Row(
        Modifier.fillMaxWidth().background(colors.sunken)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (change != null) Icons.Outlined.Difference else Icons.Outlined.Info,
            contentDescription = null,
            tint = if (change != null) colors.warning else colors.textTertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            file.name,
            style = AndmxTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(humanSize(file.length()), style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        Spacer(Modifier.width(Spacing.sm))
        Text(guestPath, style = AndmxCodeTextStyle, color = colors.textTertiary, maxLines = 1, modifier = Modifier.weight(1f))
        if (change != null && stats != null) {
            Text(if (change.isNew) "新建" else "已修改", style = AndmxTheme.typography.labelSmall, color = colors.warning)
            Spacer(Modifier.width(Spacing.xs))
            Text("+${stats.added}", style = AndmxTheme.typography.labelSmall, color = colors.accent)
            Spacer(Modifier.width(Spacing.xs))
            Text("-${stats.removed}", style = AndmxTheme.typography.labelSmall, color = colors.warning)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "差异",
                style = AndmxTheme.typography.labelMedium,
                color = colors.accent,
                modifier = Modifier.clip(Radii.sm).clickable { onOpenDiff(guestPath) }
                    .padding(horizontal = Spacing.sm, vertical = 2.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: File,
    guestPath: String,
    change: FileChange?,
    dragText: String?,
    onClick: () -> Unit,
) {
    val colors = AndmxTheme.colors
    val stats = remember(change?.oldContent, change?.newContent) {
        change?.let { DiffEngine.stats(DiffEngine.diff(it.oldContent, it.newContent)) }
    }
    var rowMod = Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = Spacing.md, vertical = 7.dp)
    if (dragText != null) {
        rowMod = rowMod.dragAndDropSource {
            detectTapGestures(onLongPress = {
                startTransfer(
                    androidx.compose.ui.draganddrop.DragAndDropTransferData(
                        android.content.ClipData.newPlainText("andmx-file", dragText),
                    ),
                )
            })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowMod) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = guestPath,
            tint = when {
                change != null -> colors.warning
                file.isDirectory -> colors.accent
                else -> colors.textTertiary
            },
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            file.name + if (file.isDirectory) "/" else "",
            style = AndmxTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (file.isFile) {
            if (change != null && stats != null) {
                Row(
                    Modifier.clip(Radii.sm).border(1.dp, colors.border, Radii.sm)
                        .padding(horizontal = Spacing.xs, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(if (change.isNew) "新建" else "改动", style = AndmxTheme.typography.labelSmall, color = colors.warning)
                    Spacer(Modifier.width(Spacing.xs))
                    Text("+${stats.added}", style = AndmxTheme.typography.labelSmall, color = colors.accent)
                    Spacer(Modifier.width(Spacing.xs))
                    Text("-${stats.removed}", style = AndmxTheme.typography.labelSmall, color = colors.warning)
                }
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(humanSize(file.length()), style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        }
    }
}

@Composable
private fun FileViewer(file: File) {
    val colors = AndmxTheme.colors
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val content = remember(file.path) { readPreview(file) }
    Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll).padding(Spacing.md)) {
        Text(content, style = AndmxCodeTextStyle, color = colors.textPrimary)
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun readPreview(file: File, limit: Int = 512 * 1024): String = runCatching {
    if (file.length() > limit) return@runCatching "(文件过大,仅支持预览 < 512KB)"
    val bytes = file.readBytes()
    if (bytes.take(4096).any { it.toInt() == 0 }) "(二进制文件,无法预览)"
    else String(bytes, Charsets.UTF_8)
}.getOrElse { "(无法读取: ${it.message})" }
