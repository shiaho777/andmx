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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.plugins.PluginSystem
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.mcp.McpManager
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.EmptyState
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class McpView {
    data object List : McpView()
    data class Edit(val entry: McpEntry?) : McpView()
}

private sealed class ConnState {
    data object Idle : ConnState()
    data object Testing : ConnState()
    data class Ok(val tools: Int) : ConnState()
    data class Fail(val reason: String) : ConnState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val settings by store.settings.collectAsState(initial = ProviderSettings())
    val scope = rememberCoroutineScope()
    val entries = remember(settings.mcpServers) { McpEntry.parse(settings.mcpServers) }
    var view by remember { mutableStateOf<McpView>(McpView.List) }

    fun persist(list: List<McpEntry>) {
        scope.launch { store.update(settings.copy(mcpServers = list.joinToString("\n") { it.toLine() })) }
    }

    AnimatedContent(
        targetState = view,
        transitionSpec = {
            if (targetState is McpView.Edit) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "mcpView"
    ) { target ->
        when (target) {
            is McpView.List -> McpListView(
                entries = entries,
                onBack = onBack,
                onAdd = { view = McpView.Edit(null) },
                onEdit = { view = McpView.Edit(it) }
            )
            is McpView.Edit -> McpEditPage(
                initial = target.entry,
                onBack = { view = McpView.List },
                onSave = { entry ->
                    val others = entries.filterNot { it.name == target.entry?.name }
                    persist(others + entry)
                    view = McpView.List
                },
                onDelete = target.entry?.let { e ->
                    {
                        persist(entries.filterNot { it.name == e.name })
                        view = McpView.List
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpListView(
    entries: List<McpEntry>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (McpEntry) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connStates = remember { mutableStateMapOf<String, ConnState>() }
    var query by remember { mutableStateOf("") }
    var pluginEntries by remember { mutableStateOf<List<PluginSystem.PluginMcpEntry>>(emptyList()) }
    var loadingPlugins by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loadingPlugins = true
        pluginEntries = withContext(Dispatchers.IO) {
            val fs = GuestFs(ProotRuntime(context))
            val system = PluginSystem(context, fs)
            runCatching { com.andmx.agent.plugins.BuiltinPluginSeeder.ensureSeeded(context, fs) }
            system.listPluginMcpEntries()
        }
        loadingPlugins = false
    }

    val q = query.trim()
    val localFiltered = remember(entries, q) {
        if (q.isEmpty()) entries
        else entries.filter {
            it.name.contains(q, true) || it.target.contains(q, true)
        }
    }
    val pluginFiltered = remember(pluginEntries, q) {
        if (q.isEmpty()) pluginEntries
        else pluginEntries.filter {
            it.name.contains(q, true) ||
                it.pluginName.contains(q, true) ||
                it.marketplace.contains(q, true) ||
                it.description.contains(q, true)
        }
    }
    val totalVisible = localFiltered.size + pluginFiltered.size

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { backAppBar("MCP 服务器", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, "新建 MCP 服务器")
            }
        }
    ) { padding ->
        if (!loadingPlugins && entries.isEmpty() && pluginEntries.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                McpHeader()
                EmptyState(
                    icon = Icons.Outlined.Cloud,
                    title = "还没有 MCP 服务器",
                    message = "添加一个 MCP 服务器，让 Agent 获得额外能力。",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { McpHeader() }
                item {
                    McpSearchField(
                        value = query,
                        onValueChange = { query = it },
                    )
                }
                if (loadingPlugins && pluginEntries.isEmpty() && entries.isEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (localFiltered.isNotEmpty() || (entries.isEmpty() && pluginFiltered.isEmpty() && !loadingPlugins && q.isEmpty())) {
                    item {
                        McpSectionHeader(
                            title = "已配置 MCP 服务器",
                            count = localFiltered.size,
                        )
                    }
                    if (localFiltered.isEmpty() && q.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        "还没有 MCP 服务器",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "添加一个 MCP 服务器，让 Agent 获得额外能力。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    items(localFiltered, key = { "local:${it.name}" }) { entry ->
                        McpCard(
                            entry = entry,
                            state = connStates[entry.name] ?: ConnState.Idle,
                            onEdit = { onEdit(entry) },
                            onTest = {
                                connStates[entry.name] = ConnState.Testing
                                scope.launch {
                                    val result = runCatching {
                                        val mgr = McpManager(context)
                                        mgr.connectAll(entry.toLine())
                                        mgr.connected.firstOrNull { it.name == entry.name }
                                    }.getOrNull()
                                    connStates[entry.name] = if (result != null)
                                        ConnState.Ok(result.tools.size)
                                    else ConnState.Fail("连接失败")
                                }
                            }
                        )
                    }
                }
                if (pluginFiltered.isNotEmpty()) {
                    item {
                        McpSectionHeader(
                            title = "Plugin MCP 服务器",
                            count = pluginFiltered.size,
                            hint = "由插件注册，修改请到对应插件中进行。",
                        )
                    }
                    items(pluginFiltered, key = { "plugin:${it.id}" }) { entry ->
                        PluginMcpCard(entry)
                    }
                }
                if (q.isNotEmpty() && totalVisible == 0 && !loadingPlugins) {
                    item {
                        Text(
                            "未找到匹配的 MCP 服务器",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpHeader() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "管理 AndMX Agent 使用的 MCP 服务器配置。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun McpSearchField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .border(1.dp, border, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    "搜索 MCP 服务器…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun McpSectionHeader(
    title: String,
    count: Int,
    hint: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun McpCard(
    entry: McpEntry,
    state: ConnState,
    onEdit: () -> Unit,
    onTest: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .clickable(onClick = onEdit),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        shape = shape,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (entry.isRemote) Icons.Outlined.Cloud else Icons.Outlined.Terminal,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(if (entry.isRemote) "HTTP / SSE" else "STDIO")
                when (state) {
                    is ConnState.Testing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    is ConnState.Ok -> StatusPill("已连接 · ${state.tools} 个工具")
                    is ConnState.Fail -> StatusPill(state.reason, error = true)
                    ConnState.Idle -> TextButton(onClick = onTest) { Text("测试连接") }
                }
            }
        }
    }
}

@Composable
private fun PluginMcpCard(entry: PluginSystem.PluginMcpEntry) {
    val shape = RoundedCornerShape(12.dp)
    val (label, description) = pluginMcpStatus(entry)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        shape = shape,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Extension,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    StatusPill(entry.pluginName)
                    StatusPill(label)
                    entry.toolCount?.let { StatusPill("$it 个工具") }
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (entry.marketplace.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.marketplace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun pluginMcpStatus(entry: PluginSystem.PluginMcpEntry): Pair<String, String> {
    return when {
        entry.status == "error" && !entry.error.isNullOrBlank() ->
            "未加载" to entry.error
        entry.active ->
            "插件内置" to "该 MCP 服务器由已启用插件提供，配置跟随插件管理。"
        entry.pluginEnabled ->
            "未加载" to (entry.error ?: "插件声明了该 MCP 服务器，但当前未成功加载，请查看插件诊断。")
        else ->
            "插件未启用" to "该 MCP 服务器内置在插件中，启用插件后会加载。"
    }
}

@Composable
private fun StatusPill(text: String, error: Boolean = false) {
    val bg = if (error) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val fg = if (error) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
