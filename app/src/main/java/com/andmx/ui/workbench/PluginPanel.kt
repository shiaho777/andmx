package com.andmx.ui.workbench

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.mcp.McpManager
import com.andmx.ui.theme.AndmxColors
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/**
 * MCP servers panel — Codex-style.
 *
 * Displays each connected MCP server as a card with its tool list.
 * Mirrors how Codex shows MCP server status: name, connection state,
 * and the tools each server advertises. Empty state guides the user
 * to the settings page where servers are configured.
 */
@Composable
fun PluginPanel(
    servers: List<McpManager.Connected>,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.textSecondary)
            Spacer(Modifier.width(Spacing.sm))
            Text("MCP 服务器", style = AndmxTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Spacer(Modifier.weight(1f))
            Text("${servers.size} 个", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        }

        Spacer(Modifier.height(Spacing.md))

        if (servers.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(32.dp), tint = colors.textTertiary)
                Spacer(Modifier.height(Spacing.sm))
                Text("暂无已连接的 MCP 服务器", style = AndmxTheme.typography.bodySmall, color = colors.textSecondary)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "在设置 → 偏好中添加 MCP 服务器\n格式: 名称|启动命令 (每行一个)",
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            servers.forEach { server ->
                McpServerCard(server, colors)
                Spacer(Modifier.height(Spacing.sm))
            }
        }
    }
}

@Composable
private fun McpServerCard(server: McpManager.Connected, colors: AndmxColors) {
    var expanded by remember { mutableStateOf(false) }
    val dotColor by animateColorAsState(if (server.tools.isNotEmpty()) colors.accent else colors.textTertiary, label = "mcpDot")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radii.md)
            .background(colors.surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status dot
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(dotColor),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                server.name,
                style = AndmxTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${server.tools.size} 工具",
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }

        if (expanded && server.tools.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            server.tools.forEach { toolName ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("·", style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        toolName,
                        style = AndmxTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
