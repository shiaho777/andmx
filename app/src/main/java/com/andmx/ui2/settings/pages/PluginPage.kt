package com.andmx.ui2.settings.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.plugins.BuiltinPluginSeeder
import com.andmx.agent.plugins.PluginMarketplace
import com.andmx.agent.plugins.PluginSystem
import com.andmx.exec.PersistentShell
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.ui2.settings.EmptyState
import com.andmx.ui2.settings.ResourceBadge
import com.andmx.ui2.settings.ResourceCardGroup
import com.andmx.ui2.settings.ResourceFooterCount
import com.andmx.ui2.settings.ResourceListRow
import com.andmx.ui2.settings.ResourcePageDescription
import com.andmx.ui2.settings.ResourceRowDivider
import com.andmx.ui2.settings.ResourceSearchField
import com.andmx.ui2.settings.ResourceSectionHeader
import com.andmx.ui2.settings.ResourceStatusBanner
import com.andmx.ui2.settings.settingsTopBarColors
import kotlinx.coroutines.launch

private sealed class PluginView {
    data object List : PluginView()
    data class Detail(val plugin: PluginSystem.Plugin) : PluginView()
    data class MarketDetail(val entry: PluginMarketplace.CatalogPlugin) : PluginView()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { ProotRuntime(context) }
    val fs = remember { GuestFs(runtime) }
    val shell = remember { PersistentShell(context, runtime) }
    val system = remember { PluginSystem(context, fs, shell) }
    val market = remember { PluginMarketplace(context, fs, shell) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refresh by remember { mutableIntStateOf(0) }
    var marketRefresh by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var showInstall by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<PluginView>(PluginView.List) }

    val plugins by produceState(
        initialValue = emptyList<PluginSystem.Plugin>(),
        key1 = refresh,
    ) {
        refreshing = true
        runCatching { BuiltinPluginSeeder.ensureSeeded(context, fs) }
        value = runCatching { system.discover().plugins }.getOrDefault(emptyList())
        refreshing = false
    }

    val marketBrowse by produceState(
        initialValue = PluginMarketplace.BrowseResult(PluginMarketplace.Catalog(), "init"),
        key1 = marketRefresh,
    ) {
        refreshing = true
        value = runCatching { market.loadCatalog(forceRefresh = marketRefresh > 0) }
            .getOrElse { PluginMarketplace.BrowseResult(PluginMarketplace.Catalog(), "error", it.message) }
        refreshing = false
    }

    val installedNames = remember(plugins) {
        plugins.map { it.manifest.name.lowercase() }.toSet()
    }

    AnimatedContent(
        targetState = view,
        transitionSpec = {
            if (targetState is PluginView.List) {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            } else {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            }
        },
        label = "pluginView",
    ) { target ->
        when (target) {
            is PluginView.List -> PluginHubView(
                tab = tab,
                onTabChange = { tab = it },
                plugins = plugins,
                market = marketBrowse,
                installedNames = installedNames,
                refreshing = refreshing || installing,
                snackbar = snackbar,
                onBack = onBack,
                onRefreshInstalled = { refresh++ },
                onRefreshMarket = { marketRefresh++ },
                onOpenInstalled = { view = PluginView.Detail(it) },
                onOpenMarket = { view = PluginView.MarketDetail(it) },
                onToggle = { p, enabled ->
                    scope.launch {
                        system.setEnabled(p.manifest.name, enabled)
                        refresh++
                        snackbar.showSnackbar(if (enabled) "已启用 ${p.manifest.name}" else "已停用 ${p.manifest.name}")
                    }
                },
                onInstallClick = { showInstall = true },
                onInstallMarket = { entry ->
                    scope.launch {
                        installing = true
                        val res = runCatching { system.installFromMarketplace(entry) }
                            .getOrElse { PluginSystem.InstallResult(false, entry.name, "", listOf(it.message ?: "error")) }
                        installing = false
                        if (res.ok) {
                            snackbar.showSnackbar("已安装 ${res.name}")
                            refresh++
                            marketRefresh++
                        } else {
                            snackbar.showSnackbar(res.errors.firstOrNull() ?: "安装失败")
                        }
                    }
                },
            )
            is PluginView.Detail -> PluginDetailPage(
                plugin = target.plugin,
                onBack = { view = PluginView.List },
                onToggleEnabled = { enabled ->
                    scope.launch {
                        system.setEnabled(target.plugin.manifest.name, enabled)
                        refresh++
                    }
                },
                onUninstall = if (target.plugin.source != PluginSystem.PluginSource.BUILTIN) {
                    {
                        scope.launch {
                            system.uninstall(target.plugin.manifest.name)
                            snackbar.showSnackbar("已卸载 ${target.plugin.manifest.name}")
                            view = PluginView.List
                            refresh++
                        }
                    }
                } else null,
            )
            is PluginView.MarketDetail -> MarketPluginDetailPage(
                entry = target.entry,
                installed = target.entry.name.lowercase() in installedNames,
                installing = installing,
                onBack = { view = PluginView.List },
                onInstall = {
                    scope.launch {
                        installing = true
                        val res = runCatching { system.installFromMarketplace(target.entry) }
                            .getOrElse {
                                PluginSystem.InstallResult(false, target.entry.name, "", listOf(it.message ?: "error"))
                            }
                        installing = false
                        if (res.ok) {
                            snackbar.showSnackbar("已安装 ${res.name}")
                            refresh++
                            view = PluginView.List
                        } else {
                            snackbar.showSnackbar(res.errors.firstOrNull() ?: "安装失败")
                        }
                    }
                },
            )
        }
    }

