package com.andmx.ui2.drawer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.data.AndmxDatabase
import com.andmx.data.ConversationEntity
import com.andmx.data.TaskGroupEntity
import com.andmx.ui2.files.WorkspaceFileTree
import com.andmx.ui2.chat.ChatActionBus
import com.andmx.workspace.ProjectManager
import kotlinx.coroutines.launch

private const val VISIBLE_LIMIT = 20
private const val DRAWER_PREFS = "andmx_drawer"
private const val KEY_VIEW_MODE = "view_mode"
private const val KEY_SORT_MODE = "sort_mode"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onNewConversation: () -> Unit = {},
    onOpenFiles: (projectPath: String?) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    fileTreeRequestPath: String? = null,
    fileTreeRequestKey: Int = 0,
    currentConversationId: Long = 0L,
    streamingConversationIds: Set<Long> = emptySet(),
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(if (open) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dao = remember { AndmxDatabase.get(context).dao() }
    val conversations by dao.observeConversations().collectAsState(initial = emptyList())
    val archived by dao.observeArchived().collectAsState(initial = emptyList())
    val taskGroups by dao.observeTaskGroups().collectAsState(initial = emptyList())

    val drawerPrefs = remember {
        context.getSharedPreferences(DRAWER_PREFS, Context.MODE_PRIVATE)
    }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var viewMode by remember {
        mutableStateOf(
            runCatching {
                ViewMode.valueOf(
                    drawerPrefs.getString(KEY_VIEW_MODE, ViewMode.BY_PROJECT.name)
                        ?: ViewMode.BY_PROJECT.name
                )
            }.getOrDefault(ViewMode.BY_PROJECT)
        )
    }
    var sortMode by remember {
        mutableStateOf(
            runCatching {
                SortMode.valueOf(
                    drawerPrefs.getString(KEY_SORT_MODE, SortMode.UPDATED.name)
                        ?: SortMode.UPDATED.name
                )
            }.getOrDefault(SortMode.UPDATED)
        )
    }
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    var showArchived by remember { mutableStateOf(false) }
    var showAllPinned by remember { mutableStateOf(false) }
    var showAllArchived by remember { mutableStateOf(false) }
    var expandedSections by remember { mutableStateOf(setOf<String>()) }

    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteArchived by remember { mutableStateOf(false) }
    var archiveTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var createGroupOpen by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var fileTreeOpen by remember { mutableStateOf(false) }
    var fileTreePath by remember { mutableStateOf<String?>(null) }

    fun openFileTree(path: String? = null) {
        fileTreePath = path
        fileTreeOpen = true
        showArchived = false
        searchOpen = false
        query = ""
    }

    fun closeFileTree() {
        fileTreeOpen = false
        fileTreePath = null
    }

    LaunchedEffect(fileTreeRequestKey) {
        if (fileTreeRequestKey > 0) {
            openFileTree(fileTreeRequestPath)
            if (drawerState.isClosed) drawerState.open()
        }
    }

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

    fun expandAll() {
        collapsedGroups = emptySet()
    }

    fun collapseAll(keys: List<String>) {
        collapsedGroups = keys.toSet()
    }

    fun closeAnd(action: () -> Unit) {
        action()
        scope.launch {
            drawerState.close()
            onDismiss()
        }
    }

    fun newTask() {
        scope.launch {
            val empty = conversations.firstOrNull { dao.messageCount(it.id) == 0 }
            if (empty != null) {
                closeAnd { onSelectConversation(empty.id) }
            } else {
                closeAnd { onNewConversation() }
            }
        }
    }

    val groupKeys = remember(conversations, taskGroups, viewMode) {
        buildGroupKeys(conversations, taskGroups, viewMode)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = open || drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RectangleShape,
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier.fillMaxWidth(0.5f),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    if (fileTreeOpen) {
                        WorkspaceFileTree(
                            initialPath = fileTreePath,
                            onBackToTasks = { closeFileTree() },
                            onOpenFile = { path ->
                                closeAnd {
                                    ChatActionBus.openFile(path)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                        )
                    } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .padding(top = 12.dp, bottom = 6.dp),
                    ) {
                        if (!showArchived) {
                            NewTaskButton(onClick = ::newTask)
                            Spacer(Modifier.height(2.dp))
                            DrawerQuickAction(
                                icon = Icons.Outlined.Search,
                                label = "搜索",
                                trailing = "K",
                                onClick = { closeAnd(onOpenSearch) },
                            )
                            DrawerQuickAction(
                                icon = Icons.Outlined.AutoAwesome,
                                label = "技能",
                                onClick = { closeAnd(onOpenSkills) },
                            )
                        }
                    }

                    TaskToolbar(
                        viewMode = viewMode,
                        sortMode = sortMode,
                        onViewChange = { mode ->
                            viewMode = mode
                            drawerPrefs.edit().putString(KEY_VIEW_MODE, mode.name).apply()
                            if (mode == ViewMode.TIMELINE) {
                                searchOpen = false
                            }
                        },
                        onSortChange = {
                            sortMode = it
                            drawerPrefs.edit().putString(KEY_SORT_MODE, it.name).apply()
                        },
                        allExpanded = collapsedGroups.isEmpty(),
                        canToggleExpand = !showArchived && viewMode != ViewMode.TIMELINE,
                        onToggleExpandAll = {
                            if (collapsedGroups.isEmpty()) collapseAll(groupKeys) else expandAll()
                        },
                        showArchived = showArchived,
                        onToggleArchived = {
                            showArchived = !showArchived
                            searchOpen = showArchived
                            query = ""
                            showAllArchived = false
                        },
                        searchOpen = searchOpen,
                        onToggleSearch = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        },
                        onNewGroup = if (!showArchived && viewMode == ViewMode.GROUPED) {
                            { createGroupOpen = true }
                        } else {
                            null
                        },
                    )

                    if (searchOpen || showArchived) {
                        SearchField(
                            query = query,
                            archived = showArchived,
                            onQueryChange = { query = it },
                            onClose = if (!showArchived) {
                                {
                                    searchOpen = false
                                    query = ""
                                }
                            } else {
                                null
                            },
                        )
                        Spacer(Modifier.height(2.dp))
                    }

                    val source = if (showArchived) archived else conversations
                    val filtered = remember(source, query) {
                        if (query.isBlank()) {
                            source
                        } else {
                            source.filter {
                                it.title.contains(query, true) ||
                                    it.project.contains(query, true) ||
                                    it.firstUserMessage.contains(query, true)
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            DrawerEmpty(searching = query.isNotBlank(), archived = showArchived)
                        }
                    } else if (showArchived) {
                        val visible = if (showAllArchived || filtered.size <= VISIBLE_LIMIT) {
                            filtered
                        } else {
                            filtered.take(VISIBLE_LIMIT)
                        }
                        LazyColumn(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                        ) {
                            items(visible, key = { it.id }) { conv ->
                                ConversationRow(
                                    conversation = conv,
                                    selected = conv.id == currentConversationId,
                                    sortMode = sortMode,
                                    compact = false,
                                    showWorkspaceMeta = true,
                                    archivedMode = true,
                                    streaming = conv.id in streamingConversationIds,
                                    onClick = { closeAnd { onSelectConversation(conv.id) } },
                                    onRename = { renameTarget = conv },
                                    onDelete = {
                                        deleteArchived = true
                                        deleteTarget = conv
                                    },
                                    onArchive = {
                                        scope.launch { dao.setArchived(conv.id, false) }
                                    },
                                    onUnarchive = {
                                        scope.launch { dao.setArchived(conv.id, false) }
                                    },
                                    onTogglePin = {
                                        scope.launch { dao.setPinned(conv.id, !conv.pinned) }
                                    },
                                )
                            }
                            if (filtered.size > VISIBLE_LIMIT) {
                                item(key = "show_more_archived") {
                                    ShowMoreButton(
                                        expanded = showAllArchived,
                                        onClick = { showAllArchived = !showAllArchived },
                                    )
                                }
                            }
                        }
                    } else {
                        TaskList(
                            conversations = filtered,
                            taskGroups = taskGroups,
                            viewMode = viewMode,
                            sortMode = sortMode,
                            collapsedGroups = collapsedGroups,
                            currentConversationId = currentConversationId,
                            streamingConversationIds = streamingConversationIds,
                            showAllPinned = showAllPinned,
                            expandedSections = expandedSections,
                            onToggleShowAllPinned = { showAllPinned = !showAllPinned },
                            onToggleSectionLimit = { key ->
                                expandedSections = if (key in expandedSections) {
                                    expandedSections - key
                                } else {
                                    expandedSections + key
                                }
                            },
                            onToggleCollapse = ::toggleCollapse,
                            onSelect = { id -> closeAnd { onSelectConversation(id) } },
                            onRename = { renameTarget = it },
                            onDelete = {
                                deleteArchived = false
                                deleteTarget = it
                            },
                            onArchive = { archiveTarget = it },
                            onTogglePin = {
                                scope.launch { dao.setPinned(it.id, !it.pinned) }
                            },
                            onMoveToGroup = { moveTarget = it },
                            onCreateGroup = { createGroupOpen = true },
                            onOpenFiles = { project ->
                                openFileTree(project.ifBlank { null })
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    DrawerFooter(
                        onOpenSettings = { closeAnd(onOpenSettings) },
                    )
                    } // end tasks pane
                }
            }
        },
        content = content,
    )

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                scope.launch {
                    dao.touchConversation(target.id, title, System.currentTimeMillis())
                }
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { target ->
        DeleteDialog(
            title = target.title.ifBlank { "新任务" },
            archived = deleteArchived,
            onDismiss = {
                deleteTarget = null
                deleteArchived = false
            },
            onConfirm = {
                scope.launch { dao.deleteConversation(target.id) }
                deleteTarget = null
                deleteArchived = false
            },
        )
    }
    archiveTarget?.let { target ->
        ArchiveConfirmDialog(
            title = target.title.ifBlank { "新任务" },
            onDismiss = { archiveTarget = null },
            onConfirm = {
                scope.launch { dao.setArchived(target.id, true) }
                archiveTarget = null
            },
        )
    }
    if (createGroupOpen) {
        CreateGroupDialog(
            onDismiss = { createGroupOpen = false },
            onConfirm = { name, color ->
                scope.launch {
                    val id = "g_${System.currentTimeMillis()}"
                    val order = (taskGroups.maxOfOrNull { it.sortOrder } ?: 0) + 1
                    dao.upsertTaskGroup(
                        TaskGroupEntity(
                            id = id,
                            name = name,
                            color = color,
                            sortOrder = order,
                        ),
                    )
                }
                createGroupOpen = false
            },
        )
    }
    moveTarget?.let { target ->
        MoveToGroupDialog(
            groups = taskGroups,
            currentGroupId = target.groupId,
            onDismiss = { moveTarget = null },
            onSelect = { groupId ->
                scope.launch { dao.setConversationGroup(target.id, groupId) }
                moveTarget = null
            },
        )
    }
}

