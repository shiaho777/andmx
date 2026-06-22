package com.andmx.ui.workbench

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andmx.agent.ApprovalMode
import com.andmx.agent.Guardian
import com.andmx.exec.policy.ExecPolicy
import com.andmx.exec.policy.NetworkPolicy
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Spacing

/**
 * Security & policy panel — shows current sandbox mode, approval policy,
 * exec rules, and network rules with interactive rule browsing.
 */
@Composable
fun SecurityPanel(
    approvalMode: ApprovalMode,
    execPolicy: ExecPolicy,
    networkPolicy: NetworkPolicy,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    var showExecRules by remember { mutableStateOf(false) }
    var showNetworkRules by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.sm),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Security,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.accent,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "安全策略",
                style = AndmxTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // Approval mode card
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.sunken,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.sm)) {
                InfoRow("授权模式", when (approvalMode) {
                    ApprovalMode.FULL -> "完全访问"
                    ApprovalMode.ASK -> "按需批准"
                    ApprovalMode.READ_ONLY -> "只读"
                }, modeColor(approvalMode, colors), colors)

                Spacer(Modifier.height(Spacing.xs))
                Text(
                    when (approvalMode) {
                        ApprovalMode.FULL -> "所有工具自动执行，不询问"
                        ApprovalMode.ASK -> "读取自动执行，写入/执行/网络需确认"
                        ApprovalMode.READ_ONLY -> "仅允许读取操作"
                    },
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        // Guardian risk matrix
        Text(
            "Guardian 风险评估",
            style = AndmxTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.xs))
        GuardianRiskMatrix(approvalMode, colors)

        Spacer(Modifier.height(Spacing.sm))

        // ExecPolicy rules
        val rules = execPolicy.rules()
        val denyCount = rules.count { it.action == ExecPolicy.RuleAction.DENY }
        val promptCount = rules.count { it.action == ExecPolicy.RuleAction.PROMPT }
        val allowCount = rules.count { it.action == ExecPolicy.RuleAction.ALLOW }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.sunken,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showExecRules = !showExecRules },
        ) {
            Column(modifier = Modifier.padding(Spacing.sm)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (showExecRules) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.textTertiary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        "ExecPolicy 规则",
                        style = AndmxTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    // Rule counts
                    RuleCountBadge("拒绝", denyCount, colors.warning, colors)
                    Spacer(Modifier.width(Spacing.xs))
                    RuleCountBadge("询问", promptCount, colors.accent, colors)
                    Spacer(Modifier.width(Spacing.xs))
                    RuleCountBadge("允许", allowCount, colors.textTertiary, colors)
                }

                AnimatedVisibility(showExecRules) {
                    Column(modifier = Modifier.padding(top = Spacing.sm)) {
                        // Group by action
                        val grouped = rules.groupBy { it.action }
                        for (action in listOf(ExecPolicy.RuleAction.DENY, ExecPolicy.RuleAction.PROMPT, ExecPolicy.RuleAction.ALLOW)) {
                            val actionRules = grouped[action] ?: continue
                            if (actionRules.isEmpty()) continue

                            val (actionLabel, actionColor) = when (action) {
                                ExecPolicy.RuleAction.DENY -> "拒绝" to colors.warning
                                ExecPolicy.RuleAction.PROMPT -> "询问" to colors.accent
                                ExecPolicy.RuleAction.ALLOW -> "允许" to colors.textTertiary
                            }

                            Text(
                                actionLabel,
                                style = AndmxTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = actionColor,
                                modifier = Modifier.padding(top = Spacing.xs, bottom = 2.dp),
                            )
                            for (rule in actionRules.take(15)) {
                                Text(
                                    "· ${rule.reason ?: rule.pattern.take(40)}",
                                    style = AndmxTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = Spacing.sm),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            if (actionRules.size > 15) {
                                Text(
                                    "  …还有 ${actionRules.size - 15} 条",
                                    style = AndmxTheme.typography.labelSmall,
                                    color = colors.textTertiary,
                                    modifier = Modifier.padding(start = Spacing.sm),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        // Network policy
        val hostsEntries = networkPolicy.hostsEntries()
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.sunken,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showNetworkRules = !showNetworkRules },
        ) {
            Column(modifier = Modifier.padding(Spacing.sm)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (showNetworkRules) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.textTertiary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        "网络策略",
                        style = AndmxTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (hostsEntries.isNotBlank()) "有阻断" else "无限制",
                        style = AndmxTheme.typography.labelSmall,
                        color = if (hostsEntries.isNotBlank()) colors.warning else colors.textTertiary,
                    )
                }

                AnimatedVisibility(showNetworkRules) {
                    if (hostsEntries.isNotBlank()) {
                        Text(
                            hostsEntries.take(500),
                            style = AndmxTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = Spacing.xs),
                            maxLines = 10,
                        )
                    } else {
                        Text(
                            "所有域名均可访问。配置 NetworkPolicy 可限制 Agent 的网络访问范围。",
                            style = AndmxTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuardianRiskMatrix(mode: ApprovalMode, colors: com.andmx.ui.theme.AndmxColors) {
    val levels = Guardian.RiskLevel.entries
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (level in levels) {
            val auth = when (mode) {
                ApprovalMode.FULL -> Guardian.UserAuthorization.HIGH
                ApprovalMode.ASK -> Guardian.UserAuthorization.LOW
                ApprovalMode.READ_ONLY -> Guardian.UserAuthorization.NONE
            }
            val decision = when {
                level == Guardian.RiskLevel.CRITICAL -> Guardian.Decision.DENY
                level == Guardian.RiskLevel.HIGH && auth < Guardian.UserAuthorization.MEDIUM -> Guardian.Decision.DENY
                level == Guardian.RiskLevel.HIGH -> Guardian.Decision.PROMPT
                level == Guardian.RiskLevel.MEDIUM && auth < Guardian.UserAuthorization.LOW -> Guardian.Decision.PROMPT
                else -> Guardian.Decision.ALLOW
            }
            val (color, label) = when (decision) {
                Guardian.Decision.ALLOW -> colors.accent to "允许"
                Guardian.Decision.PROMPT -> colors.warning to "询问"
                Guardian.Decision.DENY -> colors.warning to "拒绝"
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = color.copy(alpha = 0.12f),
                ) {
                    Text(
                        when (level) {
                            Guardian.RiskLevel.CRITICAL -> "严重"
                            Guardian.RiskLevel.HIGH -> "高"
                            Guardian.RiskLevel.MEDIUM -> "中"
                            Guardian.RiskLevel.LOW -> "低"
                        },
                        style = AndmxTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(label, style = AndmxTheme.typography.labelSmall, color = color)
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    colors: com.andmx.ui.theme.AndmxColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AndmxTheme.typography.bodySmall, color = colors.textSecondary)
        Text(value, style = AndmxTheme.typography.bodySmall, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RuleCountBadge(
    label: String,
    count: Int,
    color: androidx.compose.ui.graphics.Color,
    colors: com.andmx.ui.theme.AndmxColors,
) {
    Text(
        "$label $count",
        style = AndmxTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun modeColor(mode: ApprovalMode, colors: com.andmx.ui.theme.AndmxColors) = when (mode) {
    ApprovalMode.FULL -> colors.warning
    ApprovalMode.ASK -> colors.accent
    ApprovalMode.READ_ONLY -> colors.accent
}
