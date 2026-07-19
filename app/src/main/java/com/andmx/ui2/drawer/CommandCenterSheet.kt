package com.andmx.ui2.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CommandCenterItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val icon: ImageVector,
    val keywords: List<String> = emptyList(),
    val onRun: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandCenterSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    items: List<CommandCenterItem>,
) {
    if (!open) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val filtered = remember(query, items) {
        val q = query.trim()
        if (q.isEmpty()) items
        else items.filter { item ->
            item.title.contains(q, true) ||
                item.subtitle.contains(q, true) ||
                item.keywords.any { it.contains(q, true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "搜索",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("搜索命令、设置与入口…") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                },
                shape = RoundedCornerShape(12.dp),
            )
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
            Spacer(Modifier.height(12.dp))
            if (filtered.isEmpty()) {
                Text(
                    "暂无相关结果",
                    modifier = Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.id }) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    item.onRun()
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                item.icon,
                                null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (item.subtitle.isNotBlank()) {
                                    Text(
                                        item.subtitle,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
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

fun defaultCommandCenterItems(
    onNewTask: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenTerminal: () -> Unit,
    onToggleSidebar: () -> Unit,
): List<CommandCenterItem> = listOf(
    CommandCenterItem(
        id = "new-task",
        title = "新建任务",
        subtitle = "开启一个新的会话任务",
        icon = Icons.Outlined.Add,
        keywords = listOf("new", "task", "新建", "任务"),
        onRun = onNewTask,
    ),
    CommandCenterItem(
        id = "files",
        title = "查看文件",
        subtitle = "打开当前工作区文件树",
        icon = Icons.Outlined.FolderOpen,
        keywords = listOf("files", "file", "folder", "文件", "文件夹"),
        onRun = onOpenFiles,
    ),
    CommandCenterItem(
        id = "skills",
        title = "技能",
        subtitle = "管理已安装技能",
        icon = Icons.Outlined.Bolt,
        keywords = listOf("skills", "skill", "技能"),
        onRun = onOpenSkills,
    ),
    CommandCenterItem(
        id = "terminal",
        title = "切换终端",
        subtitle = "打开或关闭底部终端",
        icon = Icons.Outlined.Terminal,
        keywords = listOf("terminal", "shell", "终端"),
        onRun = onOpenTerminal,
    ),
    CommandCenterItem(
        id = "sidebar",
        title = "切换侧边栏",
        subtitle = "显示或隐藏任务侧边栏",
        icon = Icons.Outlined.Menu,
        keywords = listOf("sidebar", "drawer", "侧边栏", "菜单"),
        onRun = onToggleSidebar,
    ),
    CommandCenterItem(
        id = "settings",
        title = "设置",
        subtitle = "打开应用设置",
        icon = Icons.Outlined.Settings,
        keywords = listOf("settings", "设置"),
        onRun = onOpenSettings,
    )
)
