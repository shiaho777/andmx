package com.andmx.ui2.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andmx.workspace.GitBaseline

/**
 * 分支切换对话框（ZCode 对齐：分支作为工作区上下文元信息）。
 * 列出本地分支、当前分支高亮、支持切换与新建。
 */
@Composable
fun BranchSwitchDialog(
    currentBranch: String,
    onListBranches: suspend () -> List<GitBaseline.BranchInfo>,
    onCheckout: (name: String, create: Boolean, onDone: (Boolean, String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var branches by remember { mutableStateOf<List<GitBaseline.BranchInfo>?>(null) }
    var newBranchName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 首次打开拉取分支列表
    LaunchedEffect(Unit) {
        branches = onListBranches()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("切换分支") },
        text = {
            Column {
                if (branches == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                } else if (branches!!.isEmpty()) {
                    Text(
                        "当前工作区不是 git 仓库，或暂无分支",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    ) {
                        items(branches!!, key = { it.name }) { info ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy && !info.isCurrent) {
                                        busy = true
                                        message = null
                                        onCheckout(info.name, false) { ok, msg ->
                                            busy = false
                                            message = msg
                                            if (ok) onDismiss()
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    info.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (info.isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (info.isCurrent) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = "当前分支",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // 新建分支
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("新建分支") },
                    singleLine = true,
                    enabled = !busy,
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && newBranchName.isNotBlank(),
                onClick = {
                    busy = true
                    message = null
                    onCheckout(newBranchName.trim(), true) { ok, msg ->
                        busy = false
                        message = msg
                        if (ok) onDismiss()
                    }
                },
            ) { Text("新建并切换") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}
