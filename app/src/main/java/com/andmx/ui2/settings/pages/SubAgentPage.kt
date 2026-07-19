package com.andmx.ui2.settings.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import java.io.File
import androidx.compose.material3.ListItem
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.multi.SubagentCatalog
import com.andmx.agent.multi.SubagentModelCatalog
import com.andmx.agent.multi.SubagentModelOption
import com.andmx.agent.multi.SubagentStorage
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.CustomSubAgent
import com.andmx.settings.ProviderStore
import com.andmx.settings.SettingsStore
import com.andmx.settings.SubagentStateFile
import com.andmx.ui2.settings.ResourceCardGroup
import com.andmx.ui2.settings.ResourceFilterDropdown
import com.andmx.ui2.settings.ResourceFooterCount
import com.andmx.ui2.settings.ResourcePageDescription
import com.andmx.ui2.settings.ResourceRowDivider
import com.andmx.ui2.settings.ResourceSearchField
import com.andmx.ui2.settings.ResourceSectionHeader
import com.andmx.ui2.settings.ResourceToolbarRow
import com.andmx.ui2.settings.settingsTopBarColors
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

private sealed class AgentView {
    data object List : AgentView()
    data class Edit(val agent: CustomSubAgent?, val builtIn: Boolean) : AgentView()
}

private enum class AgentFilter(val label: String) {
    All("全部"),
    Enabled("已启用"),
    Disabled("已停用"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val providerStore = remember { ProviderStore(context) }
    val userAgents by store.customSubAgents.collectAsState(initial = emptyList())
    val state by store.subagentState.collectAsState(initial = SubagentStateFile())
    val settings by store.settings.collectAsState(initial = null)
    val primary by providerStore.primary.collectAsState(initial = null)
    val providers by providerStore.providers.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<AgentView>(AgentView.List) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<CustomSubAgent?>(null) }
    var showFolderSheet by remember { mutableStateOf(false) }
    var folderFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var folderPath by remember { mutableStateOf("") }

    val allAgents = remember(userAgents, state, refreshTick) {
        SubagentCatalog.listAll(userAgents, state)
    }

    val modelOptions = remember(providers, primary, settings) {
        SubagentModelCatalog.buildOptions(
            providers = providers,
            activeProviderId = primary?.id ?: settings?.activeProviderId.orEmpty(),
            activeModel = settings?.model.orEmpty(),
        )
    }

