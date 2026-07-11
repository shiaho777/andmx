package com.andmx.ui2.settings.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.agent.SlashCommands
import com.andmx.settings.CustomCommand
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.launch

private sealed class CmdView {
    data object List : CmdView()
    data class Edit(val command: CustomCommand?) : CmdView()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val commands by store.customCommands.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<CmdView>(CmdView.List) }

    AnimatedContent(
        targetState = view,
        transitionSpec = {
            if (targetState is CmdView.Edit) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "cmdView"
    ) { target ->
        when (target) {
            is CmdView.List -> CommandListView(
                commands = commands,
                onBack = onBack,
                onAdd = { view = CmdView.Edit(null) },
                onEdit = { view = CmdView.Edit(it) }
            )
            is CmdView.Edit -> CommandEditPage(
                initial = target.command,
                onBack = { view = CmdView.List },
                onSave = { cmd ->
                    scope.launch {
                        val others = commands.filterNot { it.id == cmd.id }
                        store.saveCommands(others + cmd)
                    }
                    view = CmdView.List
                },
                onDelete = target.command?.let { c ->
                    {
                        scope.launch { store.saveCommands(commands.filterNot { it.id == c.id }) }
                        view = CmdView.List
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandListView(
    commands: List<CustomCommand>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (CustomCommand) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val userMatches = commands.filter {
        query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true)
    }
    val builtinMatches = remember(query) {
        if (query.isBlank()) SlashCommands.list
        else SlashCommands.suggestions(if (query.startsWith("/")) query else "/$query", 40)
    }

    Scaffold(
        topBar = { backAppBar("命令", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, "新建命令")
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索命令...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    singleLine = true
                )
            }

            item { GroupHeader("用户命令") }
            if (userMatches.isEmpty()) {
                item { EmptyHint("暂无用户命令，点击右下角新建") }
            } else {
                items(userMatches, key = { it.id }) { cmd ->
                    ListItem(
                        headlineContent = {
                            Text("/${cmd.name}", fontFamily = FontFamily.Monospace)
                        },
                        supportingContent = {
                            Text(cmd.description.ifBlank { "暂无描述" })
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(cmd) }
                    )
                }
            }

            item { GroupHeader("内置命令") }
            items(builtinMatches) { spec ->
                ListItem(
                    headlineContent = { Text(spec.name, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text(spec.desc) },
                    leadingContent = { Icon(Icons.Outlined.Terminal, null) }
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}
