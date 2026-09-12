package com.andmx.ui2.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.ui2.theme.LocalMotion

/**
 * Goal 完成度验证时间线行（ZCode goalVerificationTimeline 对齐）：
 * 一轮 verifier 判定显示为一行「目标验证 #N · 验证中/未通过/已完成」，
 * 未通过时附 reason 与驱动下一轮续跑的 nextAction。
 */
@Composable
fun GoalVerifyRow(item: GoalVerifyItem) {
    var expanded by remember(item.sortKey) { mutableStateOf(item.passed == false) }
    val motion = LocalMotion.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = motion.defaultEffects,
        label = "goalVerifyChevron",
    )
    val running = item.passed == null
    val statusText = when (item.passed) {
        null -> "验证中"
        true -> "已完成"
        false -> "未通过 · 继续迭代"
    }
    val statusColor = when (item.passed) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        true -> MaterialTheme.colorScheme.tertiary
        false -> MaterialTheme.colorScheme.primary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Flag,
                null,
                Modifier.size(15.dp),
                tint = statusColor.copy(alpha = if (running) 0.6f else 0.85f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "目标验证 #${item.iteration} · $statusText",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = statusColor.copy(alpha = if (running) 0.72f else 0.9f),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                Modifier
                    .padding(start = 6.dp)
                    .size(16.dp)
                    .rotate(rotation)
                    .alpha(if (expanded || running) 0.9f else 0.4f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        AnimatedVisibility(
            visible = expanded && (item.reason.isNotBlank() || item.nextAction.isNotBlank()),
            enter = expandVertically(animationSpec = motion.defaultExpand) + fadeIn(motion.defaultEffects),
            exit = shrinkVertically(animationSpec = motion.defaultExpand) + fadeOut(motion.defaultEffects),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 23.dp, top = 1.dp, bottom = 4.dp),
            ) {
                if (item.reason.isNotBlank()) {
                    Text(
                        text = item.reason,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.nextAction.isNotBlank()) {
                    Text(
                        text = "下一步：${item.nextAction}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
