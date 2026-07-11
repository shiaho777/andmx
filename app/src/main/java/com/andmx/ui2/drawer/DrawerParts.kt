package com.andmx.ui2.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.data.AndmxDao
import com.andmx.data.ConversationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun DrawerHeader(showArchived: Boolean, onNew: () -> Unit, onBackFromArchive: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showArchived) {
            IconButton(onClick = onBackFromArchive) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
            }
            Text("归档任务", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        } else {
            Text("任务", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onNew) { Icon(Icons.Outlined.Add, "新建任务") }
        }
    }
}

@Composable
fun DrawerToolbar(
    viewMode: ViewMode, sortMode: SortMode,
    onViewChange: (ViewMode) -> Unit,
    onSortChange: (SortMode) -> Unit,
    allExpanded: Boolean,
    onToggleExpandAll: () -> Unit,
    query: String, onQueryChange: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQueryChange,
            placeholder = { Text("搜索任务...") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = viewMode == ViewMode.GROUPED, onClick = { onViewChange(ViewMode.GROUPED) },
                label = { Text("分组") }
            )
            FilterChip(
                selected = viewMode == ViewMode.BY_PROJECT, onClick = { onViewChange(ViewMode.BY_PROJECT) },
                label = { Text("项目") }
            )
            FilterChip(
                selected = viewMode == ViewMode.TIMELINE, onClick = { onViewChange(ViewMode.TIMELINE) },
                label = { Text("时间线") }
            )
            Spacer0(8.dp)
            FilterChip(
                selected = sortMode == SortMode.UPDATED, onClick = {
                    onSortChange(if (sortMode == SortMode.UPDATED) SortMode.CREATED else SortMode.UPDATED)
                },
                label = { Text(if (sortMode == SortMode.UPDATED) "更新" else "创建") }
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onToggleExpandAll) {
                Icon(
                    if (allExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                    null, Modifier.size(16.dp)
                )
                Text(
                    if (allExpanded) " 全部收起" else " 全部展开",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun GroupHeader(name: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            " · $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun GroupSectionHeader(
    name: String, count: Int, color: Color,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onViewFiles: () -> Unit,
    onArchiveGroup: () -> Unit,
    onNewGroup: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
            null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (color != Color.Transparent) {
            Box(Modifier.padding(end = 6.dp).size(10.dp).clip(CircleShape).background(color))
        }
        Text(
            name, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.MoreVert, "分组操作", Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("查看文件") },
                    leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                    onClick = { menu = false; onViewFiles() }
                )
                DropdownMenuItem(
                    text = { Text("归档分组") },
                    leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                    onClick = { menu = false; onArchiveGroup() }
                )
                DropdownMenuItem(
                    text = { Text("新建分组") },
                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                    onClick = { menu = false; onNewGroup() }
                )
            }
        }
    }
}

@Composable
fun SearchField(query: String, archived: Boolean, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange,
        placeholder = { Text(if (archived) "搜索归档任务..." else "搜索任务...") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
fun DrawerEmpty(searching: Boolean, archived: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            when {
                searching -> "没有匹配的任务"
                archived -> "暂无归档任务"
                else -> "暂无任务，点击右上角新建"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun BottomEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
fun TaskItem(
    conv: ConversationEntity, scope: CoroutineScope, dao: AndmxDao,
    onRename: () -> Unit, onDelete: () -> Unit, onSelect: (Long) -> Unit,
    selected: Boolean = false,
) {
    ConversationRow(
        conversation = conv,
        onClick = { onSelect(conv.id) },
        onRename = onRename,
        onDelete = onDelete,
        onArchive = { scope.launch { dao.setArchived(conv.id, true) } },
        onTogglePin = { scope.launch { dao.setPinned(conv.id, !conv.pinned) } },
        selected = selected,
    )
}

@Composable
fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("blue") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分组") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("颜色", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groupColors.forEach { (key, c) ->
                        Box(
                            Modifier.size(if (color == key) 32.dp else 26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .clickable { color = key }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim(), color) }, enabled = name.isNotBlank()) {
                Text("创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun Spacer0(width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(width, 0.dp))
}
