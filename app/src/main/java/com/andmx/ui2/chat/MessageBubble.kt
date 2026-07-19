package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onRegenerate: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    isEditing: Boolean = false,
) {
    val isUser = message.role == "user"
    if (isUser) {
        UserProcessBubble(
            message = message,
            modifier = modifier,
            onCopy = onCopy,
            onEdit = onEdit,
            isEditing = isEditing,
        )
    } else {
        AssistantProcessBlock(
            message = message,
            modifier = modifier,
            onRegenerate = onRegenerate,
            onCopy = onCopy,
            onBranch = onBranch,
        )
    }
}

@Composable
private fun UserProcessBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    isEditing: Boolean = false,
) {
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxBubble)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (isEditing) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = if (isEditing) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
        }
        if (message.content.isNotBlank() && (onCopy != null || onEdit != null || message.createdAt > 0L || message.sortKey > 0L)) {
            UserActionBar(
                createdAt = message.createdAt.takeIf { it > 0L } ?: message.sortKey,
                onCopy = onCopy,
                onEdit = onEdit,
                isEditing = isEditing,
            )
        }
    }
}

@Composable
private fun UserActionBar(
    createdAt: Long,
    onCopy: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    isEditing: Boolean,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onCopy != null) {
            ActionChip(
                icon = Icons.Outlined.ContentCopy,
                label = "复制",
                onClick = onCopy,
            )
        }
        if (onEdit != null) {
            ActionChip(
                icon = Icons.Outlined.Edit,
                label = if (isEditing) "编辑中" else "编辑",
                onClick = onEdit,
            )
        }
        val timeLabel = formatHm(createdAt)
        if (timeLabel.isNotBlank()) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = muted.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun AssistantProcessBlock(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onRegenerate: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
) {
    val process = message.isProcess
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = if (process) 3.dp else 7.dp,
                bottom = if (process) 2.dp else 5.dp,
            ),
    ) {
        StreamingText(
            text = message.content,
            isStreaming = message.isStreaming,
            muted = process,
        )
        val showActions = !process && !message.isStreaming && message.content.isNotBlank()
        if (showActions && (onCopy != null || onBranch != null || onRegenerate != null || message.completedAt > 0L || message.createdAt > 0L)) {
            AssistantActionBar(
                createdAt = message.createdAt.takeIf { it > 0L } ?: message.sortKey,
                completedAt = message.completedAt,
                onCopy = onCopy,
                onBranch = onBranch,
                onRegenerate = onRegenerate,
            )
        }
    }
}

@Composable
private fun AssistantActionBar(
    createdAt: Long,
    completedAt: Long,
    onCopy: (() -> Unit)?,
    onBranch: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (onCopy != null) {
                ActionChip(
                    icon = Icons.Outlined.ContentCopy,
                    label = "复制",
                    onClick = onCopy,
                )
            }
            if (onBranch != null) {
                ActionChip(
                    icon = Icons.Outlined.AccountTree,
                    label = "分支",
                    onClick = onBranch,
                )
            }
            if (onRegenerate != null) {
                ActionChip(
                    icon = Icons.Outlined.Refresh,
                    label = "重新生成",
                    onClick = onRegenerate,
                )
            }
        }
        val timeLabel = formatMessageTime(createdAt, completedAt)
        if (timeLabel.isNotBlank()) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = muted.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

private fun formatMessageTime(createdAt: Long, completedAt: Long): String {
    val start = formatHm(createdAt)
    val end = formatHm(completedAt)
    return when {
        start.isBlank() && end.isBlank() -> ""
        end.isBlank() -> start
        start.isBlank() || start == end -> end
        else -> "$start → $end"
    }
}

private fun formatHm(ms: Long): String {
    if (ms <= 0L) return ""
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}
