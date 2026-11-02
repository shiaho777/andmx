package com.andmx.ui2.settings.pages

import com.andmx.ui2.settings.tr

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.agent.plugins.SkillInstaller
import com.andmx.agent.plugins.SkillValidator
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
    val frontmatter = remember(skillMd) { skillFrontmatter(skillMd) }
    val diagnostics by produceState<SkillValidator.ValidationResult?>(null, skill.path, skill.hasSkillMd) {
        value = if (!skill.hasSkillMd) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { SkillValidator(fs).validateSkillMd("${skill.path}/SKILL.md") }.getOrNull()
            }
        }
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
                InfoLineSkill("版本", frontmatter["version"].orEmpty().ifBlank { "未声明" })
                InfoLineSkill("说明", skill.description.ifBlank { frontmatter["description"].orEmpty().ifBlank { "未声明" } })
                InfoLineSkill("SKILL.md", if (skill.hasSkillMd) "已包含" else "缺失")
                InfoLineSkill("路径", skill.path)
                diagnostics?.let { result ->
                    InfoLineSkill("检查", if (result.valid) "通过" else "有问题")
                    result.errors.forEach { InfoLineSkill("错误", it) }
                    result.warnings.forEach { InfoLineSkill("提醒", it) }
                }
            }

            if (skillMd.isNotBlank()) {
                SettingsGroup("SKILL.md") {
                    MarkdownView(markdown = skillMd)
                }
            } else if (!skill.hasSkillMd) {
                SettingsGroup("说明") {
                    Text(tr("该技能目录缺少 SKILL.md，可能无法被正确加载。"),
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
                Text(tr("删除技能"), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("删除技能")) },
            text = { Text(tr("确定删除「${skill.name}」？将从磁盘移除该技能目录，且无法撤销。")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(tr("取消")) }
            }
        )
    }
}

private fun skillFrontmatter(content: String): Map<String, String> {
    if (!content.startsWith("---")) return emptyMap()
    val end = content.indexOf("\n---", 3)
    if (end < 0) return emptyMap()
    val map = linkedMapOf<String, String>()
    content.substring(3, end).lineSequence().forEach { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) return@forEach
        var value = line.substring(idx + 1).trim()
        if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\''))) {
            value = value.substring(1, value.length - 1)
        }
        map[line.substring(0, idx).trim()] = value
    }
    return map
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
