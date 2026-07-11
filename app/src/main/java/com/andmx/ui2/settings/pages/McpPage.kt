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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.mcp.McpManager
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.EmptyState
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.launch

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

    Scaffold(
        topBar = { backAppBar("MCP 服务器", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, "新建 MCP 服务器")
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Cloud,
                title = "还没有 MCP 服务器",
                message = "添加一个 MCP 服务器，让 Agent 获得额外能力。",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { it.name }) { entry ->
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (entry.isRemote) Icons.Outlined.Cloud else Icons.Outlined.Terminal,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                entry.target,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 2
            )
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (entry.isRemote) "HTTP / SSE" else "STDIO") }
                )
                when (state) {
                    is ConnState.Testing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    is ConnState.Ok -> Text(
                        "已连接 · ${state.tools} 个工具",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    is ConnState.Fail -> Text(
                        state.reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    ConnState.Idle -> TextButton(onClick = onTest) { Text("测试连接") }
                }
            }
        }
    }
}