    LaunchedEffect(userAgents) {
        runCatching { SubagentStorage.syncUserAgents(context, userAgents) }
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除子智能体") },
            text = { Text("确定要删除子智能体「${target.name}」吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val users = store.customSubAgents.firstOrNull().orEmpty()
                                .filterNot { it.id == target.id }
                            store.saveSubAgents(users)
                            SubagentStorage.syncUserAgents(context, users)
                            val path = target.path.trim()
                            if (path.isNotBlank() && !path.startsWith("built-in:")) {
                                runCatching { java.io.File(path).delete() }
                            }
                            deleteTarget = null
                        }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    if (showFolderSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFolderSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                Text(
                    "用户子智能体目录",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    folderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            SubagentStorage.openAgentsFolder(context)
                        }
                    ) { Text("选择打开方式") }
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("agents", folderPath)
                            )
                            android.widget.Toast.makeText(context, "路径已复制", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("复制路径") }
                }
                if (folderFiles.isEmpty()) {
                    Text(
                        "目录为空",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    folderFiles.forEach { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = {
                                Text(
                                    if (file.length() < 1024) "${file.length()} B"
                                    else "${file.length() / 1024} KB"
                                )
                            },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null)
                            },
                            modifier = Modifier.clickable {
                                SubagentStorage.openAgentFile(context, file)
                            },
                        )
                    }
                }
            }
        }
    }

    AnimatedContent(
        targetState = view,

        transitionSpec = {
            if (targetState is AgentView.Edit) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "agentView"
    ) { target ->
        when (target) {
            is AgentView.List -> AgentListView(
                agents = allAgents,
                modelOptions = modelOptions,
                onBack = onBack,
                onAdd = { view = AgentView.Edit(null, builtIn = false) },
                onOpenFolder = {
                    scope.launch {
                        SubagentStorage.syncUserAgents(
                            context,
                            store.customSubAgents.firstOrNull().orEmpty(),
                        )
                        folderPath = SubagentStorage.agentsDir(context).absolutePath
                        folderFiles = SubagentStorage.listAgentFiles(context)
                        showFolderSheet = true
                    }
                },
                onRefresh = {
                    scope.launch {
                        val disk = SubagentStorage.loadMarkdownAgents(context)
                        if (disk.isNotEmpty()) {
                            val existing = store.customSubAgents.firstOrNull().orEmpty()
                                .filter { it.scope != "built-in" && it.source != "built-in" }
                            val byName = existing.associateBy { it.name.lowercase() }.toMutableMap()
                            disk.forEach { loaded ->
                                val prev = byName[loaded.name.lowercase()]
                                byName[loaded.name.lowercase()] = loaded.copy(
                                    id = prev?.id ?: loaded.id,
                                    enabled = prev?.enabled ?: true,
                                )
                            }
                            store.saveSubAgents(byName.values.toList())
                        }
                        refreshTick++
                    }
                },
                onEditUser = { view = AgentView.Edit(it, builtIn = false) },
                onDeleteUser = { deleteTarget = it },
                onToggleUser = { agent, enabled ->
                    scope.launch {
                        val users = store.customSubAgents.firstOrNull().orEmpty()
                        store.saveSubAgents(
                            users.map { if (it.id == agent.id) it.copy(enabled = enabled) else it }
                        )
                    }
                },
                onBuiltInModel = { agent, model ->
                    scope.launch {
                        val cur = store.subagentState.firstOrNull() ?: SubagentStateFile()
                        val overrides = cur.builtInModelOverrides.toMutableMap()
                        val m = model.trim()
                        if (m.isBlank() || m == "inherit") overrides.remove(agent.name)
                        else overrides[agent.name] = m
                        store.saveSubagentState(cur.copy(builtInModelOverrides = overrides))
                    }
                },
            )
            is AgentView.Edit -> SubAgentEditPage(
                initial = target.agent,
                builtIn = target.builtIn,
                modelOptions = modelOptions,
                onBack = { view = AgentView.List },
                onSave = { agent ->
                    scope.launch {
                        if (target.builtIn) {
                            val cur = store.subagentState.firstOrNull() ?: SubagentStateFile()
                            val overrides = cur.builtInModelOverrides.toMutableMap()
                            val model = agent.model.trim()
                            if (model.isBlank() || model == "inherit") overrides.remove(agent.name)
                            else overrides[agent.name] = model
                            store.saveSubagentState(cur.copy(builtInModelOverrides = overrides))
                        } else {
                            val users = store.customSubAgents.firstOrNull().orEmpty()
                                .filter { it.scope != "built-in" && it.source != "built-in" }
                            val cleaned = agent.copy(
                                name = agent.name.trim(),
                                description = agent.description.trim(),
                                systemPrompt = agent.systemPrompt.trim(),
                                scope = "user",
                                source = "user",
                                readOnly = false,
                                path = agent.path.ifBlank {
                                    SubagentStorage.agentsDir(context)
                                        .resolve("${agent.name.trim().lowercase()}.md")
                                        .absolutePath
                                },
                            )
                            val others = users.filterNot {
                                it.id == cleaned.id || it.name.equals(cleaned.name, true)
                            }
                            val next = others + cleaned
                            store.saveSubAgents(next)
                            SubagentStorage.syncUserAgents(context, next)
                        }
                    }
                    view = AgentView.List
                },
                onDelete = if (!target.builtIn && target.agent != null) {
                    {
                        deleteTarget = target.agent
                        view = AgentView.List
                    }
                } else null
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListView(
    agents: List<CustomSubAgent>,
    modelOptions: List<SubagentModelOption>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenFolder: () -> Unit,
    onRefresh: () -> Unit,
    onEditUser: (CustomSubAgent) -> Unit,
    onDeleteUser: (CustomSubAgent) -> Unit,
    onToggleUser: (CustomSubAgent, Boolean) -> Unit,
    onBuiltInModel: (CustomSubAgent, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AgentFilter.All) }

    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val subtlest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)

    val filtered = agents.filter { agent ->
        val q = query.isBlank() || listOf(
            agent.name, agent.description, agent.model, agent.path, agent.scope, agent.source,
            agent.tools.joinToString(" "),
        ).joinToString(" ").contains(query, ignoreCase = true)
        val f = when (filter) {
            AgentFilter.All -> true
            AgentFilter.Enabled -> agent.enabled
            AgentFilter.Disabled -> !agent.enabled
        }
        q && f
    }
    val users = filtered.filter { isUserAgent(it) }
    val plugins = filtered.filter { it.source == "plugin" }
    val builtIns = filtered.filter { isBuiltInAgent(it) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("子智能体") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Outlined.Add, "新建子智能体")
                    }
                    IconButton(onClick = onOpenFolder) {
                        Icon(Icons.Outlined.FolderOpen, "打开用户子智能体目录")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, "刷新")
                    }
                },
                colors = settingsTopBarColors(),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ResourcePageDescription("管理 AndMX Agent 运行时消费的用户级子智能体 Markdown 文件。")
            }

            item {
                ResourceToolbarRow(
                    search = {
                        ResourceSearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "搜索子智能体...",
                        )
                    },
                    filter = {
                        ResourceFilterDropdown(
                            label = "全部",
                            options = AgentFilter.entries.map { it.label },
                            selected = filter.label,
                            onSelect = { label ->
                                filter = AgentFilter.entries.first { it.label == label }
                            },
                        )
                    },
                )
            }

            if (filtered.isEmpty()) {
                item {
                    ResourceCardGroup {
                        Text(
                            "没有找到子智能体",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }

            if (users.isNotEmpty()) {
                item {
                    ResourceSectionHeader(title = "用户子智能体", count = users.size)
                }
                item {
                    ResourceCardGroup {
                        users.forEachIndexed { index, agent ->
                            if (index > 0) ResourceRowDivider()
                            AgentRow(
                                agent = agent,
                                modelOptions = modelOptions,
                                subtle = subtle,
                                subtlest = subtlest,
                                onBuiltInModel = onBuiltInModel,
                                onToggle = { onToggleUser(agent, it) },
                                onEdit = { onEditUser(agent) },
                                onDelete = { onDeleteUser(agent) },
                            )
                        }
                    }
                }
            }

            if (plugins.isNotEmpty()) {
                item {
                    ResourceSectionHeader(
                        title = "插件子智能体",
                        count = plugins.size,
                        hint = "由插件注册，修改请到对应插件中进行。",
                    )
                }
                item {
                    ResourceCardGroup {
                        plugins.forEachIndexed { index, agent ->
                            if (index > 0) ResourceRowDivider()
                            AgentRow(
                                agent = agent,
                                modelOptions = modelOptions,
                                subtle = subtle,
                                subtlest = subtlest,
                                onBuiltInModel = onBuiltInModel,
                                onToggle = {},
                                onEdit = {},
                                onDelete = {},
                            )
                        }
                    }
                }
            }

            if (builtIns.isNotEmpty()) {
                item {
                    ResourceSectionHeader(
                        title = "内置子智能体",
                        count = builtIns.size,
                        hint = "内置 profile 是运行时默认能力，当前不可在这里编辑。",
                    )
                }
                item {
                    ResourceCardGroup {
                        builtIns.forEachIndexed { index, agent ->
                            if (index > 0) ResourceRowDivider()
                            AgentRow(
                                agent = agent,
                                modelOptions = modelOptions,
                                subtle = subtle,
                                subtlest = subtlest,
                                onBuiltInModel = onBuiltInModel,
                                onToggle = {},
                                onEdit = {},
                                onDelete = {},
                            )
                        }
                    }
                }
            }
            if (filtered.isNotEmpty()) {
                item {
                    ResourceFooterCount("共 ${filtered.size} 个子智能体")
                }
            }
        }
    }
}

