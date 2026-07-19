package com.andmx.ui2.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.exec.remote.RemoteDirEntry
import com.andmx.exec.remote.RemoteFsClient
import com.andmx.workspace.ProjectManager
import com.andmx.workspace.WorkspaceKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    initialPath: String? = null,
) {
    val context = LocalContext.current
    val projectManager = remember { ProjectManager(context) }
    val hostPath by projectManager.hostPath.collectAsState()
    val kind by projectManager.workspaceKind.collectAsState()
    val remoteSpec = remember(hostPath, kind) { projectManager.currentRemoteSpec() }

    if (kind == WorkspaceKind.REMOTE && remoteSpec != null) {
        RemoteFilesScreen(
            rootPath = remoteSpec.remotePath.ifBlank { "~" },
            spec = remoteSpec,
            initialPath = initialPath,
            modifier = modifier,
        )
        return
    }

    val rootPath = hostPath
        ?: android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
    val resolved = remember(initialPath, rootPath, hostPath) {
        resolveLocalBrowseTarget(initialPath, rootPath, hostPath, projectManager.guestMountPath)
    }

    var currentPath by remember(rootPath, resolved.dir) { mutableStateOf(resolved.dir) }
    var openFile by remember(resolved.file) { mutableStateOf(resolved.file) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var changedOnly by remember { mutableStateOf(false) }

    LaunchedEffect(initialPath, rootPath, hostPath) {
        val target = resolveLocalBrowseTarget(initialPath, rootPath, hostPath, projectManager.guestMountPath)
        currentPath = target.dir
        openFile = target.file
    }

    AnimatedContent(
        targetState = openFile,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "filesView",
        modifier = modifier,
    ) { target ->
        if (target != null) {
            FileViewerScreen(path = target, onBack = { openFile = null })
        } else StoragePermissionGate {
            FileBrowser(
                rootPath = rootPath,
                currentPath = currentPath,
                query = query,
                refreshKey = refresh,
                changedOnly = changedOnly,
                onToggleChanged = { changedOnly = !changedOnly },
                onQueryChange = { query = it },
                onEnterDir = { currentPath = it; query = "" },
                onUp = {
                    if (currentPath != rootPath) currentPath = File(currentPath).parent ?: rootPath
                },
                onOpenFile = { openFile = it },
                onRefresh = { refresh++ },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFilesScreen(
    rootPath: String,
    spec: com.andmx.workspace.RemoteWorkspaceSpec,
    initialPath: String? = null,
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
    var openFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    fun reload(path: String) {
        loading = true
        error = null
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { client.listDir(path) }.getOrElse {
                    error = it.message
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
        currentPath = target
        reload(target)
        val want = initialPath?.trim().orEmpty()
        if (want.isNotBlank()) {
            val text = withContext(Dispatchers.IO) {
                client.readText(want).getOrNull()
            }
            if (text != null) {
                openFile = want to text
            }
        }
    }

    if (openFile != null) {
        val (path, content) = openFile!!
        RemoteFileViewer(
            path = path,
            content = content,
            onBack = { openFile = null },
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("远程工作区", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            currentPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    val atRoot = currentPath == rootPath || currentPath == "/"
                    if (!atRoot) {
                        IconButton(onClick = {
                            val parent = currentPath.trimEnd('/')
                                .substringBeforeLast('/', missingDelimiterValue = "/")
                                .ifBlank { "/" }
                            reload(parent)
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一级")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refresh++ }) {
                        Icon(Icons.Outlined.Refresh, "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else if (error != null) {
                Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        reload(entry.path)
                                    } else {
                                        loading = true
                                        scope.launch {
                                            val text = withContext(Dispatchers.IO) {
                                                client.readText(entry.path).getOrElse { err ->
                                                    error = err.message
                                                    null
                                                }
                                            }
                                            loading = false
                                            if (text != null) openFile = entry.path to text
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (entry.isDirectory) Icons.Outlined.Folder
                                else Icons.Outlined.InsertDriveFile,
                                null,
                                Modifier.size(20.dp),
                            )
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!entry.isDirectory) {
                                    Text(
                                        humanSize(entry.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFileViewer(
    path: String,
    content: String,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        path.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        Text(
            content,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}


internal data class LocalBrowseTarget(val dir: String, val file: String?)

internal fun resolveLocalBrowseTarget(
    initialPath: String?,
    rootPath: String,
    hostPath: String?,
    guestMount: String,
): LocalBrowseTarget {
    val raw = initialPath?.trim().orEmpty()
    if (raw.isBlank()) return LocalBrowseTarget(rootPath, null)
    val mapped = when {
        hostPath != null && raw.startsWith(guestMount) -> {
            val rel = raw.removePrefix(guestMount).trimStart('/')
            if (rel.isEmpty()) hostPath else "$hostPath/$rel"
        }
        hostPath != null && raw.startsWith("/root/project") -> {
            val rel = raw.removePrefix("/root/project").trimStart('/')
            if (rel.isEmpty()) hostPath else "$hostPath/$rel"
        }
        raw.startsWith("/") -> raw
        hostPath != null -> "$hostPath/${raw.trimStart('/')}"
        else -> "$rootPath/${raw.trimStart('/')}"
    }
    val file = File(mapped)
    return when {
        file.isFile -> LocalBrowseTarget(file.parent ?: rootPath, file.absolutePath)
        file.isDirectory -> LocalBrowseTarget(file.absolutePath, null)
        file.parentFile?.isDirectory == true -> LocalBrowseTarget(file.parentFile!!.absolutePath, null)
        else -> {
            val parent = file.parent
            if (!parent.isNullOrBlank()) LocalBrowseTarget(parent, null)
            else LocalBrowseTarget(rootPath, null)
        }
    }
}

internal fun resolveRemoteBrowsePath(initialPath: String?, rootPath: String): String {
    val raw = initialPath?.trim().orEmpty()
    if (raw.isBlank()) return rootPath
    if (raw.startsWith("/") || raw.startsWith("~")) {
        val asFileParent = raw.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = rootPath)
        return asFileParent.ifBlank { rootPath }
    }
    val joined = rootPath.trimEnd('/') + "/" + raw.trimStart('/')
    return joined.substringBeforeLast('/', missingDelimiterValue = rootPath).ifBlank { rootPath }
}

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
