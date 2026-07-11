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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.andmx.agent.plugins.PluginSystem
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.ui2.settings.EmptyState
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.launch

private sealed class PluginView {
    data object List : PluginView()
    data class Detail(val plugin: PluginSystem.Plugin) : PluginView()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val system = remember { PluginSystem(context, GuestFs(ProotRuntime(context))) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<PluginView>(PluginView.List) }

    val plugins by produceState(
        initialValue = emptyList<PluginSystem.Plugin>(),
        key1 = refresh
    ) {
        refreshing = true
        value = runCatching { system.discover().plugins }.getOrDefault(emptyList())
        refreshing = false
    }

    AnimatedContent(
        targetState = view,
        transitionSpec = {
            if (targetState is PluginView.Detail) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "pluginView"
    ) { target ->
        when (target) {
            is PluginView.List -> PluginListView(
                plugins = plugins,
                refreshing = refreshing,
                onBack = onBack,
                onRefresh = { refresh++ },
                onOpen = { view = PluginView.Detail(it) },
                onToggle = { p, enabled ->
                    scope.launch {
                        system.setEnabled(p.manifest.name, enabled)
                        refresh++
                    }
                }
            )
            is PluginView.Detail -> {
                val live = plugins.firstOrNull { it.manifest.name == target.plugin.manifest.name }
                    ?: target.plugin
                PluginDetailPage(
                    plugin = live,
                    onBack = { view = PluginView.List },
                    onToggleEnabled = { enabled ->
                        scope.launch {
                            system.setEnabled(live.manifest.name, enabled)
                            refresh++
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginListView(
    plugins: List<PluginSystem.Plugin>,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (PluginSystem.Plugin) -> Unit,
    onToggle: (PluginSystem.Plugin, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = plugins.filter {
        query.isBlank() || it.manifest.name.contains(query, true) ||
            it.manifest.description.contains(query, true)
    }
    val enabledCount = plugins.count { it.enabled }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        Icon(Icons.Outlined.Refresh, "刷新")
                    }
                }
            )
        }
    ) { padding ->
        if (plugins.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Extension,
                title = "没有找到插件",
                message = "插件可打包技能、命令、Hooks 和 MCP 服务器。",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索插件...") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                items(filtered, key = { it.manifest.name }) { plugin ->
                    PluginCard(plugin, onOpen = { onOpen(plugin) }, onToggle = { onToggle(plugin, it) })
                }
                item {
                    Text(
                        "共 ${plugins.size} 个插件 · $enabledCount 个已启用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginSystem.Plugin,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val m = plugin.manifest
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "v${m.version}" + sourceSuffix(plugin.source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = plugin.enabled, onCheckedChange = onToggle)
            }
            if (m.description.isNotBlank()) {
                Text(
                    m.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "${m.skills.size} 技能 · ${m.tools.size} 工具 · ${m.hooks.size} Hooks",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun sourceSuffix(source: PluginSystem.PluginSource): String = when (source) {
    PluginSystem.PluginSource.BUILTIN -> " · 内置"
    PluginSystem.PluginSource.MARKETPLACE -> " · 市场"
    PluginSystem.PluginSource.LOCAL -> " · 本地"
}