@Composable
private fun AgentRow(
    agent: CustomSubAgent,
    modelOptions: List<SubagentModelOption>,
    subtle: Color,
    subtlest: Color,
    onBuiltInModel: (CustomSubAgent, String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val user = isUserAgent(agent)
    val builtIn = isBuiltInAgent(agent)
    val modelValue = if (SubagentModelCatalog.isInherit(agent.model)) {
        SubagentModelCatalog.INHERIT
    } else {
        agent.model
    }
    val toolsLabel = if (SubagentCatalog.isAllTools(agent.tools)) {
        "全部工具"
    } else {
        "${agent.tools.size} 个工具"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            if (agent.color.isNotBlank()) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 0.dp, bottom = 0.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(1.dp)
                        .clip(CircleShape)
                        .background(agentColor(agent.color))
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    agent.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ScopeBadge(scopeLabel(agent))
                if (!builtIn) {
                    ScopeBadge(SubagentModelCatalog.displayLabel(modelValue, modelOptions))
                }
                ScopeBadge(toolsLabel)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                agent.description.ifBlank { "暂无描述" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val path = agent.path.ifBlank {
                if (builtIn) "built-in:${agent.name}" else "user:${agent.name.lowercase()}.md"
            }
            if (path.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = subtlest,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (builtIn) {
                BuiltInModelPicker(
                    value = modelValue,
                    options = modelOptions,
                    subtle = subtle,
                    onSelect = { onBuiltInModel(agent, it) },
                )
            }
            if (user) {
                Switch(
                    checked = agent.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Edit, "编辑", Modifier.size(16.dp), tint = subtle)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, "删除", Modifier.size(16.dp), tint = subtle)
                }
            }
        }
    }
}