    if (showInstall) {
        InstallPluginDialog(
            installing = installing,
            onDismiss = { if (!installing) showInstall = false },
            onConfirmGit = { url ->
                scope.launch {
                    installing = true
                    val res = system.installFromGit(url)
                    installing = false
                    showInstall = false
                    if (res.ok) {
                        snackbar.showSnackbar("已安装 ${res.name}")
                        refresh++
                    } else {
                        snackbar.showSnackbar(res.errors.firstOrNull() ?: "安装失败")
                    }
                }
            },
            onConfirmLocal = { path ->
                scope.launch {
                    installing = true
                    val res = system.installFromLocal(path)
                    installing = false
                    showInstall = false
                    if (res.ok) {
                        snackbar.showSnackbar("已安装 ${res.name}")
                        refresh++
                    } else {
                        snackbar.showSnackbar(res.errors.firstOrNull() ?: "安装失败")
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginHubView(
    tab: Int,
    onTabChange: (Int) -> Unit,
    plugins: List<PluginSystem.Plugin>,
    market: PluginMarketplace.BrowseResult,
    installedNames: Set<String>,
    refreshing: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRefreshInstalled: () -> Unit,
    onRefreshMarket: () -> Unit,
    onOpenInstalled: (PluginSystem.Plugin) -> Unit,
    onOpenMarket: (PluginMarketplace.CatalogPlugin) -> Unit,
    onToggle: (PluginSystem.Plugin, Boolean) -> Unit,
    onInstallClick: () -> Unit,
    onInstallMarket: (PluginMarketplace.CatalogPlugin) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }

    val categories = remember(market.catalog.plugins) {
        listOf("全部") + market.catalog.plugins.map { it.category.ifBlank { "uncategorized" } }
            .distinct().sorted()
    }
    val marketItems = remember(market.catalog.plugins, query, category) {
        market.catalog.plugins.filter { p ->
            val catOk = category == "全部" || p.category == category
            val q = query.trim()
            val qOk = q.isBlank() ||
                p.name.contains(q, true) ||
                p.description.contains(q, true) ||
                p.author.contains(q, true) ||
                p.marketplace.contains(q, true)
            catOk && qOk
        }
    }
    val installedFiltered = remember(plugins, query) {
        val q = query.trim()
        if (q.isBlank()) plugins
        else plugins.filter {
            it.manifest.name.contains(q, true) ||
                it.manifest.description.contains(q, true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = settingsTopBarColors(),
                actions = {
                    IconButton(
                        onClick = {
                            if (tab == 0) onRefreshInstalled() else onRefreshMarket()
                        },
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = onInstallClick) {
                    Icon(Icons.Outlined.Add, contentDescription = "安装插件")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { onTabChange(0) }, text = { Text("已安装") })
                Tab(selected = tab == 1, onClick = { onTabChange(1) }, text = { Text("市场") })
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ResourcePageDescription(
                    if (tab == 0) "启用或停用已安装的插件。插件可打包技能、命令、Hooks 和 MCP 服务器。"
                    else "浏览插件市场并安装。官方目录与 AndMX / Claude 市场对齐。"
                )
                Spacer(Modifier.height(10.dp))
                ResourceSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = if (tab == 0) "搜索已安装插件..." else "搜索市场插件...",
                )
            }
            if (tab == 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    items(categories) { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c) },
                        )
                    }
                }
            }

            when (tab) {
                0 -> {
                    if (installedFiltered.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.Extension,
                            title = "还没有插件",
                            message = "可从市场安装，或用 + 从 Git / 本地路径安装。",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                ResourceSectionHeader(
                                    title = "已安装插件",
                                    count = installedFiltered.size,
                                )
                            }
                            item {
                                ResourceCardGroup {
                                    installedFiltered.forEachIndexed { index, p ->
                                        if (index > 0) ResourceRowDivider()
                                        InstalledPluginRow(
                                            plugin = p,
                                            onOpen = { onOpenInstalled(p) },
                                            onToggle = { onToggle(p, it) },
                                        )
                                    }
                                }
                            }
                            item {
                                ResourceFooterCount("共 ${installedFiltered.size} 个插件")
                            }
                        }
                    }
                }
                else -> {
                    if (marketItems.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.Storefront,
                            title = "市场暂无结果",
                            message = "下拉刷新，或检查网络后重试。官方目录会与 Claude Plugins Official 对齐。",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                ResourceSectionHeader(
                                    title = "插件市场",
                                    count = marketItems.size,
                                    hint = "来源 ${market.from}" + (market.error?.let { " · $it" } ?: ""),
                                )
                            }
                            item {
                                ResourceCardGroup {
                                    marketItems.forEachIndexed { index, entry ->
                                        if (index > 0) ResourceRowDivider()
                                        MarketPluginRow(
                                            entry = entry,
                                            installed = entry.name.lowercase() in installedNames,
                                            onOpen = { onOpenMarket(entry) },
                                            onInstall = { onInstallMarket(entry) },
                                        )
                                    }
                                }
                            }
                            item {
                                ResourceFooterCount("共 ${marketItems.size} 个插件")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledPluginRow(
    plugin: PluginSystem.Plugin,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val m = plugin.manifest
    val meta = listOfNotNull(
        "${m.skills.size} 技能".takeIf { m.skills.isNotEmpty() || true },
        "${m.commands.size} 命令",
        "${m.tools.size} 工具",
        "${m.hooks.size} Hooks",
        "${m.mcpServers.size} MCP".takeIf { m.mcpServers.isNotEmpty() },
    ).joinToString(" · ")
    ResourceListRow(
        icon = Icons.Outlined.Extension,
        title = m.name,
        subtitle = (m.description.ifBlank { "暂无描述" } + sourceSuffix(plugin.source)).take(120),
        onClick = onOpen,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResourceBadge(
                    when (plugin.source) {
                        PluginSystem.PluginSource.BUILTIN -> "内置"
                        PluginSystem.PluginSource.MARKETPLACE -> "市场"
                        PluginSystem.PluginSource.LOCAL -> "本地"
                    }
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = plugin.enabled, onCheckedChange = onToggle)
            }
        },
    )
}

@Composable
private fun MarketPluginRow(
    entry: PluginMarketplace.CatalogPlugin,
    installed: Boolean,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
) {
    ResourceListRow(
        icon = Icons.Outlined.Storefront,
        title = entry.name,
        subtitle = entry.description.ifBlank {
            listOfNotNull(
                entry.author.takeIf { it.isNotBlank() },
                entry.category,
                entry.marketplace,
            ).joinToString(" · ").ifBlank { "暂无描述" }
        },
        onClick = onOpen,
        trailing = {
            if (installed) {
                ResourceBadge("已安装")
            } else {
                TextButton(onClick = onInstall) { Text("安装") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketPluginDetailPage(
    entry: PluginMarketplace.CatalogPlugin,
    installed: Boolean,
    installing: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(entry.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    }
                },
                actions = {
                    if (!installed) {
                        TextButton(onClick = onInstall, enabled = !installing) {
                            if (installing) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("安装")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                listOfNotNull(
                    entry.author.takeIf { it.isNotBlank() },
                    entry.category,
                    entry.marketplace,
                    entry.version.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.description.isNotBlank()) {
                Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (entry.homepage.isNotBlank()) {
                Text("主页：${entry.homepage}", style = MaterialTheme.typography.bodySmall)
            }
            val src = entry.source
            if (src != null) {
                Text(
                    buildString {
                        append("源类型：${src.type}")
                        if (src.url.isNotBlank()) append("\nURL：${src.url}")
                        if (src.repo.isNotBlank()) append("\nRepo：${src.repo}")
                        if (src.path.isNotBlank()) append("\nPath：${src.path}")
                        if (src.ref.isNotBlank()) append("\nRef：${src.ref}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (installed) {
                AssistChip(onClick = {}, enabled = false, label = { Text("已安装") })
            }
        }
    }
}

@Composable
private fun InstallPluginDialog(
    installing: Boolean,
    onDismiss: () -> Unit,
    onConfirmGit: (String) -> Unit,
    onConfirmLocal: (String) -> Unit,
) {
    var mode by remember { mutableIntStateOf(0) }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = { Text("安装插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Git") })
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("本地路径") })
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !installing,
                    placeholder = {
                        Text(
                            if (mode == 0) "https://github.com/org/plugin.git"
                            else "/root/project/my-plugin",
                        )
                    },
                    label = { Text(if (mode == 0) "仓库 URL" else "Guest 路径") },
                )
                Text(
                    if (mode == 0) "将 clone 到 /root/.andmx/plugins/<name>，并识别 .andmx-plugin / .zcode-plugin / .codex-plugin / SKILL.md。"
                    else "从 proot 内路径复制整个目录作为插件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (installing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(" 安装中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val v = value.trim()
                    if (v.isBlank()) return@TextButton
                    if (mode == 0) onConfirmGit(v) else onConfirmLocal(v)
                },
                enabled = !installing && value.isNotBlank(),
            ) { Text("安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !installing) { Text("取消") }
        },
    )
}

private fun sourceSuffix(source: PluginSystem.PluginSource): String = when (source) {
    PluginSystem.PluginSource.BUILTIN -> " · 内置"
    PluginSystem.PluginSource.MARKETPLACE -> " · 市场"
    PluginSystem.PluginSource.LOCAL -> " · 本地"
}
