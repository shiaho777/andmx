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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderStore
import com.andmx.ui2.settings.EmptyState
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.launch

private sealed class ModelView {
    data object List : ModelView()
    data class Edit(val provider: ProviderDefinition?) : ModelView()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProviderStore(context) }
    val providers by store.providers.collectAsState(initial = emptyList())
    val primary by store.primary.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var view by remember { mutableStateOf<ModelView>(ModelView.List) }

    AnimatedContent(
        targetState = view,
        transitionSpec = {
            if (targetState is ModelView.Edit) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "modelView"
    ) { target ->
        when (target) {
            is ModelView.List -> ModelListView(
                providers = providers,
                primaryId = primary?.id,
                onBack = onBack,
                onAdd = { view = ModelView.Edit(null) },
                onEdit = { view = ModelView.Edit(it) },
                onToggleEnabled = { p, enabled ->
                    scope.launch { store.upsert(p.copy(enabled = enabled)) }
                },
                onSetPrimary = { scope.launch { store.setPrimary(it.id) } }
            )
            is ModelView.Edit -> ProviderEditPage(
                initial = target.provider,
                onBack = { view = ModelView.List },
                onSave = {
                    scope.launch { store.upsert(it) }
                    view = ModelView.List
                },
                onDelete = target.provider?.let { p ->
                    {
                        scope.launch { store.delete(p.id) }
                        view = ModelView.List
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelListView(
    providers: List<ProviderDefinition>,
    primaryId: String?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ProviderDefinition) -> Unit,
    onToggleEnabled: (ProviderDefinition, Boolean) -> Unit,
    onSetPrimary: (ProviderDefinition) -> Unit
) {
    Scaffold(
        topBar = { backAppBar("模型设置", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, "添加供应商")
            }
        }
    ) { padding ->
        if (providers.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Add,
                title = "暂无自定义模型供应商",
                message = "配置后可在聊天时选择使用。点击右下角添加。",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        isPrimary = provider.id == primaryId,
                        onEdit = { onEdit(provider) },
                        onToggleEnabled = { onToggleEnabled(provider, it) },
                        onSetPrimary = { onSetPrimary(provider) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderDefinition,
    isPrimary: Boolean,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSetPrimary: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            provider.name.ifBlank { provider.id },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isPrimary) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                "主要",
                                Modifier.padding(start = 6.dp).size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        provider.baseUrl.ifBlank { "未设置地址" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(kindShort(provider)) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("${provider.models.size} 个模型") }
                )
                if (!isPrimary && provider.enabled) {
                    androidx.compose.material3.TextButton(onClick = onSetPrimary) {
                        Text("设为主要")
                    }
                }
            }
        }
    }
}

private fun kindShort(p: ProviderDefinition): String = when (p.kind.name) {
    "OPENAI" -> "Chat"
    "OPENAI_RESPONSES" -> "Responses"
    "ANTHROPIC" -> "Anthropic"
    else -> p.kind.name
}
