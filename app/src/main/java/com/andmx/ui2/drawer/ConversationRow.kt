package com.andmx.ui2.drawer

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    unread: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit = {},
    onTogglePin: () -> Unit,
    onMoveToGroup: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val bg = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    val titleColor = MaterialTheme.colorScheme.onSurface
    val metaColor = if (selected) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ts = if (sortMode == SortMode.CREATED) conversation.createdAt else conversation.updatedAt
    val timeText = relativeTaskTime(ts)
    val title = conversation.title.ifBlank { "新任务" }
    val multiLine = showWorkspaceMeta || !compact

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg)
                .combinedClickable(
                    onClick = onClick,
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
                    streaming -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.8.dp,
                            color = metaColor,
                        )
                    }
                    unread -> {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0EA5E9)),
                        )
                    }
                    conversation.pinned && !archivedMode -> {
                        Icon(
                            Icons.Outlined.PushPin,
                            null,
                            Modifier
                                .size(16.dp)
                                .clickable(onClick = onTogglePin),
                            tint = metaColor,
                        )
                    }
                    selected -> {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(metaColor.copy(alpha = 0.35f)),
                        )
                    }
                }
            }

            if (multiLine && showWorkspaceMeta) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = titleColor,
                    )
                    Row(
                        Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ProjectManager.displayName(conversation.project),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = metaColor,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            " · ",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                            color = metaColor.copy(alpha = 0.55f),
                        )
                        Text(
                            timeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                            color = metaColor,
                        )
                    }
                }
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = titleColor,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
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
                        "更多",
                        Modifier.size(18.dp),
                        tint = metaColor.copy(alpha = 0.85f),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (archivedMode) {
                        DropdownMenuItem(
                            text = { Text("取消归档") },
                            leadingIcon = { Icon(Icons.Outlined.Unarchive, null) },
                            onClick = {
                                menuOpen = false
                                onUnarchive()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重命名任务") },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (conversation.pinned) "取消置顶任务" else "置顶任务")
                            },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = {
                                menuOpen = false
                                onTogglePin()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = {
                                Text(if (conversation.pinned) "取消置顶任务" else "置顶任务")
                            },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = {
                                menuOpen = false
                                onTogglePin()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重命名任务") },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("移动到分组") },
                            leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                            onClick = {
                                menuOpen = false
                                onMoveToGroup()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("归档任务") },
                            leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                            onClick = {
                                menuOpen = false
                                onArchive()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
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