@Composable
private fun TaskList(
    conversations: List<ConversationEntity>,
    taskGroups: List<TaskGroupEntity>,
    viewMode: ViewMode,
    sortMode: SortMode,
    collapsedGroups: Set<String>,
    currentConversationId: Long,
    streamingConversationIds: Set<Long>,
    showAllPinned: Boolean,
    expandedSections: Set<String>,
    onToggleShowAllPinned: () -> Unit,
    onToggleSectionLimit: (String) -> Unit,
    onToggleCollapse: (String) -> Unit,
    onSelect: (Long) -> Unit,
    onRename: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    onArchive: (ConversationEntity) -> Unit,
    onTogglePin: (ConversationEntity) -> Unit,
    onMoveToGroup: (ConversationEntity) -> Unit,
    onCreateGroup: () -> Unit,
    onOpenFiles: (project: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pinned = conversations.filter { it.pinned }.sortedWith(conversationComparator(sortMode))
    val unpinned = conversations.filterNot { it.pinned }
    val showWorkspaceMeta = viewMode == ViewMode.TIMELINE
    val visiblePinned = if (showAllPinned || pinned.size <= VISIBLE_LIMIT) {
        pinned
    } else {
        pinned.take(VISIBLE_LIMIT)
    }

    LazyColumn(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .padding(top = 2.dp, bottom = 8.dp),
    ) {
        if (pinned.isNotEmpty()) {
            item(key = "sec_pinned") {
                SectionHeader(title = "已置顶")
            }
            items(visiblePinned, key = { "pin_${it.id}" }) { conv ->
                ConversationRow(
                    conversation = conv,
                    selected = conv.id == currentConversationId,
                    sortMode = sortMode,
                    compact = true,
                    showWorkspaceMeta = showWorkspaceMeta,
                    streaming = conv.id in streamingConversationIds,
                    onClick = { onSelect(conv.id) },
                    onRename = { onRename(conv) },
                    onDelete = { onDelete(conv) },
                    onArchive = { onArchive(conv) },
                    onTogglePin = { onTogglePin(conv) },
                    onMoveToGroup = { onMoveToGroup(conv) },
                )
            }
            if (pinned.size > VISIBLE_LIMIT) {
                item(key = "show_more_pinned") {
                    ShowMoreButton(
                        expanded = showAllPinned,
                        onClick = onToggleShowAllPinned,
                    )
                }
            }
        }

        when (viewMode) {
            ViewMode.TIMELINE -> {
                val groups = unpinned
                    .sortedWith(conversationComparator(sortMode))
                    .groupBy {
                        timelineBucket(
                            if (sortMode == SortMode.CREATED) it.createdAt else it.updatedAt,
                        )
                    }
                    .toList()
                    .sortedBy { it.first.ordinal }
                groups.forEach { (bucket, items) ->
                    val key = "tl_${bucket.name}"
                    val expanded = key in expandedSections
                    val visible = if (expanded || items.size <= VISIBLE_LIMIT) {
                        items
                    } else {
                        items.take(VISIBLE_LIMIT)
                    }
                    item(key = "h_$key") {
                        SectionHeader(
                            title = bucket.label,
                            collapsed = key in collapsedGroups,
                            onToggle = { onToggleCollapse(key) },
                        )
                    }
                    if (key !in collapsedGroups) {
                        items(visible, key = { it.id }) { conv ->
                            ConversationRow(
                                conversation = conv,
                                selected = conv.id == currentConversationId,
                                sortMode = sortMode,
                                compact = false,
                                showWorkspaceMeta = true,
                                streaming = conv.id in streamingConversationIds,
                                onClick = { onSelect(conv.id) },
                                onRename = { onRename(conv) },
                                onDelete = { onDelete(conv) },
                                onArchive = { onArchive(conv) },
                                onTogglePin = { onTogglePin(conv) },
                                onMoveToGroup = { onMoveToGroup(conv) },
                            )
                        }
                        if (items.size > VISIBLE_LIMIT) {
                            item(key = "more_$key") {
                                ShowMoreButton(
                                    expanded = expanded,
                                    onClick = { onToggleSectionLimit(key) },
                                )
                            }
                        }
                    }
                }
            }

            ViewMode.BY_PROJECT -> {
                val groups = unpinned
                    .groupBy { ProjectManager.displayName(it.project) }
                    .toList()
                    .sortedBy { it.first.lowercase() }
                groups.forEach { (name, items) ->
                    val key = "proj_$name"
                    val sorted = items.sortedWith(conversationComparator(sortMode))
                    val expanded = key in expandedSections
                    val visible = if (expanded || sorted.size <= VISIBLE_LIMIT) {
                        sorted
                    } else {
                        sorted.take(VISIBLE_LIMIT)
                    }
                    item(key = "h_$key") {
                        val sampleProject = sorted.firstOrNull()?.project.orEmpty()
                        SectionHeader(
                            title = name,
                            collapsed = key in collapsedGroups,
                            onToggle = { onToggleCollapse(key) },
                            trailing = {
                                ProjectSectionActions(
                                    onOpenFiles = { onOpenFiles(sampleProject) },
                                )
                            },
                        )
                    }
                    if (key !in collapsedGroups) {
                        items(visible, key = { it.id }) { conv ->
                            ConversationRow(
                                conversation = conv,
                                selected = conv.id == currentConversationId,
                                sortMode = sortMode,
                                compact = true,
                                showWorkspaceMeta = false,
                                streaming = conv.id in streamingConversationIds,
                                onClick = { onSelect(conv.id) },
                                onRename = { onRename(conv) },
                                onDelete = { onDelete(conv) },
                                onArchive = { onArchive(conv) },
                                onTogglePin = { onTogglePin(conv) },
                                onMoveToGroup = { onMoveToGroup(conv) },
                            )
                        }
                        if (sorted.size > VISIBLE_LIMIT) {
                            item(key = "more_$key") {
                                ShowMoreButton(
                                    expanded = expanded,
                                    onClick = { onToggleSectionLimit(key) },
                                )
                            }
                        }
                    }
                }
            }

            ViewMode.GROUPED -> {
                val groupMap = taskGroups.associateBy { it.id }
                val assigned = unpinned.groupBy { it.groupId.ifBlank { "" } }
                val orderedKeys = buildList {
                    taskGroups.sortedBy { it.sortOrder }.forEach { add(it.id) }
                    if (assigned[""].orEmpty().isNotEmpty() || assigned.keys.any { it !in groupMap }) {
                        add("")
                    }
                    assigned.keys.filter { it.isNotBlank() && it !in groupMap }.sorted().forEach { add(it) }
                }.distinct()

                orderedKeys.forEach { gid ->
                    val items = assigned[gid].orEmpty()
                    if (items.isEmpty()) return@forEach
                    val group = groupMap[gid]
                    val title = group?.name ?: if (gid.isBlank()) "最近任务" else gid
                    val color = group?.let { groupColors[it.color] } ?: Color.Transparent
                    val key = "grp_${gid.ifBlank { "recent" }}"
                    val sorted = items.sortedWith(conversationComparator(sortMode))
                    val expanded = key in expandedSections
                    val visible = if (expanded || sorted.size <= VISIBLE_LIMIT) {
                        sorted
                    } else {
                        sorted.take(VISIBLE_LIMIT)
                    }
                    item(key = "h_$key") {
                        val projectPath = sorted.firstOrNull()?.project.orEmpty()
                        SectionHeader(
                            title = title,
                            color = color,
                            collapsed = key in collapsedGroups,
                            onToggle = { onToggleCollapse(key) },
                            trailing = if (projectPath.isNotBlank()) {
                                {
                                    ProjectSectionActions(
                                        onOpenFiles = { onOpenFiles(projectPath) },
                                    )
                                }
                            } else null,
                        )
                    }
                    if (key !in collapsedGroups) {
                        items(visible, key = { it.id }) { conv ->
                            ConversationRow(
                                conversation = conv,
                                selected = conv.id == currentConversationId,
                                sortMode = sortMode,
                                compact = true,
                                showWorkspaceMeta = false,
                                streaming = conv.id in streamingConversationIds,
                                onClick = { onSelect(conv.id) },
                                onRename = { onRename(conv) },
                                onDelete = { onDelete(conv) },
                                onArchive = { onArchive(conv) },
                                onTogglePin = { onTogglePin(conv) },
                                onMoveToGroup = { onMoveToGroup(conv) },
                            )
                        }
                        if (sorted.size > VISIBLE_LIMIT) {
                            item(key = "more_$key") {
                                ShowMoreButton(
                                    expanded = expanded,
                                    onClick = { onToggleSectionLimit(key) },
                                )
                            }
                        }
                    }
                }

                item(key = "create_group") {
                    TextButton(
                        onClick = onCreateGroup,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text("＋ 新建分组", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun conversationComparator(sortMode: SortMode): Comparator<ConversationEntity> =
    when (sortMode) {
        SortMode.UPDATED -> compareByDescending<ConversationEntity> { it.updatedAt }
            .thenByDescending { it.createdAt }
        SortMode.CREATED -> compareByDescending<ConversationEntity> { it.createdAt }
            .thenByDescending { it.updatedAt }
    }

private fun buildGroupKeys(
    conversations: List<ConversationEntity>,
    taskGroups: List<TaskGroupEntity>,
    viewMode: ViewMode,
): List<String> {
    val unpinned = conversations.filterNot { it.pinned }
    return when (viewMode) {
        ViewMode.TIMELINE -> TimelineBucket.entries.map { "tl_${it.name}" }
        ViewMode.BY_PROJECT -> unpinned
            .map { "proj_${ProjectManager.displayName(it.project)}" }
            .distinct()
        ViewMode.GROUPED -> {
            val ids = taskGroups.map { "grp_${it.id}" }.toMutableList()
            if (unpinned.any { it.groupId.isBlank() }) ids += "grp_recent"
            unpinned.map { it.groupId }
                .filter { id -> id.isNotBlank() && taskGroups.none { it.id == id } }
                .distinct()
                .forEach { ids += "grp_$it" }
            ids
        }
    }
}
