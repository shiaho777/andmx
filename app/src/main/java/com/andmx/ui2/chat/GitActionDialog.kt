package com.andmx.ui2.chat

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
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Commit
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.workspace.GitBaseline

enum class GitActionMode { MENU, COMMIT, PUSH }

@Composable
fun GitActionDialog(
    gitInfo: GitBaseline.GitInfo,
    dirtyFiles: List<GitBaseline.ChangedFile>,
    onListDirty: suspend () -> List<GitBaseline.ChangedFile>,
    onGenerateCommitMessage: (onDone: (Result<String>) -> Unit) -> Unit,
    onCommit: (message: String, onDone: (GitBaseline.CommitResult) -> Unit) -> Unit,
    onPush: (onDone: (GitBaseline.PushResult) -> Unit) -> Unit,
    onCommitAndPush: (message: String, onDone: (GitBaseline.CommitResult, GitBaseline.PushResult?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(GitActionMode.MENU) }
    var files by remember { mutableStateOf(dirtyFiles) }
    var commitMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (files.isEmpty()) files = onListDirty()
    }

    when (mode) {
        GitActionMode.MENU -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("提交或推送") },
            text = {
                Column {
                    Text(
                        "分支：${gitInfo.branch.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (gitInfo.trackingBranch.isNotBlank()) {
                        Text(
                            "上游：${gitInfo.trackingBranch}（领先 ${gitInfo.ahead} / 落后 ${gitInfo.behind}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (gitInfo.isRepo) {
                        Text(
                            "尚未设置上游分支，首次推送会自动建立。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "未提交更改：${gitInfo.dirtyFileCount} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    )
                    TextButton(
                        enabled = gitInfo.hasChanges,
                        onClick = {
                            message = null
                            mode = GitActionMode.COMMIT
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Commit, null, Modifier.size(18.dp))
                        Text("提交更改", Modifier.padding(start = 8.dp))
                    }
                    TextButton(
                        enabled = gitInfo.needsPush || gitInfo.ahead > 0 || !gitInfo.hasUpstream,
                        onClick = {
                            message = null
                            mode = GitActionMode.PUSH
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp))
                        Text("推送", Modifier.padding(start = 8.dp))
                    }
                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )

        GitActionMode.COMMIT -> AlertDialog(
            onDismissRequest = { if (!busy) mode = GitActionMode.MENU },
            title = { Text("提交更改") },
            text = {
                Column {
                    Text(
                        "将当前 workspace 内的未提交更改保存为一次提交。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!gitInfo.hasIdentity) {
                        Text(
                            "当前没有可用的 Git 提交身份，请先配置 user.name 和 user.email。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Text(
                        "更改 · ${files.size.coerceAtLeast(gitInfo.dirtyFileCount)} 个文件",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
                        items(files.take(25), key = { it.path }) { f ->
                            Text(
                                "${f.status.ifBlank { "M" }}  ${f.path}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("提交消息") },
                        placeholder = { Text("提交信息（留空将自动生成）") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        supportingText = { Text("生成会先填充提交消息，再由你确认提交。") },
                    )
                    TextButton(
                        enabled = !busy && gitInfo.hasIdentity,
                        onClick = {
                            busy = true
                            message = null
                            onGenerateCommitMessage { result ->
                                busy = false
                                result.onSuccess { commitMessage = it }
                                    .onFailure {
                                        message = it.message ?: "生成提交消息失败，请重试或手动填写。"
                                    }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (commitMessage.isBlank()) "生成提交消息" else "重新生成")
                    }
                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (busy) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            },
            confirmButton = {
                Column {
                    TextButton(
                        enabled = !busy && gitInfo.hasIdentity && gitInfo.hasChanges,
                        onClick = {
                            busy = true
                            message = null
                            fun doCommit(msg: String) {
                                onCommit(msg) { result ->
                                    busy = false
                                    message = result.message
                                    if (result.ok) onDismiss()
                                }
                            }
                            val msg = commitMessage.trim()
                            if (msg.isNotEmpty()) {
                                doCommit(msg)
                            } else {
                                onGenerateCommitMessage { result ->
                                    result.onSuccess {
                                        commitMessage = it
                                        doCommit(it)
                                    }.onFailure {
                                        busy = false
                                        message = it.message ?: "生成提交消息失败，请重试或手动填写。"
                                    }
                                }
                            }
                        },
                    ) { Text("提交") }
                    TextButton(
                        enabled = !busy && gitInfo.hasIdentity && gitInfo.hasChanges,
                        onClick = {
                            busy = true
                            message = null
                            fun doCommitPush(msg: String) {
                                onCommitAndPush(msg) { commit, push ->
                                    busy = false
                                    message = when {
                                        !commit.ok -> commit.message
                                        push == null -> commit.message
                                        push.ok -> "已提交并推送当前更改"
                                        else -> "已提交，但推送失败：${push.message}"
                                    }
                                    if (commit.ok && push?.ok == true) onDismiss()
                                }
                            }
                            val msg = commitMessage.trim()
                            if (msg.isNotEmpty()) {
                                doCommitPush(msg)
                            } else {
                                onGenerateCommitMessage { result ->
                                    result.onSuccess {
                                        commitMessage = it
                                        doCommitPush(it)
                                    }.onFailure {
                                        busy = false
                                        message = it.message ?: "生成提交消息失败，请重试或手动填写。"
                                    }
                                }
                            }
                        },
                    ) { Text("提交并推送") }
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { mode = GitActionMode.MENU }) { Text("返回") }
            },
        )

        GitActionMode.PUSH -> AlertDialog(
            onDismissRequest = { if (!busy) mode = GitActionMode.MENU },
            title = { Text("推送更改") },
            text = {
                Column {
                    Text(
                        if (gitInfo.hasUpstream) {
                            "将当前分支最新提交推送到远程分支。"
                        } else {
                            "首次推送会把当前分支发布到远程并设置 upstream。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "分支：${gitInfo.branch}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "同步状态：领先 ${gitInfo.ahead} / 落后 ${gitInfo.behind}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "远程分支：${gitInfo.trackingBranch.ifBlank { "首次推送将自动创建" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (gitInfo.hasUpstream && gitInfo.ahead == 0) {
                        Text(
                            "当前分支没有需要推送的提交。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (busy) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && (gitInfo.ahead > 0 || !gitInfo.hasUpstream),
                    onClick = {
                        busy = true
                        message = null
                        onPush { result ->
                            busy = false
                            message = result.message
                            if (result.ok) onDismiss()
                        }
                    },
                ) { Text("推送") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { mode = GitActionMode.MENU }) { Text("返回") }
            },
        )
    }
}
