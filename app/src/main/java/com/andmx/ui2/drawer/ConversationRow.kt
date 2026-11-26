package com.andmx.ui2.drawer

import com.andmx.ui2.settings.tr
import com.andmx.ui2.theme.Chrome
import com.andmx.ui2.theme.LocalMotion
import com.andmx.ui2.theme.workspaceLinkColor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import com.andmx.data.ConversationEntity
import com.andmx.workspace.ProjectManager

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    conversation: ConversationEntity,
    selected: Boolean,
    sortMode: SortMode,
    compact: Boolean = true,
    showWorkspaceMeta: Boolean = false,
    archivedMode: Boolean = false,
    streaming: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit = {},
    onTogglePin: () -> Unit,
    onToggleUnread: () -> Unit = {},
    onMoveToGroup: () -> Unit = {},
    searchExcerpt: String? = null,
    awaitingApproval: Boolean = false,
) {
    val unread = conversation.unreadAt != null
    var menuOpen by remember { mutableStateOf(false) }
    val shape = Chrome.control
    val selection by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = LocalMotion.current.defaultEffects,
        label = "taskSelection",
    )
    val bg = lerp(Color.Transparent, MaterialTheme.colorScheme.surfaceContainerHighest, selection)
    val titleColor = MaterialTheme.colorScheme.onSurface
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ts = activityTimestamp(
        createdAt = conversation.createdAt,
        updatedAt = conversation.updatedAt,
        preferCreated = sortMode == SortMode.CREATED,
    )
    val timeText = relativeTaskTime(ts)
    val title = conversation.title.ifBlank { tr("新任务") }
    val multiLine = showWorkspaceMeta || !compact || searchExcerpt != null

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg)
                .semantics { this.selected = selected }
                .combinedClickable(
                    onClick = onClick,
                    role = Role.Button,
                    onLongClickLabel = tr("更多"),
                    onLongClick = { menuOpen = true },
                )
                .then(
                    if (multiLine) Modifier.padding(vertical = 10.dp)
                    else Modifier.height(44.dp),
                )
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    awaitingApproval -> Icon(
                        Icons.Outlined.Shield,
                        tr("等待确认"),
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    streaming -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.8.dp,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    unread -> {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(workspaceLinkColor()),
                        )
                    }
                    conversation.pinned && !archivedMode -> {
                        Icon(
                            Icons.Outlined.PushPin,
                            tr("取消置顶任务"),
                            Modifier
                                .size(16.dp)
                                .clickable(onClick = onTogglePin),
                            tint = metaColor,
                        )
                    }
                }
            }

            if (showWorkspaceMeta || searchExcerpt != null) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = titleColor,
                    )
                    if (searchExcerpt != null) {
                        Text(
                            searchExcerpt,
                            style = MaterialTheme.typography.bodySmall,
                            color = metaColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Row(
                        Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ProjectManager.displayName(conversation.project),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = metaColor,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            " · ",
                            style = MaterialTheme.typography.bodySmall,
                            color = metaColor.copy(alpha = 0.55f),
                        )
                        Text(
                            timeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = metaColor,
                        )
                    }
                }
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = titleColor,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = metaColor,
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                )
            }

            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        tr("更多"),
                        Modifier.size(18.dp),
                        tint = metaColor.copy(alpha = 0.85f),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (archivedMode) {
                        DropdownMenuItem(
                            text = { Text(tr("取消归档")) },
                            leadingIcon = { Icon(Icons.Outlined.Unarchive, null) },
                            onClick = {
                                menuOpen = false
                                onUnarchive()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("重命名任务")) },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (conversation.pinned) tr("取消置顶任务") else tr("置顶任务"))
                            },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = {
                                menuOpen = false
                                onTogglePin()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr(if (unread) tr("标记为已读") else tr("标记为未读"))) },
                            leadingIcon = {
                                Icon(
                                    if (unread) Icons.Outlined.MarkEmailRead
                                    else Icons.Outlined.MarkEmailUnread,
                                    null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleUnread()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("删除")) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = {
                                Text(if (conversation.pinned) tr("取消置顶任务") else tr("置顶任务"))
                            },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = {
                                menuOpen = false
                                onTogglePin()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("重命名任务")) },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("移动到分组")) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                            onClick = {
                                menuOpen = false
                                onMoveToGroup()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr(if (unread) tr("标记为已读") else tr("标记为未读"))) },
                            leadingIcon = {
                                Icon(
                                    if (unread) Icons.Outlined.MarkEmailRead
                                    else Icons.Outlined.MarkEmailUnread,
                                    null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleUnread()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("归档任务")) },
                            leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                            onClick = {
                                menuOpen = false
                                onArchive()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("删除")) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}