@Composable
private fun BuiltInModelPicker(
    value: String,
    options: List<SubagentModelOption>,
    subtle: Color,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = SubagentModelCatalog.displayLabel(value, options)
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            modifier = Modifier
                .height(32.dp)
                .widthIn(max = 168.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 128.dp),
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    null,
                    Modifier.size(14.dp),
                    tint = subtle,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var lastGroup = ""
            options.forEach { opt ->
                if (opt.group.isNotBlank() && opt.group != lastGroup) {
                    lastGroup = opt.group
                    DropdownMenuItem(
                        text = {
                            Text(
                                opt.group,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            opt.label,
                            color = if (opt.value == value || (SubagentModelCatalog.isInherit(value) && opt.value == SubagentModelCatalog.INHERIT))
                                MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        onSelect(opt.value)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SubagentModelSelectField(
    value: String,
    options: List<SubagentModelOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    SubagentModelCatalog.displayLabel(value, options),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp), tint = subtle)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var lastGroup = ""
            options.forEach { opt ->
                if (opt.group.isNotBlank() && opt.group != lastGroup) {
                    lastGroup = opt.group
                    DropdownMenuItem(
                        text = {
                            Text(
                                opt.group,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                }
                DropdownMenuItem(
                    text = { Text(opt.label) },
                    onClick = {
                        onSelect(opt.value)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ScopeBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun isBuiltInAgent(agent: CustomSubAgent): Boolean =
    agent.scope == "built-in" || agent.source == "built-in"

private fun isUserAgent(agent: CustomSubAgent): Boolean =
    agent.scope == "user" && agent.source == "user" && !agent.readOnly && !isBuiltInAgent(agent)

internal fun agentColor(id: String): Color = when (id) {
    "red" -> Color(0xFFF87171)
    "blue" -> Color(0xFF7DD3FC)
    "green" -> Color(0xFF6EE7B7)
    "yellow" -> Color(0xFFFDE047)
    "purple" -> Color(0xFFC4B5FD)
    "orange" -> Color(0xFFFDBA74)
    "pink" -> Color(0xFFF9A8D4)
    "cyan" -> Color(0xFF67E8F9)
    else -> Color(0xFFA3A3A3)
}

internal fun modelLabel(m: String): String = when (m) {
    "", "inherit", "defaultMain" -> "继承默认"
    "main" -> "主模型"
    "lite" -> "轻量模型"
    else -> m
}

internal fun permLabel(p: String): String = when (p) {
    "acceptEdits" -> "接受编辑"
    "plan" -> "计划模式"
    "auto" -> "自动"
    "bypassPermissions" -> "绕过权限"
    "dontAsk" -> "不询问"
    else -> "默认权限"
}

private fun scopeLabel(agent: CustomSubAgent): String = when {
    isBuiltInAgent(agent) -> "内置"
    agent.source == "plugin" -> "插件"
    agent.scope == "workspace" -> "工作区"
    else -> "用户"
}
