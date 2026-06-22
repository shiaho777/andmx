package com.andmx.ui.workbench

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.plugins.PluginSystem
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow

@Composable
fun PluginPanel(
    pluginState: StateFlow<PluginSystem.PluginDiscovery>,
    onTogglePlugin: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val discovery by pluginState.collectAsState()
    val colors = AndmxTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.sm),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.accent)
            Spacer(Modifier.width(Spacing.sm))
            Text("插件", style = AndmxTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Spacer(Modifier.weight(1f))
            Text("${discovery.plugins.size} 个", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        }

        Spacer(Modifier.height(Spacing.sm))

        // Stats row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBadge(Icons.Outlined.Build, "工具", discovery.totalTools, colors)
            StatBadge(Icons.Outlined.Anchor, "钩子", discovery.totalHooks, colors)
            StatBadge(Icons.Outlined.School, "技能", discovery.totalSkills, colors)
        }

        Spacer(Modifier.height(Spacing.sm))

        if (discovery.plugins.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(32.dp), tint = colors.textTertiary)
                Spacer(Modifier.height(Spacing.sm))
                Text("暂无已安装的插件", style = AndmxTheme.typography.bodySmall, color = colors.textSecondary)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "将插件放入 /root/.andmx/plugins/ 目录即可自动发现。\n支持 Codex (plugin.json) 和 Claude (.claude-plugin/plugin.json) 格式。",
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
            }
        } else {
            for (plugin in discovery.plugins) {
                PluginCard(plugin, onTogglePlugin, colors)
                Spacer(Modifier.height(Spacing.xs))
            }
        }
    }
}

@Composable
private fun StatBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int, colors: com.andmx.ui.theme.AndmxColors) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (count > 0) colors.accent else colors.textTertiary)
        Spacer(Modifier.height(2.dp))
        Text("$count", style = AndmxTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        Text(label, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

@Composable
private fun PluginCard(
    plugin: PluginSystem.Plugin,
    onToggle: (String, Boolean) -> Unit,
    colors: com.andmx.ui.theme.AndmxColors,
) {
    var enabled by remember(plugin.enabled, plugin.manifest.name) { mutableStateOf(plugin.enabled) }
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (enabled) colors.accent else colors.textTertiary,
                )
                Spacer(Modifier.width(Spacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        plugin.manifest.name,
                        style = AndmxTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        plugin.manifest.description.ifBlank { "无描述" },
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; onToggle(plugin.manifest.name, it) })
            }

            AnimatedVisibility(expanded) {
                Column(modifier = Modifier.padding(top = Spacing.xs)) {
                    InfoLine("版本", plugin.manifest.version, colors)
                    if (plugin.manifest.author.isNotBlank()) InfoLine("作者", plugin.manifest.author, colors)
                    InfoLine("工具", "${plugin.manifest.tools.size} 个", colors)
                    InfoLine("钩子", "${plugin.manifest.hooks.size} 个", colors)
                    InfoLine("技能", "${plugin.manifest.skills.size} 个", colors)
                    InfoLine("目录", plugin.dir, colors)

                    if (plugin.manifest.hooks.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text("钩子详情", style = AndmxTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                        for (hook in plugin.manifest.hooks) {
                            Text(
                                "· ${hook.name.ifBlank { hook.event }}: ${hook.command.take(60)}",
                                style = AndmxTheme.typography.labelSmall,
                                color = colors.textTertiary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(start = Spacing.sm),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, colors: com.andmx.ui.theme.AndmxColors) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("$label: ", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        Text(value, style = AndmxTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
