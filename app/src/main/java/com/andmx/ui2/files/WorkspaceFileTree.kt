package com.andmx.ui2.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.exec.remote.RemoteDirEntry
import com.andmx.exec.remote.RemoteFsClient
import com.andmx.ui2.icons.FileTypeIcons
import com.andmx.workspace.ChangeTracker
import com.andmx.workspace.ProjectManager
import com.andmx.workspace.WorkspaceKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkspaceFileTree(
    initialPath: String? = null,
    onBackToTasks: () -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val projectManager = remember { ProjectManager(context) }
    val hostPath by projectManager.hostPath.collectAsState()
    val kind by projectManager.workspaceKind.collectAsState()
    val remoteSpec = remember(hostPath, kind) { projectManager.currentRemoteSpec() }

    if (kind == WorkspaceKind.REMOTE && remoteSpec != null) {
        RemoteWorkspaceFileTree(
            rootPath = remoteSpec.remotePath.ifBlank { "~" },
            spec = remoteSpec,
            initialPath = initialPath,
            onBackToTasks = onBackToTasks,
            onOpenFile = onOpenFile,
            modifier = modifier,
        )
        return
    }

    val rootPath = hostPath
        ?: android.os.Environment.getExternalStorageDirectory()?.absolutePath
        ?: "/sdcard"
    val resolved = remember(initialPath, rootPath, hostPath) {
        resolveLocalBrowseTarget(initialPath, rootPath, hostPath, projectManager.guestMountPath)
    }
    var currentPath by remember(rootPath, resolved.dir) { mutableStateOf(resolved.dir) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var changedOnly by remember { mutableStateOf(false) }
    val changes by ChangeTracker.changes.collectAsState()
    val changedMap = remember(changes) { changes.associateBy { it.path } }

    LaunchedEffect(initialPath, rootPath, hostPath) {
        val target = resolveLocalBrowseTarget(initialPath, rootPath, hostPath, projectManager.guestMountPath)
        currentPath = target.dir
        target.file?.let(onOpenFile)
    }

    val files = remember(currentPath, query, refresh, changedOnly, changes) {
        (File(currentPath).listFiles()?.asList() ?: emptyList())
            .filter { !it.name.startsWith(".") }
            .filter { query.isBlank() || it.name.contains(query, true) }
            .filter { f ->
                if (!changedOnly) true
                else f.isDirectory || changedMap.keys.any { it.endsWith(f.name) || it == f.absolutePath }
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val atRoot = currentPath == rootPath
    val relPath = currentPath.removePrefix(rootPath).ifBlank { "/" }

    FileTreeShell(
        title = "Workspace",
        subtitle = relPath,
        query = query,
        onQueryChange = { query = it },
        onBackToTasks = onBackToTasks,
        canGoUp = !atRoot,
        onGoUp = {
            if (!atRoot) currentPath = File(currentPath).parent ?: rootPath
        },
        changedOnly = changedOnly,
        onToggleChanged = { changedOnly = !changedOnly },
        onRefresh = { refresh++ },
        modifier = modifier,
    ) {
        when {
            files.isEmpty() -> {
                Text(
                    if (query.isNotBlank()) "没有匹配的文件。" else "当前目录为空。",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.absolutePath }) { file ->
                        FileTreeRow(
                            name = file.name,
                            isDirectory = file.isDirectory,
                            path = file.absolutePath,
                            onClick = {
                                if (file.isDirectory) {
                                    currentPath = file.absolutePath
                                    query = ""
                                } else {
                                    onOpenFile(file.absolutePath)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteWorkspaceFileTree(
    rootPath: String,
    spec: com.andmx.workspace.RemoteWorkspaceSpec,
    initialPath: String?,
    onBackToTasks: () -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(spec.id, spec.remotePath) { RemoteFsClient(context, spec) }
    val startPath = remember(initialPath, rootPath) {
        resolveRemoteBrowsePath(initialPath, rootPath)
    }
    var currentPath by remember(rootPath, startPath) { mutableStateOf(startPath) }
    var entries by remember { mutableStateOf<List<RemoteDirEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    fun reload(path: String) {
        loading = true
        error = null
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { client.listDir(path) }.getOrElse {
                    error = it.message ?: "读取目录失败"
                    emptyList()
                }
            }
            entries = list
            currentPath = path
            loading = false
        }
    }

    LaunchedEffect(rootPath, refresh, initialPath) {
        val target = resolveRemoteBrowsePath(initialPath, rootPath)
        reload(target)
    }

    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { it.name.contains(query, true) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val atRoot = currentPath.trimEnd('/') == rootPath.trimEnd('/')
    val relPath = currentPath.removePrefix(rootPath).ifBlank { "/" }

    FileTreeShell(
        title = "Workspace",
        subtitle = relPath,
        query = query,
        onQueryChange = { query = it },
        onBackToTasks = onBackToTasks,
        canGoUp = !atRoot,
        onGoUp = {
            if (!atRoot) {
                val parent = currentPath.trimEnd('/').substringBeforeLast('/', rootPath)
                reload(parent.ifBlank { rootPath })
            }
        },
        changedOnly = false,
        onToggleChanged = null,
        onRefresh = { refresh++ },
        modifier = modifier,
    ) {
        when {
            loading -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            error != null -> {
                Text(
                    error ?: "加载失败",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
            }
            filtered.isEmpty() -> {
                Text(
                    if (query.isNotBlank()) "没有匹配的文件。" else "当前目录为空。",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.path }) { entry ->
                        FileTreeRow(
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            path = entry.path,
                            onClick = {
                                if (entry.isDirectory) {
                                    query = ""
                                    reload(entry.path)
                                } else {
                                    onOpenFile(entry.path)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTreeShell(
    title: String,
    subtitle: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onBackToTasks: () -> Unit,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
    changedOnly: Boolean,
    onToggleChanged: (() -> Unit)?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBackToTasks)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "返回任务",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canGoUp) {
                FileTreeIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "上一级",
                    onClick = onGoUp,
                )
            }
            if (onToggleChanged != null) {
                FileTreeIconButton(
                    icon = Icons.Outlined.Difference,
                    contentDescription = "仅显示变更文件",
                    selected = changedOnly,
                    onClick = onToggleChanged,
                )
            }
            FileTreeIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "刷新文件树",
                onClick = onRefresh,
            )
        }

        Spacer(Modifier.height(6.dp))
        FileTreeSearchField(query = query, onQueryChange = onQueryChange)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun FileTreeSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val hint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "搜索文件...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = hint,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun FileTreeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(18.dp), tint = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    name: String,
    isDirectory: Boolean,
    path: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isDirectory) {
            Icon(
                Icons.Outlined.Folder,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                FileTypeIcons.iconFor(name),
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
