package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.UpdatePlanTool

@Composable
fun ApprovalBanner(
    request: ChatController.ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Shield,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "等待确认 · ${request.modeLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                request.summary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDeny) { Text("拒绝") }
                Button(onClick = onAllow) { Text("允许") }
            }
        }
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
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "任务计划",
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
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
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
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "子代理 · ${agents.size}",
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
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
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
fun ApprovalTimelineCard(
    item: ApprovalItem,
    onAllow: (() -> Unit)? = null,
    onDeny: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        color = when (item.status) {
            "allowed" -> MaterialTheme.colorScheme.primaryContainer
            "denied" -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.tertiaryContainer
        },
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Shield,
                    null,
                    Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (item.status) {
                        "allowed" -> "已允许 · ${item.modeLabel}"
                        "denied" -> "已拒绝 · ${item.modeLabel}"
                        else -> "等待确认 · ${item.modeLabel}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                item.summary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (item.status == "pending" && onAllow != null && onDeny != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDeny) { Text("拒绝") }
                    Button(onClick = onAllow) { Text("允许") }
                }
            }
        }
    }
}

@Composable
fun SubAgentTimelineCard(
    item: SubAgentItem,
    modifier: Modifier = Modifier,
) {
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
            .padding(horizontal = 12.dp, vertical = 3.dp)
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
                Text(
                    "子代理 · ${item.state.lowercase()}",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
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
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                maxLines = if (expanded) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            AnimatedVisibility(
                visible = expanded && item.result.isNotBlank(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Text(
                    item.result.take(4000),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
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
            .padding(horizontal = 12.dp, vertical = 3.dp)
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
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (lastTurnTokens > 0) "上下文 · 上轮 $lastTurnTokens" else "上下文",
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
    goal: com.andmx.ui.conversation.ConversationGoal,
    modifier: Modifier = Modifier,
) {
    if (!goal.hasGoal) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "目标 · ${goal.status.label}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            goal.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (goal.tokenBudget > 0) {
            Text(
                "预算 ${goal.tokensUsed}/${goal.tokenBudget}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
