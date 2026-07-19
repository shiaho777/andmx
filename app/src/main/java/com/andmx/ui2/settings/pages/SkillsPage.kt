package com.andmx.ui2.settings.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.plugins.BuiltinPluginSeeder
import com.andmx.agent.plugins.PluginSystem
import com.andmx.agent.plugins.SkillInstaller
import com.andmx.exec.PersistentShell
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.ui2.chat.ChatComposerBus
import com.andmx.ui2.settings.EmptyState
import com.andmx.ui2.settings.ResourceBadge
import com.andmx.ui2.settings.ResourceCardGroup
import com.andmx.ui2.settings.ResourceFilterDropdown
import com.andmx.ui2.settings.ResourceFooterCount
import com.andmx.ui2.settings.ResourceListRow
import com.andmx.ui2.settings.ResourcePageDescription
import com.andmx.ui2.settings.ResourceRowDivider
import com.andmx.ui2.settings.ResourceSearchField
import com.andmx.ui2.settings.ResourceSectionHeader
import com.andmx.ui2.settings.ResourceStatusBanner
import com.andmx.ui2.settings.ResourceToolbarRow
import com.andmx.ui2.settings.settingsTopBarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class SkillView {
    data object List : SkillView()
    data class Detail(val skill: SkillInstaller.InstalledSkill) : SkillView()
}

private enum class SkillSourceFilter(val label: String) {
    All("全部"),
    Local("工作区与个人"),
    Plugin("Plugin"),
}

