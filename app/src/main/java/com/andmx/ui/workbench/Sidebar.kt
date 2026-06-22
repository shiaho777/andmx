package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Update
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.ui.conversation.GoalPhase
import com.andmx.ui.conversation.label
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

data class ConversationItem(
    val id: Long,
    val title: String,
    val time: String,
    val muted: Boolean = false,
    val goalText: String = "",
    val goalPhase: GoalPhase = GoalPhase.EMPTY,
    val goalNote: String = "",
)
data class ProjectGroup(val name: String, val conversations: List<ConversationItem>)

@Composable
fun Sidebar(
    groups: List<ProjectGroup>,
    activeId: Long?,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    onPlugins: () -> Unit,
    onAutomations: () -> Unit,
    onSettings: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    /** Open the project picker/creator dialog. */
    onProjectHeaderClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.sidebar)
            .padding(horizontal = Spacing.sm),
    ) {
        Spacer(Modifier.height(Spacing.md))

        NavRow(Icons.Outlined.ChatBubbleOutline, "新对话", onClick = onNewChat)
        NavRow(Icons.Outlined.Search, "搜索", onClick = onSearch)
        NavRow(Icons.Outlined.Extension, "插件", onClick = onPlugins)
        NavRow(Icons.Outlined.AutoAwesome, "自动化", onClick = onAutomations)

        Spacer(Modifier.height(Spacing.sm))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = Spacing.xs),
        ) {
            if (groups.isEmpty()) {
                item {
                    Text(
                        "还没有对话",
                        style = AndmxTheme.typography.bodySmall,
                        color = AndmxTheme.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    )
                }
            }
            items(groups) { group ->
                ProjectHeader(group.name, onClick = onProjectHeaderClick)
                group.conversations.forEach { conv ->
                    ConversationRow(
                        conv = conv,
                        selected = conv.id == activeId,
                        onClick = { onSelectConversation(conv.id) },
                        onDelete = { onDeleteConversation(conv.id) },
                        onRename = { onRenameConversation(conv.id, conv.title) },
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
            }
        }

        SidebarDivider()
        NavRow(Icons.Outlined.Settings, "设置", onClick = onSettings)
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    val colors = AndmxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.selected else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = label,
            style = AndmxTheme.typography.titleSmall,
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun ProjectHeader(rawName: String, onClick: () -> Unit = {}) {
    val colors = AndmxTheme.colors
    // Project grouping stores the guest path (e.g. /root/my-app); display the
    // last path segment as the project name.
    val name = rawName.trimEnd('/').substringAfterLast('/').ifBlank { rawName }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = name,
            style = AndmxTheme.typography.labelLarge,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: ConversationItem,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val colors = AndmxTheme.colors
    var menu by remember { mutableStateOf(false) }
    val rowBg by androidx.compose.animation.animateColorAsState(
        if (selected) colors.selected else androidx.compose.ui.graphics.Color.Transparent,
        label = "convRowBg",
    )
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBg)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = Spacing.xxxl, end = Spacing.sm)
            .padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = conv.title,
                style = AndmxTheme.typography.bodyMedium,
                color = if (selected) colors.textPrimary else if (conv.muted) colors.textTertiary else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (conv.goalText.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xxs))
                GoalStatusLine(conv)
            }
        }
        if (conv.time.isNotEmpty()) {
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = conv.time,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("重命名", color = colors.textPrimary) },
                onClick = { menu = false; onRename() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("删除对话", color = colors.warning) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun GoalStatusLine(conv: ConversationItem) {
    val colors = AndmxTheme.colors
    val statusColor = goalStatusColor(conv.goalPhase)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.clip(Radii.pill)
                .background(statusColor.copy(alpha = if (colors.isDark) 0.18f else 0.12f))
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        ) {
            Text(
                conv.goalPhase.label,
                style = AndmxTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(
            conv.goalNote.ifBlank { conv.goalText },
            style = AndmxTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun goalStatusColor(phase: GoalPhase): Color {
    val colors = AndmxTheme.colors
    return when (phase) {
        GoalPhase.RUNNING -> colors.accent
        GoalPhase.WAITING_APPROVAL, GoalPhase.NEEDS_SETUP, GoalPhase.FAILED -> colors.warning
        GoalPhase.PAUSED -> colors.textSecondary
        GoalPhase.READY -> colors.textTertiary
        GoalPhase.EMPTY -> colors.textTertiary
    }
}

@Composable
private fun SidebarDivider() {
    val colors = AndmxTheme.colors
    Box(
        Modifier
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.border),
    )
}
