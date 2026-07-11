package com.andmx.ui.workbench

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.ToolArgs
import com.andmx.ui.conversation.ChatItem
import com.andmx.ui.conversation.toolArtifactSummary
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

internal data class LatestTurnSummary(
    val title: String,
    val detail: String,
    val statusLabel: String,
    val running: Boolean,
    val icon: ImageVector,
    val focusTarget: WorkbenchFocusTarget? = null,
    val imageCount: Int = 0,
)

internal fun buildLatestTurnSummary(
    sessionState: ExecutionSessionState,
    items: List<ChatItem>,
    busy: Boolean,
): LatestTurnSummary? {
    if (items.isEmpty() && sessionState.turns.isEmpty() && sessionState.backgroundTasks.isEmpty()) return null
    val currentTurn = sessionState.turns.firstOrNull { it.id == sessionState.activeTurnId } ?: sessionState.turns.lastOrNull()
    val currentArtifacts = currentTurn?.artifacts.orEmpty()
    fun resolveTarget(target: FocusTarget, artifacts: List<ArtifactState> = currentArtifacts): WorkbenchFocusTarget? =
        target.toWorkbenchFocusTarget(artifacts.ifEmpty { sessionState.artifacts })
    val pendingApproval = items.asReversed().filterIsInstance<ChatItem.Approval>().firstOrNull { !it.resolved }
    if (pendingApproval != null || currentTurn?.status == TurnStatus.WAITING_APPROVAL) {
        return LatestTurnSummary(
            title = "等待授权",
            detail = pendingApproval?.summary?.ifBlank {
                currentTurn?.tools?.lastOrNull()?.preview ?: currentTurn?.userText.orEmpty()
            } ?: currentTurn?.tools?.lastOrNull()?.preview ?: currentTurn?.userText.orEmpty(),
            statusLabel = "需要操作",
            running = false,
            icon = Icons.Outlined.HourglassBottom,
            focusTarget = currentTurn?.tools?.lastOrNull()?.let { resolveTarget(it.focusTarget, it.artifacts) },
        )
    }
    val runningTool = currentTurn?.tools?.lastOrNull {
        it.status == ToolExecutionStatus.RUNNING || it.status == ToolExecutionStatus.WAITING_APPROVAL
    }
    if (runningTool != null) {
        val images = runningTool.artifacts.count { it.kind == ArtifactKind.IMAGE }
        return LatestTurnSummary(
            title = "正在执行 ${runningTool.name}",
            detail = runningTool.preview.ifBlank { "等待工具输出" },
            statusLabel = "执行中",
            running = true,
            icon = Icons.Outlined.Bolt,
            focusTarget = resolveTarget(runningTool.focusTarget, runningTool.artifacts),
            imageCount = images,
        )
    }
    val activeBackground = sessionState.backgroundTasks.lastOrNull {
        it.status == BackgroundTaskStatus.RUNNING || it.status == BackgroundTaskStatus.WAITING
    }
    if (activeBackground != null) {
        return LatestTurnSummary(
            title = activeBackground.title,
            detail = activeBackground.summary.ifBlank { activeBackground.detail }.ifBlank { "后台任务运行中" },
            statusLabel = if (activeBackground.status == BackgroundTaskStatus.WAITING) "等待中" else "后台执行",
            running = true,
            icon = Icons.Outlined.Bolt,
            focusTarget = resolveTarget(activeBackground.focusTarget),
        )
    }
    val latestTool = currentTurn?.tools?.lastOrNull()
    if (latestTool != null) {
        val images = latestTool.artifacts.count { it.kind == ArtifactKind.IMAGE }
        return LatestTurnSummary(
            title = "${latestTool.name} 已完成",
            detail = latestTool.preview.ifBlank { latestTool.output }.ifBlank { "工具已返回" },
            statusLabel = when (latestTool.status) {
                ToolExecutionStatus.FAILED -> "失败"
                ToolExecutionStatus.CANCELLED -> "已取消"
                else -> if (busy) "继续处理中" else "已完成"
            },
            running = busy && currentTurn?.status == TurnStatus.RUNNING,
            icon = if (latestTool.error || latestTool.status == ToolExecutionStatus.FAILED) Icons.Outlined.Info else Icons.Outlined.CheckCircle,
            focusTarget = resolveTarget(latestTool.focusTarget, latestTool.artifacts),
            imageCount = images,
        )
    }
    val assistantText = currentTurn?.assistantStreamingText
        ?.takeIf { it.isNotBlank() }
        ?: currentTurn?.assistantText.orEmpty()
    if (currentTurn != null && (assistantText.isNotBlank() || busy)) {
        return LatestTurnSummary(
            title = if (!currentTurn.assistantDone || busy) "最新回复" else "已更新回复",
            detail = assistantText.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "模型正在生成内容" },
            statusLabel = if (!currentTurn.assistantDone || busy) "生成中" else "完成",
            running = !currentTurn.assistantDone || busy,
            icon = if (!currentTurn.assistantDone || busy) Icons.Outlined.Bolt else Icons.Outlined.CheckCircle,
            focusTarget = resolveTarget(currentTurn.focusTarget),
        )
    }
    val latestUser = currentTurn?.userText
        ?: items.asReversed().filterIsInstance<ChatItem.User>().firstOrNull()?.text
        ?: return null
    return LatestTurnSummary(
        title = "当前任务",
        detail = latestUser.lineSequence().firstOrNull()?.trim().orEmpty(),
        statusLabel = if (busy) "处理中" else "待继续",
        running = busy,
        icon = if (busy) Icons.Outlined.Bolt else Icons.Outlined.Info,
    )
}

@Composable
internal fun LatestTurnStrip(
    summary: LatestTurnSummary,
    onOpenTarget: (WorkbenchFocusTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val pulse = if (summary.running) {
        rememberInfiniteTransition(label = "latestTurnPulse").animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "latestTurnPulseAlpha",
        ).value
    } else {
        1f
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, Radii.md)
            .background(colors.surface, Radii.md)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(pulse)
                    .background(if (summary.running) colors.accent else colors.textTertiary, CircleShape),
            )
            Spacer(Modifier.size(Spacing.sm))
            Icon(summary.icon, contentDescription = null, tint = if (summary.running) colors.accent else colors.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(Spacing.xs))
            Text(
                summary.title,
                style = AndmxTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                summary.statusLabel,
                style = AndmxTheme.typography.labelSmall,
                color = if (summary.running) colors.accent else colors.textTertiary,
            )
        }
        Text(
            summary.detail,
            style = AndmxTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            summary.focusTarget?.let { target ->
                SummaryChip(
                    label = "打开${focusTargetLabel(target)}",
                    onClick = { onOpenTarget(target) },
                )
            }
            if (summary.imageCount > 0) {
                SummaryChip(label = "${summary.imageCount} 张图")
            }
        }
    }
}

@Composable
private fun SummaryChip(label: String, onClick: (() -> Unit)? = null) {
    val colors = AndmxTheme.colors
    Text(
        text = label,
        style = AndmxTheme.typography.labelSmall,
        color = if (onClick != null) colors.accent else colors.textTertiary,
        modifier = Modifier
            .background(colors.sunken, Radii.pill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}
