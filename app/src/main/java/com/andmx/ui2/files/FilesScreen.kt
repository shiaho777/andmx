package com.andmx.ui2.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.andmx.workspace.ProjectManager
import java.io.File

@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val projectManager = remember { ProjectManager(context) }
    // 跟随工作区切换：hostPath 变化时根目录随之更新
    val hostPath by projectManager.hostPath.collectAsState()
    val rootPath = hostPath
        ?: android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"

    var currentPath by remember(rootPath) { mutableStateOf(rootPath) }
    var openFile by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var changedOnly by remember { mutableStateOf(false) }

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
        modifier = modifier
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
                onUp = { if (currentPath != rootPath) currentPath = File(currentPath).parent ?: rootPath },
                onOpenFile = { openFile = it },
                onRefresh = { refresh++ }
            )
        }
    }
}

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
