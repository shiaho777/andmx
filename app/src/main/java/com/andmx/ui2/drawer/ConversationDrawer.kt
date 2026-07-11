package com.andmx.ui2.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.data.AndmxDatabase
import com.andmx.data.ConversationEntity
import com.andmx.data.TaskGroupEntity
import kotlinx.coroutines.launch

enum class ViewMode { GROUPED, BY_PROJECT, TIMELINE }
enum class SortMode { UPDATED, CREATED }

val groupColors = mapOf(
    "gray" to Color(0xFF9E9E9E), "red" to Color(0xFFEF5350),
    "orange" to Color(0xFFFFA726), "yellow" to Color(0xFFFFEE58),
    "green" to Color(0xFF66BB6A), "blue" to Color(0xFF42A5F5),
    "purple" to Color(0xFFAB47BC)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    workspaceName: String = "",
    content: @Composable () -> Unit
) {
    val drawerState = rememberDrawerState(if (open) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dao = remember { AndmxDatabase.get(context).dao() }
    val conversations by dao.observeConversations().collectAsState(initial = emptyList())
    val archivedCount by dao.observeArchived().collectAsState(initial = emptyList()).let { it ->
        androidx.compose.runtime.remember { mutableStateOf(0) }.also { _ ->
            androidx.compose.runtime.LaunchedEffect(it) {}
        }; it
    }
    val taskGroups by dao.observeTaskGroups().collectAsState(initial = emptyList())

    var query by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(ViewMode.GROUPED) }
    var sortMode by remember { mutableStateOf(SortMode.UPDATED) }
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    var showArchived by remember { mutableStateOf(false) }
    val archived by dao.observeArchived().collectAsState(initial = emptyList())

    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var newGroupDialog by remember { mutableStateOf(false) }

    LaunchedEffect(open) {
        if (open && drawerState.isClosed) drawerState.open()
        else if (!open && drawerState.isOpen) drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed && open) onDismiss()
    }

    fun toggleCollapse(key: String) {
        collapsedGroups = if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
    }
    fun expandAll() { collapsedGroups = emptySet() }
    fun collapseAll(groupKeys: List<String>) { collapsedGroups = groupKeys.toSet() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                // ZCode 对齐：侧边栏顶部工作区指示器（Commit 2 补完整选择器下拉）
                if (workspaceName.isNotBlank()) {
                    WorkspaceStrip(
                        name = workspaceName,
                        onOpenFiles = onOpenFiles,
                    )
                    HorizontalDivider()
                }
                DrawerHeader(
                    showArchived = showArchived,
                    onNew = {
                        scope.launch {
                            val id = dao.insertConversation(
                                ConversationEntity(project = "/root", title = "新任务")
                            )
                            onSelectConversation(id)
                        }
                    },
                    onBackFromArchive = { showArchived = false; query = "" }
                )

                if (showArchived) {
                    SearchField(query = query, archived = true, onQueryChange = { query = it })
                    HorizontalDivider()
                    val archFiltered = if (query.isBlank()) archived
                        else archived.filter { it.title.contains(query, true) }
                    if (archFiltered.isEmpty()) {
                        DrawerEmpty(searching = query.isNotBlank(), archived = true)
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(archFiltered, key = { it.id }) { conv ->
                                ConversationRow(
                                    conversation = conv,
                                    onClick = { onSelectConversation(conv.id) },
                                    onRename = {}, onDelete = { deleteTarget = conv },
                                    onArchive = {}, onTogglePin = {},
                                    archivedMode = true,
                                    onUnarchive = { scope.launch { dao.setArchived(conv.id, false) } }
                                )
                            }
                        }
                    }
                } else {
                    DrawerToolbar(
                        viewMode = viewMode, sortMode = sortMode,
                        onViewChange = { viewMode = it },
                        onSortChange = { sortMode = it },
                        allExpanded = collapsedGroups.isEmpty(),
                        onToggleExpandAll = {
                            if (collapsedGroups.isEmpty()) {
                                collapseAll(buildGroupKeys(conversations, taskGroups, viewMode))
                            } else {
                                expandAll()
                            }
                        },
                        query = query, onQueryChange = { query = it }
                    )
                    HorizontalDivider()

                    val filtered = if (query.isBlank()) conversations
                        else conversations.filter { it.title.contains(query, true) }
                    val groupKeys = buildGroupKeys(filtered, taskGroups, viewMode)

                    if (filtered.isEmpty()) {
                        DrawerEmpty(searching = query.isNotBlank())
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            val pinned = filtered.filter { it.pinned }
                            if (pinned.isNotEmpty()) {
                                item(key = "sec_pinned") { GroupHeader("已置顶", pinned.size) }
                                items(pinned, key = { "p_${it.id}" }) { conv ->
                                    TaskItem(conv, scope, dao,
                                        onRename = { renameTarget = conv },
                                        onDelete = { deleteTarget = conv },
                                        onSelect = onSelectConversation
                                    )
                                }
                            }

                            when (viewMode) {
                                ViewMode.TIMELINE -> {
                                    val sorted = sortList(filtered.filterNot { it.pinned }, sortMode)
                                    item(key = "sec_recent") { GroupHeader("最近任务", sorted.size) }
                                    items(sorted, key = { it.id }) { conv ->
                                        TaskItem(conv, scope, dao,
                                            onRename = { renameTarget = conv },
                                            onDelete = { deleteTarget = conv },
                                            onSelect = onSelectConversation
                                        )
                                    }
                                }
                                else -> {
                                    val groups = buildGroups(filtered.filterNot { it.pinned }, taskGroups, viewMode, sortMode)
                                    groups.forEach { (key, name, color, items) ->
                                        val collapsed = key in collapsedGroups
                                        item(key = "grp_$key") {
                                            GroupSectionHeader(
                                                name = name,
                                                count = items.size,
                                                color = color,
                                                collapsed = collapsed,
                                                onToggle = { toggleCollapse(key) },
                                                onViewFiles = onOpenFiles,
                                                onArchiveGroup = {
                                                    scope.launch { items.forEach { dao.setArchived(it.id, true) } }
                                                },
                                                onNewGroup = { newGroupDialog = true }
                                            )
                                        }
                                        if (!collapsed) {
                                            items(items, key = { it.id }) { conv ->
                                                TaskItem(conv, scope, dao,
                                                    onRename = { renameTarget = conv },
                                                    onDelete = { deleteTarget = conv },
                                                    onSelect = onSelectConversation
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (archived.isNotEmpty()) {
                        HorizontalDivider()
                        BottomEntry(icon = Icons.Outlined.Archive, label = "归档 · ${archived.size}") {
                            showArchived = true; query = ""
                        }
                    }
                    // ZCode 对齐：设置在侧边栏底部
                    HorizontalDivider()
                    BottomEntry(icon = Icons.Outlined.Tune, label = "设置") {
                        onOpenSettings()
                    }
                }
            }
        },
        content = content
    )

    renameTarget?.let { target ->
        RenameDialog(initial = target.title, onDismiss = { renameTarget = null }) { newTitle ->
            scope.launch { dao.touchConversation(target.id, newTitle, System.currentTimeMillis()) }
            renameTarget = null
        }
    }
    deleteTarget?.let { target ->
        DeleteDialog(title = target.title, onDismiss = { deleteTarget = null }) {
            scope.launch { dao.deleteConversation(target.id) }
            deleteTarget = null
        }
    }
    if (newGroupDialog) {
        NewGroupDialog(
            onDismiss = { newGroupDialog = false },
            onCreate = { name, color ->
                scope.launch {
                    dao.upsertTaskGroup(
                        TaskGroupEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name, color = color,
                            sortOrder = taskGroups.size
                        )
                    )
                }
                newGroupDialog = false
            }
        )
    }
}

private fun sortList(list: List<ConversationEntity>, mode: SortMode): List<ConversationEntity> =
    when (mode) {
        SortMode.UPDATED -> list.sortedByDescending { it.updatedAt }
        SortMode.CREATED -> list.sortedByDescending { it.createdAt }
    }

private data class DrawerGroup(val key: String, val name: String, val color: Color, val items: List<ConversationEntity>)

private fun buildGroups(
    list: List<ConversationEntity>,
    taskGroups: List<TaskGroupEntity>,
    viewMode: ViewMode,
    sortMode: SortMode = SortMode.UPDATED
): List<DrawerGroup> {
    return when (viewMode) {
        ViewMode.GROUPED -> {
            val grouped = list.filter { it.groupId.isNotBlank() }
                .groupBy { it.groupId }
                .mapNotNull { (gid, items) ->
                    val g = taskGroups.firstOrNull { it.id == gid } ?: return@mapNotNull null
                    DrawerGroup(g.id, g.name, groupColors[g.color] ?: Color.Gray, sortList(items, sortMode))
                }
            val ungrouped = list.filter { it.groupId.isBlank() }
                .groupBy { projectName(it.project) }
                .map { (name, items) -> DrawerGroup("proj_$name", name, Color.Transparent, sortList(items, sortMode)) }
            grouped + ungrouped
        }
        ViewMode.BY_PROJECT -> {
            list.groupBy { projectName(it.project) }
                .map { (name, items) -> DrawerGroup("proj_$name", name, Color.Transparent, sortList(items, sortMode)) }
                .sortedByDescending { it.items.maxOfOrNull { c -> c.updatedAt } ?: 0L }
        }
        else -> emptyList()
    }
}

private fun buildGroupKeys(list: List<ConversationEntity>, taskGroups: List<TaskGroupEntity>, viewMode: ViewMode): List<String> =
    buildGroups(list, taskGroups, viewMode).map { it.key }

/**
 * 侧边栏顶部工作区条（ZCode 对齐）。
 * Commit 1：显示当前工作区名 + 文件浏览入口。Commit 2 扩展为完整选择器（下拉切换/新建）。
 */
@Composable
private fun WorkspaceStrip(name: String, onOpenFiles: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        IconButton(onClick = onOpenFiles) {
            Icon(Icons.Outlined.Folder, "查看工作区文件")
        }
    }
}

private fun projectName(project: String): String =
    project.trimEnd('/').substringAfterLast('/').ifBlank { "默认" }
