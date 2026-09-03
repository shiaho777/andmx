package com.andmx.ui2.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.ConversationGoal
import com.andmx.ui2.theme.LocalMotion
import com.andmx.workspace.GitBaseline

@Composable
fun StatusCapsule(
    goal: ConversationGoal,
    planSteps: List<UpdatePlanTool.PlanStep>,
    subAgents: List<SubAgentItem>,
    gitInfo: GitBaseline.GitInfo?,
    contextTokens: Int,
    contextWindow: Int,
    breakdown: List<ChatController.ContextBreakdownItem> = emptyList(),
    onCompress: () -> Unit,
    displayMode: String = "auto",
    modifier: Modifier = Modifier,
) {
    val hasSteps = planSteps.isNotEmpty()
    val runningAgents = subAgents.count { it.state.equals("running", true) || it.state.equals("streaming", true) }
    val doneAgents = subAgents.count { it.state.equals("completed", true) || it.state.equals("done", true) }
    val contextPct = if (contextWindow > 0) (contextTokens.toFloat() / contextWindow).coerceIn(0f, 1f) else 0f

    val anythingToShow = goal.hasGoal || hasSteps || subAgents.isNotEmpty() ||
        (gitInfo?.branch?.isNotBlank() == true) || contextPct > 0.001f
    if (!anythingToShow) return

    // ZCode summaryPanel.displayMode 对齐：auto 默认收起（用户可手动展开），
    // expanded 强制展开，collapsed 强制收起。
    var expanded by remember(displayMode) { mutableStateOf(displayMode == "expanded") }
    val motion = LocalMotion.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = motion.defaultEffects,
        label = "statusChevron",
    )

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val done = planSteps.count { it.status == UpdatePlanTool.StepStatus.COMPLETED }
            val total = planSteps.size
            if (goal.hasGoal) StatusDot(Icons.Outlined.Flag, MaterialTheme.colorScheme.tertiary)
            if (hasSteps) {
                StatusDot(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary)
                Text(
                    "$done/$total",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (runningAgents > 0) {
                StatusDot(Icons.Outlined.AccountTree, MaterialTheme.colorScheme.secondary)
                Text(
                    "$runningAgents 代理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val branch = gitInfo?.branch.orEmpty()
            if (branch.isNotBlank()) {
                StatusDot(Icons.Outlined.Commit, MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    branch,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                if ((gitInfo?.dirtyFileCount ?: 0) > 0) {
                    Text(
                        "●${gitInfo?.dirtyFileCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    )
                }
                val ahead = gitInfo?.ahead ?: 0
                val behind = gitInfo?.behind ?: 0
                if (ahead > 0 || behind > 0) {
                    Text(
                        (if (ahead > 0) "↑$ahead " else "") + (if (behind > 0) "↓$behind" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (contextPct > 0.001f) {
                Text(
                    "${(contextPct * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (contextPct > 0.85f) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                Modifier.size(14.dp).rotate(chevronRotation).alpha(0.7f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = motion.defaultExpand) + fadeIn(motion.defaultEffects),
            exit = shrinkVertically(animationSpec = motion.defaultExpand) + fadeOut(motion.defaultEffects),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (goal.hasGoal) GoalSection(goal)
                if (hasSteps) ProgressSection(planSteps)
                if (subAgents.isNotEmpty()) AgentsSection(runningAgents, doneAgents, subAgents)
                if (gitInfo?.branch?.isNotBlank() == true) GitSection(gitInfo)
                ContextSection(contextTokens, contextWindow, contextPct, breakdown, onCompress)
            }
        }
    }
}

@Composable
private fun StatusDot(icon: ImageVector, tint: Color) {
    Icon(icon, null, Modifier.size(13.dp), tint = tint.copy(alpha = 0.85f))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun GoalSection(goal: ConversationGoal) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Flag, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(6.dp))
            SectionLabel("目标")
        }
        Spacer(Modifier.height(3.dp))
        Text(
            goal.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
        if (goal.tokenBudget > 0) {
            Spacer(Modifier.height(4.dp))
            val used = goal.tokensUsed.toFloat() / goal.tokenBudget.toFloat()
            LinearProgressIndicator(
                progress = { used.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (goal.isBudgetExhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                "${goal.tokensUsed}/${goal.tokenBudget} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ProgressSection(steps: List<UpdatePlanTool.PlanStep>) {
    Column {
        SectionLabel("进度")
        Spacer(Modifier.height(4.dp))
        steps.forEach { step ->
            Row(
                Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                when (step.status) {
                    UpdatePlanTool.StepStatus.COMPLETED ->
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    UpdatePlanTool.StepStatus.IN_PROGRESS ->
                        Icon(Icons.Outlined.Timer, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                    else ->
                        Icon(Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    step.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = if (step.status == UpdatePlanTool.StepStatus.COMPLETED) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AgentsSection(running: Int, done: Int, agents: List<SubAgentItem>) {
    Column {
        SectionLabel("子代理 · $running 运行 / $done 完成")
        Spacer(Modifier.height(4.dp))
        agents.take(6).forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(
                        when {
                            a.state.equals("running", true) || a.state.equals("streaming", true) -> MaterialTheme.colorScheme.tertiary
                            a.state.equals("failed", true) -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    a.task.ifBlank { a.id },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (agents.size > 6) {
            Text(
                "… 另有 ${agents.size - 6} 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun GitSection(info: GitBaseline.GitInfo) {
    Column {
        SectionLabel("Git")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Commit, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                info.branch,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (info.dirtyFileCount > 0) {
                Text(
                    "${info.dirtyFileCount} 改动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                )
            } else {
                Text("干净", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
            }
        }
        if (info.ahead > 0 || info.behind > 0) {
            Text(
                buildString {
                    if (info.ahead > 0) append("领先 ${info.ahead} 提交")
                    if (info.ahead > 0 && info.behind > 0) append(" · ")
                    if (info.behind > 0) append("落后 ${info.behind} 提交")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ContextSection(
    tokens: Int,
    window: Int,
    pct: Float,
    breakdown: List<ChatController.ContextBreakdownItem>,
    onCompress: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("上下文")
            Spacer(Modifier.weight(1f))
            Text(
                "${formatTokens(tokens)} / ${formatTokens(window)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = when {
                pct > 0.85f -> MaterialTheme.colorScheme.error
                pct > 0.6f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        if (breakdown.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "上下文来源",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
            breakdown.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { item.percent.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        "${(item.percent * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.width(38.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        AssistChip(
            onClick = onCompress,
            label = { Text("压缩上下文", style = MaterialTheme.typography.labelSmall) },
            leadingIcon = { Icon(Icons.Outlined.Timer, null, Modifier.size(14.dp)) },
            colors = AssistChipDefaults.assistChipColors(),
        )
    }
}

private fun formatTokens(n: Int): String = when {
    n >= 1000 -> "%.1fk".format(n / 1000f)
    else -> n.toString()
}
