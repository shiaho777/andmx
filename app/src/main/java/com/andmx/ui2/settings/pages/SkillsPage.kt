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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.andmx.agent.plugins.SkillInstaller
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.ui2.settings.EmptyState
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.launch

private sealed class SkillView {
    data object List : SkillView()
    data class Detail(val skill: SkillInstaller.InstalledSkill) : SkillView()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val installer = remember { SkillInstaller(GuestFs(ProotRuntime(context))) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<SkillView>(SkillView.List) }

    val skills by produceState(
        initialValue = emptyList<SkillInstaller.InstalledSkill>(),
        key1 = refresh
    ) {
        refreshing = true
        value = runCatching { installer.listInstalled() }.getOrDefault(emptyList())
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
                skills = skills,
                refreshing = refreshing,
                onBack = onBack,
                onRefresh = { refresh++ },
                onOpen = { view = SkillView.Detail(it) },
                onInstall = { url ->
                    scope.launch {
                        runCatching { installer.installFromGit(url) }
                        refresh++
                    }
                }
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
    skills: List<SkillInstaller.InstalledSkill>,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (SkillInstaller.InstalledSkill) -> Unit,
    onInstall: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showInstall by remember { mutableStateOf(false) }
    val filtered = skills.filter { query.isBlank() || it.name.contains(query, true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("技能") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        Icon(Icons.Outlined.Refresh, "刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showInstall = true }) {
                Icon(Icons.Outlined.Bolt, "安装技能")
            }
        }
    ) { padding ->
        if (skills.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Bolt,
                title = "没有可用技能",
                message = "技能可从 Git 仓库安装，启用后在聊天里通过 \$skill-name 使用。",
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
                        placeholder = { Text("搜索技能...") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                items(filtered, key = { it.name }) { skill ->
                    SkillCard(skill, onOpen = { onOpen(skill) })
                }
                item {
                    Text(
                        "共 ${skills.size} 个技能",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    if (showInstall) {
        InstallDialog(
            onDismiss = { showInstall = false },
            onConfirm = { url ->
                showInstall = false
                onInstall(url)
            }
        )
    }
}

@Composable
private fun SkillCard(skill: SkillInstaller.InstalledSkill, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Bolt,
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("\$${skill.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (skill.hasSkillMd) "已包含 SKILL.md" else "缺少 SKILL.md",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (skill.hasSkillMd) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.error
                )
            }
        }
    }
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
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
