package com.andmx.ui.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andmx.agent.Guardian
import com.andmx.diff.DiffEngine
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Spacing

/**
 * Tool approval dialog — mirrors Codex's approval interaction.
 *
 * Features:
 * - Guardian risk level with color coding
 * - Diff preview for file modification tools (apply_patch, edit_file, write_file)
 * - Command preview for shell tools
 * - "Always allow this type" option (adds ExecPolicy amendment)
 * - Risk rationale from Guardian
 */
@Composable
fun ApprovalDialog(
    toolName: String,
    arguments: String,
    assessment: Guardian.Assessment?,
    risk: com.andmx.agent.ToolRisk,
    riskDescription: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAlwaysAllow: (() -> Unit)? = null,
) {
    val colors = AndmxTheme.colors
    val riskLevel = assessment?.riskLevel
    val showDiffPreview = toolName in listOf("apply_patch", "edit_file", "write_file")
    val showCommandPreview = toolName == "run_shell"

    AlertDialog(
        onDismissRequest = onDeny,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = riskIcon(riskLevel, risk),
                    contentDescription = null,
                    tint = riskColor(riskLevel, risk, colors),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text("需要审批", style = AndmxTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.weight(1f))
                // Risk badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = riskColor(riskLevel, risk, colors).copy(alpha = 0.12f),
                ) {
                    Text(
                        "${riskLabel(riskLevel, risk)} 风险",
                        style = AndmxTheme.typography.labelSmall,
                        color = riskColor(riskLevel, risk, colors),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        },
        text = {
            Column {
                // Tool name
                Text(
                    "工具: $toolName",
                    style = AndmxTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                )

                // Guardian rationale
                if (assessment != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = riskColor(riskLevel, risk, colors).copy(alpha = 0.06f),
                    ) {
                        Row(modifier = Modifier.padding(Spacing.xs)) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = riskColor(riskLevel, risk, colors),
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                assessment.rationale,
                                style = AndmxTheme.typography.labelSmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.sm))

                // Diff preview for file operations
                if (showDiffPreview) {
                    DiffPreviewSection(toolName, arguments, colors)
                } else if (showCommandPreview) {
                    CommandPreviewSection(arguments, colors)
                } else {
                    // Generic argument display
                    Text("参数", style = AndmxTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                    Spacer(Modifier.height(Spacing.xs))
                    CodeBlock(arguments.take(2000), colors)
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onAlwaysAllow != null && riskLevel != Guardian.RiskLevel.CRITICAL) {
                    TextButton(onClick = onAlwaysAllow) {
                        Text("始终允许", style = AndmxTheme.typography.labelMedium, color = colors.accent)
                    }
                    Spacer(Modifier.width(Spacing.xs))
                }
                Button(
                    onClick = onApprove,
                    colors = if (riskLevel == Guardian.RiskLevel.CRITICAL)
                        ButtonDefaults.buttonColors(containerColor = colors.warning)
                    else ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Text("批准")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("拒绝", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun DiffPreviewSection(toolName: String, arguments: String, colors: com.andmx.ui.theme.AndmxColors) {
    var showFullDiff by remember { mutableStateOf(false) }

    // Parse path and patch/content from arguments
    val parsed = parseToolArgs(arguments)
    val path = parsed["path"] ?: "(未知文件)"
    val patchOrContent = parsed["patch"] ?: parsed["content"] ?: parsed["new_str"] ?: ""
    val oldStr = parsed["old_str"]

    Text("文件: $path", style = AndmxTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
    Spacer(Modifier.height(Spacing.xs))

    if (patchOrContent.isNotBlank()) {
        // Generate a simple line-level diff preview
        val diffLines = generateDiffPreview(toolName, patchOrContent, oldStr)

        Text("变更预览", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        Spacer(Modifier.height(2.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = colors.codeBackground,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.xs)) {
                val lines = diffLines.split("\n")
                val displayLines = if (showFullDiff) lines else lines.take(20)
                for (line in displayLines) {
                    DiffLine(line, colors)
                }
                if (lines.size > 20) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        if (showFullDiff) "收起" else "显示全部 ${lines.size} 行",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.accent,
                        modifier = Modifier.clickable(
                            onClick = { showFullDiff = !showFullDiff },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandPreviewSection(arguments: String, colors: com.andmx.ui.theme.AndmxColors) {
    val parsed = parseToolArgs(arguments)
    val command = parsed["command"] ?: arguments.take(200)

    Text("命令", style = AndmxTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
    Spacer(Modifier.height(Spacing.xs))
    CodeBlock(command, colors)
}

@Composable
private fun CodeBlock(text: String, colors: com.andmx.ui.theme.AndmxColors) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.codeBackground,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = AndmxTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.textPrimary,
            modifier = Modifier.padding(Spacing.xs),
            maxLines = 12,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiffLine(line: String, colors: com.andmx.ui.theme.AndmxColors) {
    val (bg, fg) = when {
        line.startsWith("+") -> colors.accent.copy(alpha = 0.08f) to colors.accent
        line.startsWith("-") -> colors.warning.copy(alpha = 0.08f) to colors.warning
        line.startsWith("@@") -> colors.sunken to colors.textTertiary
        else -> androidx.compose.ui.graphics.Color.Transparent to colors.textSecondary
    }
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Text(
            line.ifBlank { " " },
            style = AndmxTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = fg,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// ── Helpers ──────────────────────────────────────

private fun parseToolArgs(raw: String): Map<String, String> {
    return runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        obj.entries.associate { (k, v) ->
            k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        }
    }.getOrDefault(emptyMap())
}

private fun generateDiffPreview(toolName: String, patchOrContent: String, oldStr: String?): String {
    // For apply_patch: show the patch as-is (it's already in diff format)
    if (toolName == "apply_patch") {
        return patchOrContent.lines().take(100).joinToString("\n")
    }
    // For write_file: show all lines as additions
    if (toolName == "write_file" && oldStr == null) {
        return patchOrContent.lines().take(50).joinToString("\n") { "+$it" }
    }
    // For edit_file: show old → new
    if (toolName == "edit_file" && oldStr != null) {
        return buildString {
            oldStr.lines().take(20).forEach { appendLine("-$it") }
            patchOrContent.lines().take(20).forEach { appendLine("+$it") }
        }
    }
    return patchOrContent.lines().take(30).joinToString("\n") { "+$it" }
}

@Composable
private fun riskColor(
    level: Guardian.RiskLevel?,
    fallback: com.andmx.agent.ToolRisk,
    colors: com.andmx.ui.theme.AndmxColors,
) = when (level) {
    Guardian.RiskLevel.CRITICAL -> colors.warning
    Guardian.RiskLevel.HIGH -> colors.warning
    Guardian.RiskLevel.MEDIUM -> colors.accent
    Guardian.RiskLevel.LOW -> colors.accent
    null -> when (fallback) {
        com.andmx.agent.ToolRisk.NETWORK -> colors.accent
        com.andmx.agent.ToolRisk.EXECUTE -> colors.accent
        com.andmx.agent.ToolRisk.WRITE -> colors.accent
        com.andmx.agent.ToolRisk.READ -> colors.textSecondary
    }
}

private fun riskIcon(level: Guardian.RiskLevel?, fallback: com.andmx.agent.ToolRisk) = when (level) {
    Guardian.RiskLevel.CRITICAL -> Icons.Outlined.Dangerous
    Guardian.RiskLevel.HIGH -> Icons.Outlined.Warning
    Guardian.RiskLevel.MEDIUM -> Icons.Outlined.Info
    Guardian.RiskLevel.LOW -> Icons.Outlined.CheckCircle
    null -> when (fallback) {
        com.andmx.agent.ToolRisk.NETWORK -> Icons.Outlined.Language
        com.andmx.agent.ToolRisk.EXECUTE -> Icons.Outlined.Terminal
        com.andmx.agent.ToolRisk.WRITE -> Icons.Outlined.Edit
        com.andmx.agent.ToolRisk.READ -> Icons.Outlined.Description
    }
}

private fun riskLabel(level: Guardian.RiskLevel?, fallback: com.andmx.agent.ToolRisk) = when (level) {
    Guardian.RiskLevel.CRITICAL -> "严重"
    Guardian.RiskLevel.HIGH -> "高"
    Guardian.RiskLevel.MEDIUM -> "中"
    Guardian.RiskLevel.LOW -> "低"
    null -> when (fallback) {
        com.andmx.agent.ToolRisk.NETWORK -> "网络"
        com.andmx.agent.ToolRisk.EXECUTE -> "执行"
        com.andmx.agent.ToolRisk.WRITE -> "写入"
        com.andmx.agent.ToolRisk.READ -> "读取"
    }
}
