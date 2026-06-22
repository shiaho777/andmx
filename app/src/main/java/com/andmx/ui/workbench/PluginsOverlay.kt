package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import com.andmx.agent.approvalEffect
import com.andmx.agent.description
import com.andmx.agent.label
import com.andmx.agent.riskOrder
import com.andmx.ui.conversation.ConversationController
import com.andmx.ui.rememberScreenHeightDp
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/** Lists the agent's tool registry and connected MCP servers — the 插件 page. */
@Composable
fun PluginsOverlay(controller: ConversationController, onConfigure: () -> Unit, onDismiss: () -> Unit) {
    val colors = AndmxTheme.colors
    val tools = controller.toolCapabilities()
    val servers = controller.mcpServers()
    val approvalMode = controller.approvalMode

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().clip(Radii.lg).background(colors.surfaceElevated).padding(Spacing.xl),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("插件与工具", style = AndmxTheme.typography.titleLarge, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    "配置 MCP", style = AndmxTheme.typography.labelMedium, color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onConfigure(); onDismiss() }.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SummaryChip(Icons.Outlined.Extension, "${tools.size} 内置工具")
                Spacer(Modifier.width(Spacing.sm))
                SummaryChip(Icons.Outlined.Security, "授权 ${approvalMode.label}")
                Spacer(Modifier.width(Spacing.sm))
                SummaryChip(Icons.Outlined.Settings, "${servers.size} MCP")
            }
            Spacer(Modifier.height(Spacing.md))
            val listMaxHeight = (rememberScreenHeightDp() * 0.6f).dp
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMaxHeight)) {
                if (servers.isNotEmpty()) {
                    item { SectionLabel("MCP 服务器") }
                    items(servers) { s ->
                        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                            Text("● ${s.name}", style = AndmxTheme.typography.titleSmall, color = colors.accent)
                            Text(s.tools.joinToString("、").ifBlank { "(无工具)" }, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
                        }
                    }
                    item { Spacer(Modifier.height(Spacing.md)) }
                }
                item { SectionLabel("可用工具 (${tools.size})") }
                val groups = tools.groupBy { it.risk }.toSortedMap(compareBy { riskOrder(it) })
                groups.forEach { (risk, group) ->
                    item(key = "risk-${risk.name}") { RiskHeader(risk) }
                    items(group, key = { it.name }) { tool ->
                        ToolCapabilityRow(tool, approvalEffect(approvalMode, tool.risk))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.clip(Radii.pill).background(colors.sunken)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = AndmxTheme.typography.labelSmall, color = colors.textSecondary)
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = AndmxTheme.colors
    Text(
        text, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary,
        modifier = Modifier.padding(vertical = Spacing.xs),
    )
}

@Composable
private fun RiskHeader(risk: ToolRisk) {
    val colors = AndmxTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(risk.label, style = AndmxTheme.typography.titleSmall, color = colors.textPrimary)
            Spacer(Modifier.width(Spacing.sm))
            Text(risk.description, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        }
    }
}

@Composable
private fun ToolCapabilityRow(tool: ToolCapability, effect: String) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
            .clip(Radii.sm).border(1.dp, colors.border, Radii.sm)
            .background(colors.surface)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tool.name, style = AndmxTheme.typography.titleSmall, color = colors.textPrimary)
            Text(tool.description, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 2)
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            effect,
            style = AndmxTheme.typography.labelSmall,
            color = when (effect) {
                "自动" -> colors.accent
                "询问" -> colors.warning
                else -> colors.textTertiary
            },
            modifier = Modifier.clip(Radii.pill).background(colors.sunken)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}
