package com.andmx.ui2.chat

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import com.andmx.ui2.theme.AndMXType
import com.andmx.ui2.theme.Chrome
import com.andmx.ui2.theme.LocalMotion
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.ToolArgs
import com.andmx.agent.UpdatePlanTool

private val genericApprovalReasons = setOf(
    "High risk tools require explicit approval",
    "Tool has side effects and requires approval",
)

@Composable
fun ApprovalBanner(
    request: ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onScope: (ApprovalScope) -> Unit = {},
    suggestions: List<com.andmx.agent.permission.PermissionSuggestion> = emptyList(),
    onAcceptSuggestion: (com.andmx.agent.permission.PermissionSuggestion) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val proposed = remember(request.id) { ToolEditDiff.preview(request.toolName, request.arguments) }
    val command = remember(request.id) { ToolArgs.shellCommand(request.toolName, request.arguments) }
    val isCommand = ToolArgs.canonical(request.toolName) in setOf("shell", "git")
    val subject = if (isCommand) command.ifBlank { request.inputPreview } else request.inputPreview
    var showRules by remember(request.id) { mutableStateOf(false) }
    var showInput by remember(request.id) { mutableStateOf(false) }
    val canSetRule = request.kind == "tool" && request.rulePreview.isNotBlank()
    Surface(
        modifier = modifier.padding(horizontal = Chrome.gutter, vertical = 6.dp).fillMaxWidth(),
        shape = Chrome.panel,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(tr("等待确认"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(request.modeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (isCommand) tr("执行命令") else if (proposed != null) tr("拟议改动") else ToolPresentation.family(request.toolName).label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            when {
                proposed != null && proposed.lines.isNotEmpty() ->
                    InlineEditDiffPreview(proposed, isRunning = false, onOpen = null, maxHeight = 92.dp)
                subject.isNotBlank() -> Text(
                    subject,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().clip(Chrome.control)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .heightIn(max = 92.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                )
                else -> Text(request.summary, style = MaterialTheme.typography.bodySmall)
            }
            if (request.reason.isNotBlank() && request.reason !in genericApprovalReasons) {
                Text(
                    request.reason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (request.arguments.isNotBlank()) {
                    TextButton(onClick = { showInput = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(tr("完整参数"))
                    }
                }
                if (canSetRule || suggestions.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { showRules = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(tr("授权范围"))
                        }
                        DropdownMenu(expanded = showRules, onDismissRequest = { showRules = false }) {
                            if (canSetRule) {
                                Text(request.rulePreview, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                                ApprovalScope.entries.filter { it != ApprovalScope.ONCE }.forEach { scope ->
                                    if (scope.name.startsWith("PROJECT_") && request.projectKey == null) return@forEach
                                    val label = when (scope) {
                                        ApprovalScope.SESSION_ALLOW -> tr("允许本会话")
                                        ApprovalScope.PROJECT_ALLOW -> tr("允许本项目")
                                        ApprovalScope.PROJECT_ASK -> tr("本次允许，之后询问")
                                        ApprovalScope.SESSION_DENY -> tr("拒绝本会话")
                                        ApprovalScope.PROJECT_DENY -> tr("拒绝本项目")
                                        ApprovalScope.ONCE -> tr("仅本次允许")
                                    }
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        showRules = false
                                        onScope(scope)
                                    })
                                }
                            }
                            if (request.projectKey != null) suggestions.forEach { suggestion ->
                                DropdownMenuItem(text = { Text(tr(suggestion.label)) }, onClick = {
                                    showRules = false
                                    onAcceptSuggestion(suggestion)
                                })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onDeny) { Text(tr("拒绝")) }
                Button(onClick = onAllow) { Text(tr("仅本次允许")) }
            }
        }
    }
    if (showInput) {
        AlertDialog(
            onDismissRequest = { showInput = false },
            title = { Text(tr("完整参数")) },
            text = {
                SelectionContainer {
                    Text(
                        if (isCommand && command.isNotBlank()) {
                            command + "\n\n" + prettyToolArguments(request.arguments)
                        } else prettyToolArguments(request.arguments),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showInput = false }) { Text(tr("返回")) } },
        )
    }
}

@Composable
fun PlanStrip(
    steps: List<UpdatePlanTool.PlanStep>,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Chrome.gutter, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(tr("任务计划"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        steps.forEach { step ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (step.status) {
                        UpdatePlanTool.StepStatus.COMPLETED -> Icons.Outlined.CheckCircle
                        UpdatePlanTool.StepStatus.IN_PROGRESS -> Icons.Outlined.Schedule
                        UpdatePlanTool.StepStatus.PENDING -> Icons.Outlined.RadioButtonUnchecked
                    },
                    null,
                    Modifier.size(15.dp),
                    tint = when (step.status) {
                        UpdatePlanTool.StepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        UpdatePlanTool.StepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
                        UpdatePlanTool.StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    step.content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}


@Composable
fun SubAgentStrip(
    agents: List<ChatController.SubAgentUi>,
    modifier: Modifier = Modifier,
) {
    if (agents.isEmpty()) return
    val live = agents.filter { it.state == "RUNNING" || it.state == "WAITING" }
    val recent = if (live.isNotEmpty()) live else agents.take(3)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Chrome.gutter, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(tr("子代理 · ${agents.size}"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        recent.forEach { agent ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (agent.state) {
                        "COMPLETED" -> Icons.Outlined.CheckCircle
                        "FAILED", "CLOSED" -> Icons.Outlined.RadioButtonUnchecked
                        else -> Icons.Outlined.Schedule
                    },
                    null,
                    Modifier.size(15.dp),
                    tint = when (agent.state) {
                        "COMPLETED" -> MaterialTheme.colorScheme.primary
                        "FAILED" -> MaterialTheme.colorScheme.error
                        "RUNNING" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        agent.task,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        agent.state.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun ApprovalTimelineCard(item: ApprovalItem, modifier: Modifier = Modifier) {
    val label = when (item.status) {
        "allowed" -> tr("已允许")
        "denied" -> tr("已拒绝")
        "closed" -> tr("确认已结束")
        else -> tr("等待确认")
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = Chrome.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Shield, null, Modifier.size(14.dp),
            tint = if (item.status == "pending") MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "$label · ${item.toolName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SubAgentTimelineCard(
    item: SubAgentItem,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val running = item.state.equals("RUNNING", ignoreCase = true) ||
        item.state.equals("running", ignoreCase = true)
    val failed = item.state.equals("FAILED", ignoreCase = true) ||
        item.state.equals("failed", ignoreCase = true)
    var expanded by remember(item.id) { mutableStateOf(running || failed) }
    var userToggled by remember(item.id) { mutableStateOf(false) }
    var wasRunning by remember(item.id) { mutableStateOf(running) }
    LaunchedEffect(running, failed) {
        if (userToggled) {
            wasRunning = running
            return@LaunchedEffect
        }
        if (running || failed) {
            expanded = true
        } else if (wasRunning) {
            kotlinx.coroutines.delay(300)
            if (!userToggled) expanded = false
        }
        wasRunning = running
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Chrome.gutter, vertical = 3.dp)
            .clickable {
                userToggled = true
                expanded = !expanded
            },
        shape = RoundedCornerShape(12.dp),
        color = when {
            failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
            running -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
            else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
        },
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        item.state.equals("COMPLETED", true) -> Icons.Outlined.CheckCircle
                        failed -> Icons.Outlined.RadioButtonUnchecked
                        else -> Icons.Outlined.Schedule
                    },
                    null,
                    Modifier.size(16.dp),
                    tint = when {
                        item.state.equals("COMPLETED", true) -> MaterialTheme.colorScheme.primary
                        failed -> MaterialTheme.colorScheme.error
                        running -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(tr("子代理 · ${item.state.lowercase()}"),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = AndMXType.cardMeta.fontSize),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                item.task,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            AnimatedVisibility(
                visible = expanded && item.activities.isNotEmpty(),
                enter = motion.riseIn(),
                exit = motion.riseOut(),
            ) {
                Column(Modifier.padding(top = 6.dp)) {
                    item.activities.forEach { act ->
                        Text(
                            act,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = AndMXType.micro.fontSize,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded && item.result.isNotBlank(),
                enter = motion.riseIn(),
                exit = motion.riseOut(),
            ) {
                Text(
                    item.result.take(4000),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = AndMXType.cardMeta.fontSize,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}


@Composable
fun McpStatusStrip(
    servers: List<com.andmx.mcp.McpManager.Connected>,
    modifier: Modifier = Modifier,
) {
    if (servers.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Chrome.gutter, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "MCP",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        servers.forEach { s ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    "${s.name} · ${s.tools.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun ContextUsageBar(
    tokens: Int,
    window: Int,
    lastTurnTokens: Int = 0,
    modifier: Modifier = Modifier,
) {
    if (tokens <= 0 || window <= 0) return
    val ratio = (tokens.toFloat() / window.toFloat()).coerceIn(0f, 1f)
    val pct = (ratio * 100).toInt()
    val color = when {
        ratio >= 0.9f -> MaterialTheme.colorScheme.error
        ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Chrome.gutter, vertical = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (lastTurnTokens > 0) "上下文 · 上轮 $lastTurnTokens" else tr("上下文"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "~$tokens / $window · ${pct}%",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
fun GoalStrip(
    goal: com.andmx.agent.ConversationGoal,
    modifier: Modifier = Modifier,
) {
    if (!goal.hasGoal) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Chrome.gutter, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(tr("目标 · ${goal.status.label}"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            goal.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (goal.tokenBudget > 0) {
            Text(tr("预算 ${goal.tokensUsed}/${goal.tokenBudget}"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
