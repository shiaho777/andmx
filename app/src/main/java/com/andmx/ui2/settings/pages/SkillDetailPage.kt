package com.andmx.ui2.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.agent.plugins.SkillInstaller
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.ui2.markdown.MarkdownView
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.backAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailPage(
    skill: SkillInstaller.InstalledSkill,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val fs = remember { GuestFs(ProotRuntime(context)) }
    var confirmDelete by remember { mutableStateOf(false) }

    val skillMd by produceState(initialValue = "", key1 = skill.path) {
        value = if (skill.hasSkillMd) {
            runCatching { fs.readText("${skill.path}/SKILL.md") }.getOrDefault("")
        } else ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { backAppBar(skill.name, onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SettingsGroup("状态") {
                InfoLineSkill("触发方式", "\$${skill.name}")
                InfoLineSkill("SKILL.md", if (skill.hasSkillMd) "已包含" else "缺失")
                InfoLineSkill("路径", skill.path)
            }

            if (skillMd.isNotBlank()) {
                SettingsGroup("SKILL.md") {
                    MarkdownView(markdown = skillMd)
                }
            } else if (!skill.hasSkillMd) {
                SettingsGroup("说明") {
                    Text(
                        "该技能目录缺少 SKILL.md，可能无法被正确加载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Text("删除技能", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除技能") },
            text = { Text("确定删除「${skill.name}」？将从磁盘移除该技能目录，且无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun InfoLineSkill(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}
