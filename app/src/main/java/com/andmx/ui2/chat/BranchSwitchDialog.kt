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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.workspace.GitBaseline

private enum class BranchDialogStep {
    LIST,
    CREATE,
    BLOCKED,
    COMMIT,
}

@Composable
fun BranchSwitchDialog(
    currentBranch: String,
    dirtyFileCount: Int = 0,
    hasIdentity: Boolean = true,
    onListBranches: suspend () -> List<GitBaseline.BranchInfo>,
    onSwitchBranch: (
        name: String,
        create: Boolean,
        onDone: (GitBaseline.SwitchResult) -> Unit,
    ) -> Unit,
    onCommitAndSwitch: (
        name: String,
        create: Boolean,
        message: String,
        onDone: (GitBaseline.SwitchResult) -> Unit,
    ) -> Unit,
    onGenerateCommitMessage: (
        targetBranch: String,
        onDone: (Result<String>) -> Unit,
    ) -> Unit = { _, cb -> cb(Result.failure(IllegalStateException("未实现"))) },
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(BranchDialogStep.LIST) }
    var branches by remember { mutableStateOf<List<GitBaseline.BranchInfo>?>(null) }
    var query by remember { mutableStateOf("") }
    var newBranchName by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var pendingCreate by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<GitBaseline.SwitchResult?>(null) }

    LaunchedEffect(Unit) {
        branches = onListBranches()
    }

    fun handleResult(result: GitBaseline.SwitchResult) {
        busy = false
        if (result.ok) {
            onDismiss()
            return
        }
        when (result.issue) {
            GitBaseline.SwitchIssue.TRACKED_OVERWRITE,
            GitBaseline.SwitchIssue.UNTRACKED_OVERWRITE,
            -> {
                blocked = result
                step = BranchDialogStep.BLOCKED
            }
            else -> message = result.message
        }
    }

    fun attemptSwitch(name: String, create: Boolean) {
        val target = name.trim()
        if (target.isEmpty()) {
            message = "分支名无效，请换一个名称。"
            return
        }
        busy = true
        message = null
        pendingName = target
        pendingCreate = create
        onSwitchBranch(target, create, ::handleResult)
    }

    when (step) {
        BranchDialogStep.CREATE -> CreateBranchDialog(
            name = newBranchName,
            onNameChange = { newBranchName = it },
            busy = busy,
            message = message,
            onConfirm = { attemptSwitch(newBranchName, create = true) },
            onBack = {
                if (!busy) {
                    step = BranchDialogStep.LIST
                    message = null
                }
            },
        )

        BranchDialogStep.BLOCKED -> BlockedDialog(
            result = blocked,
            currentBranch = currentBranch,
            targetBranch = pendingName,
            busy = busy,
            onCommitAndSwitch = {
                step = BranchDialogStep.COMMIT
                message = null
                commitMessage = ""
            },
            onCancel = {
                if (!busy) {
                    step = if (pendingCreate) BranchDialogStep.CREATE else BranchDialogStep.LIST
                    blocked = null
                    message = null
                }
            },
        )

        BranchDialogStep.COMMIT -> CommitAndSwitchDialog(
            currentBranch = currentBranch,
            targetBranch = pendingName,
            dirtyFiles = blocked?.dirtyFiles.orEmpty().ifEmpty {
                blocked?.affectedFiles.orEmpty().map {
                    GitBaseline.ChangedFile(it, "M", untracked = false)
                }
            },
            commitMessage = commitMessage,
            onMessageChange = { commitMessage = it },
            hasIdentity = hasIdentity,
            busy = busy,
            message = message,
            onGenerate = {
                busy = true
                message = null
                onGenerateCommitMessage(pendingName) { result ->
                    busy = false
                    result.onSuccess { commitMessage = it }
                        .onFailure { message = it.message ?: "生成提交消息失败，请重试或手动填写。" }
                }
            },
            onConfirm = {
                busy = true
                message = null
                fun doSwitch(msg: String) {
                    onCommitAndSwitch(pendingName, pendingCreate, msg, ::handleResult)
                }
                val msg = commitMessage.trim()
                if (msg.isNotEmpty()) {
                    doSwitch(msg)
                } else {
                    onGenerateCommitMessage(pendingName) { result ->
                        result.onSuccess {
                            commitMessage = it
                            doSwitch(it)
                        }.onFailure {
                            busy = false
                            message = it.message ?: "生成提交消息失败，请重试或手动填写。"
                        }
                    }
                }
            },
            onCancel = {
                if (!busy) {
                    step = BranchDialogStep.BLOCKED
                    message = null
                }
            },
        )

        BranchDialogStep.LIST -> ListBranchesDialog(
            currentBranch = currentBranch,
            dirtyFileCount = dirtyFileCount,
            branches = branches,
            query = query,
            onQueryChange = { query = it },
            busy = busy,
            message = message,
            onSelect = { name -> attemptSwitch(name, create = false) },
            onCreate = {
                newBranchName = ""
                message = null
                step = BranchDialogStep.CREATE
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ListBranchesDialog(
    currentBranch: String,
    dirtyFileCount: Int,
    branches: List<GitBaseline.BranchInfo>?,
    query: String,
    onQueryChange: (String) -> Unit,
    busy: Boolean,
    message: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("切换 Git 分支") },
        text = {
            Column {
                if (dirtyFileCount > 0) {
                    Text(
                        "未提交的更改：$dirtyFileCount 个文件",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("搜索分支") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )

                when {
                    branches == null -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                    }
                    else -> {
                        val filtered = branches.filter {
                            query.isBlank() || it.name.contains(query, ignoreCase = true)
                        }
                        Text(
                            "分支",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        if (filtered.isEmpty()) {
                            Text(
                                "未找到匹配分支",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                                items(filtered, key = { it.name }) { info ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy && !info.isCurrent) {
                                                onSelect(info.name)
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
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
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
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(
                    enabled = !busy,
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp))
                    Text("创建并检出新分支...", Modifier.padding(start = 8.dp))
                }

                if (currentBranch.isNotBlank()) {
                    Text(
                        "当前：$currentBranch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun CreateBranchDialog(
    name: String,
    onNameChange: (String) -> Unit,
    busy: Boolean,
    message: String?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onBack() },
        title = { Text("创建并检出新分支") },
        text = {
            Column {
                Text(
                    "基于当前 HEAD 创建一个新的本地分支，并在创建成功后立即切换过去。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("分支名") },
                    placeholder = { Text("例如 feature/git-branch-switcher") },
                    singleLine = true,
                    enabled = !busy,
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("首版只支持基于当前 HEAD 创建并切换。") },
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = onConfirm,
            ) { Text("创建并切换") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onBack) { Text("返回") }
        },
    )
}

@Composable
private fun BlockedDialog(
    result: GitBaseline.SwitchResult?,
    currentBranch: String,
    targetBranch: String,
    busy: Boolean,
    onCommitAndSwitch: () -> Unit,
    onCancel: () -> Unit,
) {
    val untracked = result?.issue == GitBaseline.SwitchIssue.UNTRACKED_OVERWRITE
    val files = result?.affectedFiles.orEmpty().ifEmpty {
        result?.dirtyFiles.orEmpty().map { it.path }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        icon = {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("提交更改以切换分支") },
        text = {
            Column {
                Text(
                    if (untracked) {
                        "你对以下未跟踪文件的更改将被检出操作覆盖："
                    } else {
                        "你对以下文件的更改将被检出操作覆盖："
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    "请先提交当前更改，再继续切换到 $targetBranch。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (currentBranch.isNotBlank()) {
                    Text(
                        "当前分支：$currentBranch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "受影响文件",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(files.take(30)) { path ->
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                if (files.size > 30) {
                    Text(
                        "以及其他 ${files.size - 30} 个文件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onCommitAndSwitch) {
                Text("提交并切换分支...")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) { Text("取消") }
        },
    )
}

@Composable
private fun CommitAndSwitchDialog(
    currentBranch: String,
    targetBranch: String,
    dirtyFiles: List<GitBaseline.ChangedFile>,
    commitMessage: String,
    onMessageChange: (String) -> Unit,
    hasIdentity: Boolean,
    busy: Boolean,
    message: String?,
    onGenerate: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("提交更改") },
        text = {
            Column {
                Text(
                    "提交完成后，将自动继续切换到 $targetBranch。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Text(
                    "当前分支：$currentBranch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "目标分支：$targetBranch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    "更改 · ${dirtyFiles.size} 个文件",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
                    items(dirtyFiles.take(20), key = { it.path }) { file ->
                        Text(
                            "${file.status.ifBlank { "M" }}  ${file.path}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!hasIdentity) {
                    Text(
                        "当前没有可用的 Git 提交身份，请先配置 user.name 和 user.email。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = onMessageChange,
                    label = { Text("提交消息") },
                    placeholder = { Text("留空以自动生成提交消息") },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    supportingText = {
                        Text("这次会默认提交当前 workspace 内的全部未提交更改。")
                    },
                )
                TextButton(
                    enabled = !busy && hasIdentity,
                    onClick = onGenerate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (commitMessage.isBlank()) "生成提交消息" else "重新生成")
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && hasIdentity, onClick = onConfirm) {
                Text("提交并切换分支")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) { Text("返回") }
        },
    )
}
