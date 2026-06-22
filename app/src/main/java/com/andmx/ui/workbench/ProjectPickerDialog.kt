package com.andmx.ui.workbench

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing
import com.andmx.workspace.ProjectManager

/**
 * Pick a **real directory on the phone's storage** as the project workspace.
 * The chosen directory is bind-mounted into the proot guest at /root/project,
 * so the agent operates on the user's actual files (visible in any file
 * manager). Mirrors Codex: project = user-chosen cwd, never built-in.
 *
 * Uses ACTION_OPEN_DOCUMENT_TREE for the system directory picker, then maps
 * the returned content URI back to a filesystem path (/sdcard/...) that proot
 * can bind. Requires MANAGE_EXTERNAL_STORAGE (sideload-oriented).
 */
@Composable
fun ProjectPickerDialog(
    manager: ProjectManager,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val hostPath by manager.hostPath.collectAsState()

    // System directory picker — returns a tree URI we convert to a file path.
    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persist permission so we keep access across reboots.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val path = uriToFilePath(uri)
            if (path != null) {
                manager.selectProject(path)
                onSelected(path)
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.canvas,
        title = { Text("选择项目目录", style = AndmxTheme.typography.titleLarge, color = colors.textPrimary) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "选择手机上的一个文件夹作为工作区。agent 将直接读写该目录里的文件," +
                        "你在文件管理器里能看到所有改动。",
                    style = AndmxTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.md))

                // Current project
                hostPath?.let { path ->
                    ProjectRow(
                        name = manager.projectName,
                        path = path,
                        selected = true,
                        onClick = {},
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "当前: $path",
                        style = AndmxTheme.typography.bodySmall,
                        color = colors.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.md))
                }

                // Pick button
                ActionPill(
                    label = if (hostPath == null) "选择文件夹" else "更换文件夹",
                    icon = Icons.Outlined.FolderOpen,
                    onClick = { dirLauncher.launch(null) },
                )

                Spacer(Modifier.height(Spacing.sm))

                // Quick-access common roots
                Text("常用位置", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
                Spacer(Modifier.height(Spacing.xs))
                manager.suggestedRoots().forEach { root ->
                    QuickPath(root) {
                        manager.selectProject(root)
                        onSelected(root)
                        onDismiss()
                    }
                    Spacer(Modifier.height(Spacing.xs))
                }

                // Optional: clear
                if (hostPath != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "取消绑定",
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.warning,
                        modifier = Modifier.clickable {
                            manager.clearProject()
                            onDismiss()
                        }.padding(vertical = Spacing.xs),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = colors.accent) }
        },
    )
}

/**
 * Convert a SAF tree content URI to a filesystem path (/sdcard/...).
 * Handles the common `content://com.android.externalstorage.documents/tree/primary:Foo`
 * form. Returns null if it can't be resolved (the caller falls back to manual entry).
 */
private fun uriToFilePath(uri: Uri): String? {
    val sdcard = Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
    // primary storage: tree/primary:Sub/Dir → /sdcard/Sub/Dir
    val docId = runCatching {
        android.provider.DocumentsContract.getTreeDocumentId(uri)
    }.getOrNull() ?: return null
    return when {
        docId == "primary:" -> sdcard
        docId.startsWith("primary:") -> "$sdcard/${docId.removePrefix("primary:")}"
        // Some providers use the raw path; best-effort.
        docId.startsWith("/") -> docId
        else -> null
    }
}

@Composable
private fun ProjectRow(name: String, path: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.surface).border(1.dp, colors.border, Radii.sm)
            .clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(name, style = AndmxTheme.typography.labelLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun QuickPath(path: String, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm).clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(path, style = AndmxTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sendActive)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = AndmxTheme.typography.labelLarge, color = colors.onAccent)
    }
}
