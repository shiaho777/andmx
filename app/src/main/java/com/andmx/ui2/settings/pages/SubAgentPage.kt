package com.andmx.ui2.settings.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.settings.CustomSubAgent
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.EmptyState
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.launch

private sealed class AgentView {
    data object List : AgentView()
    data class Edit(val agent: CustomSubAgent?) : AgentView()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val agents by store.customSubAgents.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<AgentView>(AgentView.List) }

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
                agents = agents,
                onBack = onBack,
                onAdd = { view = AgentView.Edit(null) },
                onEdit = { view = AgentView.Edit(it) },
                onToggle = { a, enabled ->
                    scope.launch {
                        store.saveSubAgents(agents.map { if (it.id == a.id) it.copy(enabled = enabled) else it })
                    }
                }
            )
            is AgentView.Edit -> SubAgentEditPage(
                initial = target.agent,
                onBack = { view = AgentView.List },
                onSave = { agent ->
                    scope.launch {
                        val others = agents.filterNot { it.id == agent.id }
                        store.saveSubAgents(others + agent)
                    }
                    view = AgentView.List
                },
                onDelete = target.agent?.let { a ->
                    {
                        scope.launch { store.saveSubAgents(agents.filterNot { it.id == a.id }) }
                        view = AgentView.List
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListView(
    agents: List<CustomSubAgent>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (CustomSubAgent) -> Unit,
    onToggle: (CustomSubAgent, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = agents.filter {
        query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true)
    }
    val enabledCount = agents.count { it.enabled }

    Scaffold(
        topBar = { backAppBar("子智能体", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Outlined.SmartToy, "新建子智能体")
            }
        }
    ) { padding ->
        if (agents.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.SmartToy,
                title = "没有找到子智能体",
                message = "创建子智能体定义，主 agent 可在运行时派生它并行处理子任务。",
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
                        placeholder = { Text("搜索子智能体...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                items(filtered, key = { it.id }) { agent ->
                    AgentCard(agent, onEdit = { onEdit(agent) }, onToggle = { onToggle(agent, it) })
                }
                item {
                    Text(
                        "共 ${agents.size} 个子智能体 · $enabledCount 个已启用",
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
private fun AgentCard(
    agent: CustomSubAgent,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val dot = agentColors.firstOrNull { it.first == agent.color }?.second ?: Color.Gray
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(12.dp).clip(CircleShape).background(dot)
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(agent.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    agent.description.ifBlank { "暂无描述" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    modelLabel(agent.model) + " · " + permLabel(agent.permissionMode) +
                        if (agent.background) " · 后台" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(checked = agent.enabled, onCheckedChange = onToggle)
        }
    }
}

private fun modelLabel(m: String): String = when (m) {
    "main" -> "主模型"; "lite" -> "轻量模型"; else -> "继承默认"
}

private fun permLabel(p: String): String = when (p) {
    "acceptEdits" -> "接受编辑"; "plan" -> "计划模式"
    "auto" -> "自动"; "bypassPermissions" -> "绕过权限"; "dontAsk" -> "不询问"
    else -> "默认权限"
}
