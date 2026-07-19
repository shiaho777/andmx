package com.andmx.ui2.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.workspace.ProjectManager

@Composable
fun EmptyWorkspaceHeader(
    hasWorkspace: Boolean,
    projectName: String,
    hostPath: String?,
    branch: String,
    isGitRepo: Boolean,
    hasChanges: Boolean,
    dirtyFileCount: Int = 0,
    ahead: Int = 0,
    candidates: List<String>,
    onSelectWorkspace: (String) -> Unit,
    onOpenFolder: () -> Unit,
    onOpenRemote: () -> Unit = {},
    onPickBranch: () -> Unit,
    onGitActions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val showBranch = hasWorkspace && isGitRepo && branch.isNotBlank()
    val branchLabel = branch
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        WorkspacePill(
            label = if (hasWorkspace) projectName else "选择项目",
            path = hostPath,
            candidates = candidates,
            onSelectWorkspace = onSelectWorkspace,
            onOpenFolder = onOpenFolder,
            onOpenRemote = onOpenRemote,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (showBranch) {
            BranchPill(
                branch = branchLabel,
                dirty = hasChanges,
                dirtyFileCount = dirtyFileCount,
                onClick = onPickBranch,
                modifier = Modifier
                    .padding(start = 0.dp)
                    .widthIn(max = 140.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (showBranch && (hasChanges || ahead > 0)) {
            PillButton(
                icon = Icons.Outlined.CloudUpload,
                text = "提交",
                onClick = onGitActions,
                contentDescription = "提交或推送",
            )
        }
    }
}

@Composable
private fun WorkspacePill(
    label: String,
    path: String?,
    candidates: List<String>,
    onSelectWorkspace: (String) -> Unit,
    onOpenFolder: () -> Unit,
    onOpenRemote: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Box(modifier) {
        PillButton(
            icon = if (path?.startsWith("ssh://") == true) Icons.Outlined.Dns else Icons.Outlined.Folder,
            text = label,
            onClick = {
                query = ""
                expanded = true
            },
            contentDescription = "选择项目",
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 320.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索工作区") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
            HorizontalDivider()

            val filtered = remember(query, candidates, path) {
                val q = query.trim()
                candidates
                    .filter { q.isEmpty() || it.contains(q, true) || ProjectManager.displayName(it).contains(q, true) }
                    .take(12)
            }

            Column(
                Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (filtered.isEmpty()) {
                    Text(
                        "没有匹配的工作区",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    filtered.forEach { candidate ->
                        val selected = candidate == path
                        DropdownMenuItem(
                            text = {
                                Text(
                                    ProjectManager.displayName(candidate),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    when {
                                        candidate.startsWith("ssh://") -> Icons.Outlined.Dns
                                        candidate.endsWith("/Documents") || candidate.endsWith("Documents") -> Icons.Outlined.Home
                                        else -> Icons.Outlined.Folder
                                    },
                                    null,
                                    Modifier.size(18.dp),
                                )
                            },
                            trailingIcon = {
                                if (selected) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelectWorkspace(candidate)
                            },
                        )
                    }
                }
            }

            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("打开文件夹") },
                leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onOpenFolder()
                },
            )
            DropdownMenuItem(
                text = { Text("远程连接") },
                leadingIcon = { Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onOpenRemote()
                },
            )
        }
    }
}

@Composable
private fun BranchPill(
    branch: String,
    dirty: Boolean,
    dirtyFileCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PillButton(
        icon = Icons.Outlined.AccountTree,
        text = branch,
        onClick = onClick,
        contentDescription = "切换 Git 分支",
        trailing = when {
            dirty && dirtyFileCount > 0 -> "·$dirtyFileCount"
            dirty -> "·"
            else -> null
        },
        modifier = modifier,
    )
}

@Composable
private fun PillButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    contentDescription: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Text(
                trailing,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = null,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
