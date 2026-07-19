package com.andmx.ui2.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.data.TaskGroupEntity

@Composable
fun NewTaskButton(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Add,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "新建任务",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "N",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
fun DrawerQuickAction(
    icon: ImageVector,
    label: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
fun ViewModeSegmented(
    viewMode: ViewMode,
    onViewChange: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val selectedBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Row(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentedChip(
            label = "分组",
            icon = Icons.Outlined.Tag,
            selected = viewMode == ViewMode.GROUPED,
            selectedBg = selectedBg,
            onClick = { onViewChange(ViewMode.GROUPED) },
        )
        SegmentedChip(
            label = "项目",
            icon = Icons.Outlined.Folder,
            selected = viewMode == ViewMode.BY_PROJECT || viewMode == ViewMode.TIMELINE,
            selectedBg = selectedBg,
            onClick = { onViewChange(ViewMode.BY_PROJECT) },
        )
    }
}

@Composable
private fun SegmentedChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    selectedBg: Color,
    onClick: () -> Unit,
) {
    val bg = if (selected) selectedBg else Color.Transparent
    val fg = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    }
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = fg)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = fg,
        )
    }
}

@Composable
fun TaskToolbar(
    viewMode: ViewMode,
    sortMode: SortMode,
    onViewChange: (ViewMode) -> Unit,
    onSortChange: (SortMode) -> Unit,
    allExpanded: Boolean,
    canToggleExpand: Boolean,
    onToggleExpandAll: () -> Unit,
    showArchived: Boolean,
    onToggleArchived: () -> Unit,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onNewGroup: (() -> Unit)? = null,
) {
    var sortMenu by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArchived) {
            ToolbarIcon(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回任务",
                selected = false,
                onClick = onToggleArchived,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "归档任务",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ToolbarIcon(
                icon = Icons.Outlined.Search,
                contentDescription = "搜索归档",
                selected = searchOpen,
                onClick = onToggleSearch,
            )
            ToolbarIcon(
                icon = Icons.Outlined.Archive,
                contentDescription = "归档",
                selected = true,
                onClick = onToggleArchived,
            )
            return
        }

        ViewModeSegmented(viewMode = viewMode, onViewChange = onViewChange)

        if (canToggleExpand) {
            Spacer(Modifier.width(2.dp))
            ToolbarIcon(
                icon = if (allExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                contentDescription = if (allExpanded) "收起全部" else "展开全部",
                selected = false,
                onClick = onToggleExpandAll,
            )
        }

        Spacer(Modifier.weight(1f))

        Box {
            ToolbarIcon(
                icon = Icons.AutoMirrored.Outlined.Sort,
                contentDescription = "排序",
                selected = false,
                onClick = { sortMenu = true },
            )
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                Text(
                    "排序方式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                DropdownMenuItem(
                    text = { Text("更新时间") },
                    trailingIcon = {
                        if (sortMode == SortMode.UPDATED) Text("✓")
                    },
                    onClick = {
                        onSortChange(SortMode.UPDATED)
                        sortMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("创建时间") },
                    trailingIcon = {
                        if (sortMode == SortMode.CREATED) Text("✓")
                    },
                    onClick = {
                        onSortChange(SortMode.CREATED)
                        sortMenu = false
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text("时间线视图") },
                    trailingIcon = {
                        if (viewMode == ViewMode.TIMELINE) Text("✓")
                    },
                    onClick = {
                        onViewChange(ViewMode.TIMELINE)
                        sortMenu = false
                    },
                )
                if (onNewGroup != null) {
                    DropdownMenuItem(
                        text = { Text("新建分组") },
                        onClick = {
                            onNewGroup()
                            sortMenu = false
                        },
                    )
                }
            }
        }

        ToolbarIcon(
            icon = Icons.Outlined.Archive,
            contentDescription = "归档",
            selected = false,
            onClick = onToggleArchived,
        )
    }
}

@Composable
fun ToolbarIcon(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    } else {
        Color.Transparent
    }
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
        )
    }
}

@Composable
fun SearchField(
    query: String,
    archived: Boolean,
    onQueryChange: (String) -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val placeholder = if (archived) "搜索归档任务..." else "搜索任务..."
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                inner()
            },
        )
        if (onClose != null) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    "关闭",
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    count: Int? = null,
    collapsed: Boolean = false,
    color: Color = Color.Transparent,
    onToggle: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != Color.Transparent) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        trailing?.invoke()
        if (onToggle != null) {
            Icon(
                if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
fun DrawerEmpty(searching: Boolean, archived: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            when {
                searching && archived -> "没有找到相关归档任务"
                searching -> "没有找到相关任务"
                archived -> "暂无归档任务"
                else -> "暂无任务"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun DrawerFooter(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Settings,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun ShowMoreButton(expanded: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Text(
            if (expanded) "显示更少" else "显示更多",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun DeleteDialog(
    title: String,
    archived: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (archived) "删除这个归档任务？" else "删除这个任务？") },
        text = {
            Text(
                if (archived) {
                    "该任务会从归档中移除，并清理本地会话数据。不会删除工作区中的文件。"
                } else {
                    "任务“$title”会从当前工作区移除，现有记录无法恢复。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("blue") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分组") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "分组颜色",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groupColors.forEach { (key, c) ->
                        Box(
                            Modifier
                                .size(if (color == key) 32.dp else 26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .clickable { color = key },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), color) },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun MoveToGroupDialog(
    groups: List<TaskGroupEntity>,
    currentGroupId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到分组") },
        text = {
            Column {
                TextButton(
                    onClick = { onSelect("") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (currentGroupId.isBlank()) "✓  最近任务" else "最近任务",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                groups.sortedBy { it.sortOrder }.forEach { g ->
                    TextButton(
                        onClick = { onSelect(g.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (g.id == currentGroupId) "✓  ${g.name}" else g.name,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun ArchiveConfirmDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归档任务") },
        text = { Text("任务“$title”会移入归档列表，之后仍可恢复。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("归档") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun ProjectSectionActions(onOpenFiles: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpenFiles),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.FolderOpen,
            "查看文件",
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
