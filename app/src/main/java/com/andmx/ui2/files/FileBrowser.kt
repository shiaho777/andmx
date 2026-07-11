package com.andmx.ui2.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.ui2.chat.ChatComposerBus
import com.andmx.ui2.icons.FileTypeIcons
import com.andmx.workspace.ChangeTracker
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowser(
    rootPath: String,
    currentPath: String,
    query: String,
    refreshKey: Int,
    changedOnly: Boolean,
    onToggleChanged: () -> Unit,
    onQueryChange: (String) -> Unit,
    onEnterDir: (String) -> Unit,
    onUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val changes by ChangeTracker.changes.collectAsState()
    val changedMap = remember(changes) {
        changes.associate { it.path to it }
    }

    var newFileDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val files = remember(currentPath, query, refreshKey, changedOnly, changes) {
        (File(currentPath).listFiles()?.asList() ?: emptyList())
            .filter { !it.name.startsWith(".") }
            .filter { query.isBlank() || it.name.contains(query, true) }
            .filter { f ->
                if (!changedOnly) true
                else f.isDirectory || changedMap.keys.any { it.endsWith(f.name) }
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val atRoot = currentPath == rootPath
    val relPath = currentPath.removePrefix(rootPath).ifBlank { "/" }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text("Workspace", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            relPath, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = if (!atRoot) {
                    { IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一级") } }
                } else {{}},
                actions = {
                    IconButton(onClick = onToggleChanged) {
                        Icon(
                            Icons.Outlined.Difference,
                            "仅显示变更文件",
                            tint = if (changedOnly) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "刷新") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { newFileDialog = true }) {
                Icon(Icons.Outlined.NoteAdd, "新建文件")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索文件...") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (files.isEmpty()) {
                Text(
                    when {
                        changedOnly -> "没有变更文件。"
                        query.isNotBlank() -> "没有匹配的文件。"
                        else -> "当前目录为空。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (!atRoot) {
                        item {
                            ListItem(
                                headlineContent = { Text("..") },
                                leadingContent = { Icon(Icons.Outlined.DriveFolderUpload, null) },
                                modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { onEnterDir(File(currentPath).parent ?: rootPath) }
                                )
                            )
                        }
                    }
                    items(files, key = { it.absolutePath }) { file ->
                        val gitKind = changedMap.entries.firstOrNull { it.key.endsWith(file.name) }?.value?.let { fc ->
                            when {
                                fc.isNew -> "新增"
                                !fc.existedBefore -> "新增"
                                fc.newContent.isEmpty() -> "删除"
                                else -> "修改"
                            }
                        }
                        FileRow(
                            file = file,
                            gitKind = gitKind,
                            rootPath = rootPath,
                            onOpen = {
                                if (file.isDirectory) onEnterDir(file.absolutePath)
                                else onOpenFile(file.absolutePath)
                            },
                            onRename = { renameTarget = file },
                            onDelete = { deleteTarget = file },
                            onAddToChat = {
                                val rel = file.absolutePath.removePrefix(rootPath).ifBlank { file.name }
                                ChatComposerBus.insert("@$rel")
                            },
                            onCopyPath = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(file.absolutePath))
                            }
                        )
                    }
                }
            }
        }
    }

    if (newFileDialog) {
        NewFileDialog(
            onDismiss = { newFileDialog = false },
            onCreate = { name, isDir ->
                newFileDialog = false
                runCatching {
                    val f = File(currentPath, name)
                    if (isDir) f.mkdirs() else { f.parentFile?.mkdirs(); f.createNewFile() }
                }
                onRefresh()
            }
        )
    }
    renameTarget?.let { target ->
        RenameFileDialog(
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                runCatching { target.renameTo(File(target.parentFile, newName)) }
                renameTarget = null
                onRefresh()
            }
        )
    }
    deleteTarget?.let { target ->
        DeleteFileDialog(
            name = target.name,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                runCatching { target.deleteRecursively() }
                deleteTarget = null
                onRefresh()
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: File,
    gitKind: String?,
    rootPath: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddToChat: () -> Unit,
    onCopyPath: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(file.name)
                    if (gitKind != null) {
                        Text(
                            "  $gitKind",
                            style = MaterialTheme.typography.labelSmall,
                            color = gitKindColor(gitKind)
                        )
                    }
                }
            },
            supportingContent = if (!file.isDirectory) {
                { Text(humanSize(file.length()), style = MaterialTheme.typography.labelSmall) }
            } else null,
            leadingContent = {
                Icon(
                    if (file.isDirectory) FileTypeIcons.folderIcon() else FileTypeIcons.iconFor(file.name),
                    null
                )
            },
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = onOpen,
                onLongClick = { menu = true }
            )
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!file.isDirectory) {
                DropdownMenuItem(text = { Text("添加到聊天") }, onClick = { menu = false; onAddToChat() })
            }
            DropdownMenuItem(text = { Text("复制路径") }, onClick = { menu = false; onCopyPath() })
            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() }
            )
        }
    }
}

@Composable
private fun gitKindColor(kind: String): Color = when (kind) {
    "新增" -> Color(0xFF4CAF50)
    "修改" -> Color(0xFFFFA726)
    "删除" -> Color(0xFFEF5350)
    "重命名" -> Color(0xFF42A5F5)
    "冲突" -> Color(0xFFEF5350)
    else -> MaterialTheme.colorScheme.primary
}
