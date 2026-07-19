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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
    onSettings: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    /** Open the project picker/creator dialog. */
    onProjectHeaderClick: () -> Unit = {},
    /** Close the sidebar drawer (compact only). When non-null, a header with a
     *  back/close button is rendered at the top. */
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.sidebar),
    ) {
        // Header row: close button (left) — ChatGPT style.
        if (onClose != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(Radii.pill)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        NavRow(Icons.Outlined.ChatBubbleOutline, "新对话", onClick = onNewChat)
        NavRow(Icons.Outlined.Search, "搜索", onClick = onSearch)
        NavRow(Icons.Outlined.Extension, "插件", onClick = onPlugins)
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
                        "无对话",
                        style = AndmxTheme.typography.labelSmall,
                        color = AndmxTheme.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    )
                }
            }
            items(groups) { group ->
                // Collapsible group header — tap to expand/collapse.
                val groupName = group.name.trimEnd('/').substringAfterLast('/').ifBlank { "默认" }
                var expanded by remember(group.name) { mutableStateOf(true) }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(Radii.sm)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        groupName,
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${group.conversations.size}",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
                if (expanded) {
                    // Show only the first N conversations by default; reveal the
                    // rest when the user taps "显示更多" — mirrors Codex's
                    // progressive disclosure in the thread list.
                    val previewCount = 5
                    var showAll by remember(group.name) { mutableStateOf(false) }
                    val visible = if (showAll) group.conversations else group.conversations.take(previewCount)
                    visible.forEach { conv ->
                        ConversationRow(
                            conv = conv,
                            selected = conv.id == activeId,
                            onClick = { onSelectConversation(conv.id) },
                            onDelete = { onDeleteConversation(conv.id) },
                            onRename = { onRenameConversation(conv.id, conv.title) },
                        )
                    }
                    if (group.conversations.size > previewCount) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(Radii.sm)
                                .clickable { showAll = !showAll }
                                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (showAll) "收起" else "显示更多 (${group.conversations.size - previewCount})",
                                style = AndmxTheme.typography.labelSmall,
                                color = colors.textTertiary,
                            )
                        }
                    }
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
    // Horizontal layout: icon left, label right — ChatGPT style.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = label,
            style = AndmxTheme.typography.titleSmall,
            color = colors.textPrimary,
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
        if (selected) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent,
        label = "convRowBg",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs)
            .clip(Radii.sm)
            .background(rowBg)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(
            text = conv.title,
            style = AndmxTheme.typography.bodyMedium,
            color = if (selected) colors.textPrimary else if (conv.muted) colors.textTertiary else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (conv.time.isNotEmpty()) {
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = conv.time,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("重命名", color = colors.textPrimary) },
            onClick = { menu = false; onRename() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("分支", color = colors.textPrimary) },
            onClick = { menu = false; onDelete() }, // TODO: wire to fork
        )
        androidx.compose.material3.HorizontalDivider()
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("删除", color = colors.warning) },
            onClick = { menu = false; onDelete() },
        )
    }
}

@Composable
private fun SidebarDivider() {
    val colors = AndmxTheme.colors
    Box(
        Modifier
            .padding(vertical = Spacing.xs)
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.border),
    )
}
