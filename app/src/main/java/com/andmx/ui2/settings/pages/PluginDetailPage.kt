package com.andmx.ui2.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.andmx.agent.plugins.PluginSystem
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.backAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailPage(
    plugin: PluginSystem.Plugin,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    val m = plugin.manifest
    Scaffold(topBar = { backAppBar(m.name, onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SettingsGroup("状态") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (plugin.enabled) "已启用" else "已停用",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "v${m.version}" + if (m.author.isNotBlank()) " · ${m.author}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = plugin.enabled, onCheckedChange = onToggleEnabled)
                }
                AssistChip(
                    onClick = {},
                    label = { Text(sourceLabel(plugin.source)) }
                )
            }

            if (m.description.isNotBlank()) {
                SettingsGroup("描述") {
                    Text(
                        m.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            SettingsGroup("组件") {
                CapabilitySection("工具", Icons.Outlined.Terminal, m.tools)
                CapabilitySection("技能", Icons.Outlined.Bolt, m.skills)
                CapabilitySection("Hooks", Icons.Outlined.Link, m.hooks.map { it.name.ifBlank { it.event } })
                if (m.tools.isEmpty() && m.skills.isEmpty() && m.hooks.isEmpty()) {
                    Text(
                        "该插件未声明可展示的组件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            SettingsGroup("安装信息") {
                InfoLine("目录", plugin.dir)
                InfoLine("版本", m.version)
                if (m.author.isNotBlank()) InfoLine("作者", m.author)
            }
        }
    }
}

@Composable
private fun CapabilitySection(title: String, icon: ImageVector, items: List<String>) {
    if (items.isEmpty()) return
    Text(
        "$title · ${items.size} 项",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
    items.forEach { item ->
        ListItem(
            headlineContent = { Text(item) },
            leadingContent = { Icon(icon, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}

private fun sourceLabel(source: PluginSystem.PluginSource): String = when (source) {
    PluginSystem.PluginSource.BUILTIN -> "内置"
    PluginSystem.PluginSource.MARKETPLACE -> "市场安装"
    PluginSystem.PluginSource.LOCAL -> "本地"
}
