package com.andmx.ui.workbench

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.UpdatePlanTool
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow

/**
 * Live plan panel — renders the agent's current task plan.
 *
 * Features:
 * - Progress bar with percentage
 * - Collapsible (global + per-step)
 * - Step status icons (completed/in_progress/pending)
 * - Empty state with guidance
 * - Animated transitions
 * - Uses AndmxTheme design system
 */
@Composable
fun PlanPanel(
    planState: StateFlow<List<UpdatePlanTool.PlanStep>>,
    modifier: Modifier = Modifier,
) {
    val steps by planState.collectAsState()
    val colors = AndmxTheme.colors
    var expanded by remember { mutableStateOf(true) }

    if (steps.isEmpty()) return

    val completed = steps.count { it.status == UpdatePlanTool.StepStatus.COMPLETED }
    val inProgress = steps.count { it.status == UpdatePlanTool.StepStatus.IN_PROGRESS }
    val progress = if (steps.isNotEmpty()) completed.toFloat() / steps.size else 0f
    val percent = (progress * 100).toInt()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.sunken.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = colors.accent,
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    "执行计划",
                    style = AndmxTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.weight(1f))

                // Progress text
                if (inProgress > 0) {
                    Text(
                        "$completed/${steps.size} · 进行中",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.accent,
                    )
                } else if (completed == steps.size) {
                    Text(
                        "✓ 完成",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.accent,
                    )
                } else {
                    Text(
                        "$completed/${steps.size}",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }

                Spacer(Modifier.width(Spacing.xs))

                // Expand/collapse toggle
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(16.dp),
                        tint = colors.textTertiary,
                    )
                }
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = if (completed == steps.size) colors.accent else colors.accent,
                trackColor = colors.border,
            )

            // Steps
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    steps.forEachIndexed { index, step ->
                        PlanStepRow(step, index, colors)
                        if (index < steps.size - 1) {
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanStepRow(
    step: UpdatePlanTool.PlanStep,
    index: Int,
    colors: com.andmx.ui.theme.AndmxColors,
) {
    var expanded by remember(step.content) { mutableStateOf(false) }

    val (icon, iconColor) = when (step.status) {
        UpdatePlanTool.StepStatus.COMPLETED -> Icons.Outlined.CheckCircle to colors.accent
        UpdatePlanTool.StepStatus.IN_PROGRESS -> Icons.Outlined.Pending to colors.warning
        UpdatePlanTool.StepStatus.PENDING -> Icons.Outlined.RadioButtonUnchecked to colors.textTertiary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = Spacing.xs, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(14.dp)
                .padding(top = 1.dp),
            tint = iconColor,
        )
        Spacer(Modifier.width(Spacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.content,
                style = AndmxTheme.typography.bodySmall,
                color = when (step.status) {
                    UpdatePlanTool.StepStatus.COMPLETED -> colors.textTertiary
                    UpdatePlanTool.StepStatus.IN_PROGRESS -> colors.textPrimary
                    UpdatePlanTool.StepStatus.PENDING -> colors.textSecondary
                },
                textDecoration = if (step.status == UpdatePlanTool.StepStatus.COMPLETED)
                    TextDecoration.LineThrough
                else null,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (step.status == UpdatePlanTool.StepStatus.IN_PROGRESS)
                    FontWeight.Medium
                else null,
            )
            AnimatedVisibility(expanded) {
                Text(
                    text = when (step.status) {
                        UpdatePlanTool.StepStatus.COMPLETED -> "已完成"
                        UpdatePlanTool.StepStatus.IN_PROGRESS -> "正在执行…"
                        UpdatePlanTool.StepStatus.PENDING -> "等待中"
                    },
                    style = AndmxTheme.typography.labelSmall,
                    color = iconColor,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        // Step number
        Text(
            text = "${index + 1}",
            style = AndmxTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}