private data class SkillsSnapshot(
    val local: List<SkillInstaller.InstalledSkill> = emptyList(),
    val plugin: List<PluginSystem.PluginSkillEntry> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsPage(onBack: () -> Unit, onCloseSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val runtime = remember { ProotRuntime(context) }
    val fs = remember { GuestFs(runtime) }
    val shell = remember { PersistentShell(context, runtime) }
    val installer = remember { SkillInstaller(fs, shell) }
    val pluginSystem = remember { PluginSystem(context, fs, shell) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<SkillView>(SkillView.List) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val snapshot by produceState(initialValue = SkillsSnapshot(), key1 = refresh) {
        refreshing = true
        value = withContext(Dispatchers.IO) {
            runCatching { BuiltinPluginSeeder.ensureSeeded(context, fs) }
            val local = runCatching { installer.listInstalled() }.getOrDefault(emptyList())
            val plugin = runCatching { pluginSystem.listPluginSkills(includeDisabled = true) }
                .getOrDefault(emptyList())
            SkillsSnapshot(local = local, plugin = plugin)
        }
        refreshing = false
    }

    AnimatedContent(
        targetState = view,
        transitionSpec = {
            if (targetState is SkillView.Detail) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "skillView"
    ) { target ->
        when (target) {
            is SkillView.List -> SkillListView(
                localSkills = snapshot.local,
                pluginSkills = snapshot.plugin,
                refreshing = refreshing,
                statusMessage = statusMessage,
                onDismissStatus = { statusMessage = null },
                onBack = onBack,
                onRefresh = { refresh++ },
                onOpenLocal = { view = SkillView.Detail(it) },
                onCreate = { name, description, withAgent ->
                    scope.launch {
                        val result = runCatching {
                            installer.createSkill(name, description)
                        }.getOrElse {
                            SkillInstaller.InstallResult(false, name, "", listOf(it.message ?: "创建失败"))
                        }
                        if (result.success) {
                            statusMessage = "已创建技能 «${result.skillName}»"
                            refresh++
                            if (withAgent) {
                                ChatComposerBus.insertSkill("skill-creator")
                                onCloseSettings?.invoke()
                            }
                        } else {
                            statusMessage = result.errors.firstOrNull() ?: "创建失败"
                        }
                    }
                },
                onInstallGit = { url ->
                    scope.launch {
                        val result = runCatching { installer.installFromGit(url) }.getOrElse {
                            SkillInstaller.InstallResult(false, "", "", listOf(it.message ?: "安装失败"))
                        }
                        statusMessage = if (result.success) {
                            "已安装 «${result.skillName}»"
                        } else {
                            result.errors.firstOrNull() ?: "安装失败"
                        }
                        refresh++
                    }
                },
                onCreateWithAgent = {
                    ChatComposerBus.insertSkill("skill-creator")
                    onCloseSettings?.invoke()
                },
            )
            is SkillView.Detail -> SkillDetailPage(
                skill = target.skill,
                onBack = { view = SkillView.List },
                onDelete = {
                    scope.launch {
                        runCatching { installer.uninstall(target.skill.name) }
                        refresh++
                    }
                    view = SkillView.List
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillListView(
    localSkills: List<SkillInstaller.InstalledSkill>,
    pluginSkills: List<PluginSystem.PluginSkillEntry>,
    refreshing: Boolean,
    statusMessage: String?,
    onDismissStatus: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLocal: (SkillInstaller.InstalledSkill) -> Unit,
    onCreate: (name: String, description: String, withAgent: Boolean) -> Unit,
    onInstallGit: (String) -> Unit,
    onCreateWithAgent: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf(SkillSourceFilter.All) }
    var menuOpen by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showGit by remember { mutableStateOf(false) }

    val q = query.trim()
    val localFiltered = remember(localSkills, q, sourceFilter) {
        if (sourceFilter == SkillSourceFilter.Plugin) emptyList()
        else localSkills.filter {
            q.isBlank() ||
                it.name.contains(q, true) ||
                it.description.contains(q, true)
        }
    }
    val pluginFiltered = remember(pluginSkills, q, sourceFilter) {
        if (sourceFilter == SkillSourceFilter.Local) emptyList()
        else pluginSkills.filter {
            q.isBlank() ||
                it.name.contains(q, true) ||
                it.description.contains(q, true) ||
                it.pluginName.contains(q, true)
        }
    }
    val total = localFiltered.size + pluginFiltered.size
    val emptyAll = localSkills.isEmpty() && pluginSkills.isEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("技能") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Outlined.Add, "新建技能")
                    }
                    IconButton(onClick = { showGit = true }) {
                        Icon(Icons.Outlined.CloudDownload, "从 Git 安装")
                    }
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, "刷新")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("新建技能") },
                            onClick = {
                                menuOpen = false
                                showCreate = true
                            },
                            leadingIcon = { Icon(Icons.Outlined.Add, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("用 Agent 创建技能") },
                            onClick = {
                                menuOpen = false
                                onCreateWithAgent()
                            },
                            leadingIcon = { Icon(Icons.Outlined.Bolt, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("从 Git 安装") },
                            onClick = {
                                menuOpen = false
                                showGit = true
                            },
                            leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) }
                        )
                    }
                },
                colors = settingsTopBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Outlined.Add, "新建技能")
            }
        }
    ) { padding ->
        if (!refreshing && emptyAll) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                statusMessage?.let { ResourceStatusBanner(it, onDismissStatus) }
                ResourcePageDescription(
                    "管理项目级与用户级技能。启用后可在聊天里通过 \$skill-name 使用。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                EmptyState(
                    icon = Icons.Outlined.Bolt,
                    title = "没有可用技能",
                    message = "可新建技能，或从 Git 安装。聊天中通过 \$skill-name 使用。",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                statusMessage?.let {
                    item { ResourceStatusBanner(it, onDismissStatus) }
                }
                item {
                    ResourcePageDescription("管理项目级与用户级技能。启用后可在聊天里通过 \$skill-name 使用。")
                }
                item {
                    ResourceToolbarRow(
                        search = {
                            ResourceSearchField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = "搜索技能...",
                            )
                        },
                        filter = {
                            ResourceFilterDropdown(
                                label = "全部",
                                options = SkillSourceFilter.entries.map { it.label },
                                selected = sourceFilter.label,
                                onSelect = { label ->
                                    sourceFilter = SkillSourceFilter.entries.first { it.label == label }
                                },
                            )
                        },
                    )
                }
                if (refreshing && emptyAll) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (localFiltered.isNotEmpty()) {
                    item {
                        ResourceSectionHeader(
                            title = "工作区与个人技能",
                            count = localFiltered.size,
                        )
                    }
                    item {
                        ResourceCardGroup {
                            localFiltered.forEachIndexed { index, skill ->
                                if (index > 0) ResourceRowDivider()
                                ResourceListRow(
                                    icon = Icons.Outlined.Bolt,
                                    title = skill.name,
                                    subtitle = skill.description.ifBlank { "暂无描述" },
                                    onClick = { onOpenLocal(skill) },
                                    trailing = { ResourceBadge("用户") },
                                )
                            }
                        }
                    }
                }
                if (pluginFiltered.isNotEmpty()) {
                    item {
                        ResourceSectionHeader(
                            title = "Plugin 技能",
                            count = pluginFiltered.size,
                            hint = "由插件注册，修改请到对应插件中进行。",
                        )
                    }
                    item {
                        ResourceCardGroup {
                            pluginFiltered.forEachIndexed { index, skill ->
                                if (index > 0) ResourceRowDivider()
                                ResourceListRow(
                                    icon = Icons.Outlined.Extension,
                                    title = skill.name,
                                    subtitle = skill.description.ifBlank { "暂无描述" },
                                    trailing = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                skill.pluginName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(end = 8.dp).width(96.dp),
                                            )
                                            ResourceBadge("Plugin")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                if (!refreshing && q.isNotEmpty() && total == 0) {
                    item {
                        Text(
                            "未找到匹配的技能",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                if (!refreshing && total > 0) {
                    item { ResourceFooterCount("共 $total 个技能") }
                }
            }
        }
    }

    if (showCreate) {
        CreateSkillDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, desc, withAgent ->
                showCreate = false
                onCreate(name, desc, withAgent)
            }
        )
    }
    if (showGit) {
        InstallDialog(
            onDismiss = { showGit = false },
            onConfirm = { url ->
                showGit = false
                onInstallGit(url)
            }
        )
    }
}

@Composable
private fun CreateSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, withAgent: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建技能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "创建本地技能目录并生成 SKILL.md。名称使用小写、数字与连字符。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("my-skill") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述 / 触发场景") },
                    placeholder = { Text("何时应使用该技能…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { onConfirm(name.trim(), description.trim(), true) },
                    enabled = name.isNotBlank()
                ) { Text("创建并用 Agent 完善") }
                TextButton(
                    onClick = { onConfirm(name.trim(), description.trim(), false) },
                    enabled = name.isNotBlank()
                ) { Text("创建") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun InstallDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 Git 安装技能") },
        text = {
            Column {
                Text(
                    "输入包含 SKILL.md 的 Git 仓库地址，将克隆到技能目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://github.com/user/skill.git") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim()) }, enabled = url.isNotBlank()) {
                Text("安装")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
