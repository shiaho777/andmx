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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.exec.remote.RemoteDirEntry
import com.andmx.workspace.RemoteConnectResult
import com.andmx.workspace.RemoteKind
import com.andmx.workspace.RemoteWorkspaceSpec
import com.andmx.workspace.SshAuthMethod

@Composable
fun RemoteWorkspaceDialog(
    profiles: List<RemoteWorkspaceSpec>,
    activeRemoteId: String?,
    onSave: (RemoteWorkspaceSpec) -> Unit,
    onDelete: (String) -> Unit,
    onConnect: (RemoteWorkspaceSpec, (RemoteConnectResult) -> Unit) -> Unit,
    onListDir: (RemoteWorkspaceSpec, String, (List<RemoteDirEntry>) -> Unit) -> Unit,
    onOpenWorkspace: (RemoteWorkspaceSpec, String, (RemoteConnectResult) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var editing by remember {
        mutableStateOf(
            RemoteWorkspaceSpec(
                kind = RemoteKind.SSH.name,
                port = 22,
                authMethod = SshAuthMethod.PRIVATE_KEY.name,
            ),
        )
    }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RemoteConnectResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectedSpec by remember { mutableStateOf<RemoteWorkspaceSpec?>(null) }
    var browsePath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteDirEntry>>(emptyList()) }
    var loadingDir by remember { mutableStateOf(false) }

    fun loadDir(spec: RemoteWorkspaceSpec, path: String) {
        loadingDir = true
        error = null
        onListDir(spec, path) { list ->
            loadingDir = false
            entries = list
            browsePath = path
        }
    }

    when (step) {
        0 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("连接远程环境") },
            text = {
                Column {
                    Text(
                        "通过 SSH 连接远程工作区，并在当前窗口中继续选择目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "连接方式：SSH",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                    if (profiles.isEmpty()) {
                        Text(
                            "还没有保存的远程连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(profiles, key = { it.id }) { profile ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            editing = profile
                                            step = 1
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(
                                            profile.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${profile.username}@${profile.host}:${profile.port}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (profile.id == activeRemoteId) {
                                        Text(
                                            "当前",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    IconButton(onClick = { onDelete(profile.id) }) {
                                        Icon(Icons.Outlined.Delete, "删除", Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = RemoteWorkspaceSpec(
                        kind = RemoteKind.SSH.name,
                        port = 22,
                        authMethod = SshAuthMethod.PRIVATE_KEY.name,
                    )
                    step = 1
                }) { Text("新建 SSH 连接") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )

        1 -> AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("SSH 远程连接") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "远程主机",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = editing.host,
                        onValueChange = { editing = editing.copy(host = it) },
                        label = { Text("主机") },
                        placeholder = { Text("输入主机地址或 IP，例如 192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = editing.port.toString(),
                        onValueChange = { v ->
                            editing = editing.copy(port = v.toIntOrNull() ?: editing.port)
                        },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = editing.username,
                        onValueChange = { editing = editing.copy(username = it) },
                        label = { Text("用户名") },
                        placeholder = { Text("输入用户名，例如 root") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = editing.label,
                        onValueChange = { editing = editing.copy(label = it) },
                        label = { Text("显示名称（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text(
                        "认证方式",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = editing.authMethod == SshAuthMethod.PRIVATE_KEY.name,
                            onClick = {
                                editing = editing.copy(authMethod = SshAuthMethod.PRIVATE_KEY.name)
                            },
                            label = { Text("私钥") },
                        )
                        FilterChip(
                            selected = editing.authMethod == SshAuthMethod.PASSWORD.name,
                            onClick = {
                                editing = editing.copy(authMethod = SshAuthMethod.PASSWORD.name)
                            },
                            label = { Text("密码") },
                        )
                    }
                    if (editing.authMethod == SshAuthMethod.PRIVATE_KEY.name) {
                        OutlinedTextField(
                            value = editing.privateKeyPath,
                            onValueChange = { editing = editing.copy(privateKeyPath = it) },
                            label = { Text("私钥") },
                            placeholder = { Text("选择或输入私钥文件路径") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        OutlinedTextField(
                            value = editing.passphrase,
                            onValueChange = { editing = editing.copy(passphrase = it) },
                            label = { Text("私钥口令（可选）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    } else {
                        OutlinedTextField(
                            value = editing.password,
                            onValueChange = { editing = editing.copy(password = it) },
                            label = { Text("密码") },
                            placeholder = { Text("输入 SSH 密码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    OutlinedTextField(
                        value = editing.remotePath,
                        onValueChange = { editing = editing.copy(remotePath = it) },
                        label = { Text("初始远程目录（可选）") },
                        placeholder = { Text("/home/user/project") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        error = null
                        if (editing.host.isBlank() || editing.username.isBlank()) {
                            error = "主机和用户名不能为空"
                            return@TextButton
                        }
                        if (editing.authMethod == SshAuthMethod.PRIVATE_KEY.name &&
                            editing.privateKeyPath.isBlank()
                        ) {
                            error = "私钥路径不能为空"
                            return@TextButton
                        }
                        if (editing.authMethod == SshAuthMethod.PASSWORD.name &&
                            editing.password.isBlank()
                        ) {
                            error = "密码不能为空"
                            return@TextButton
                        }
                        onSave(editing)
                        busy = true
                        step = 2
                        onConnect(editing) { r ->
                            busy = false
                            result = r
                            if (r.ok && r.spec != null) {
                                connectedSpec = r.spec
                                val start = r.spec.remotePath.ifBlank { r.homePath }.ifBlank { "~" }
                                step = 3
                                loadDir(r.spec, start)
                            }
                        }
                    },
                ) { Text("开始连接") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { step = 0 }) { Text("返回") }
            },
        )

        2 -> AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = {
                Text(
                    when {
                        busy -> "正在连接..."
                        result?.ok == true -> "连接成功"
                        else -> "连接失败"
                    },
                )
            },
            text = {
                Column {
                    if (busy) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                        Text("正在通过 SSH 建立连接。")
                    }
                    val logs = result?.logs.orEmpty()
                    Text(
                        "连接日志",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    if (logs.isEmpty()) {
                        Text(
                            "等待连接日志输出...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 200.dp)) {
                            items(logs) { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                    result?.message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result?.ok == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                if (!busy && result?.ok == true && connectedSpec != null) {
                    TextButton(onClick = {
                        val spec = connectedSpec!!
                        val start = spec.remotePath.ifBlank { result?.homePath.orEmpty() }.ifBlank { "~" }
                        step = 3
                        loadDir(spec, start)
                    }) { Text("选择目录") }
                } else if (!busy) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            },
            dismissButton = {
                if (!busy && result?.ok != true) {
                    TextButton(onClick = { step = 1 }) { Text("返回配置") }
                }
            },
        )

        else -> {
            val spec = connectedSpec
            AlertDialog(
                onDismissRequest = { if (!busy) onDismiss() },
                title = { Text("选择远程目录") },
                text = {
                    Column {
                        Text(
                            "远程连接就绪后，选择要在当前窗口中打开的目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                enabled = !loadingDir && browsePath.isNotBlank() && browsePath != "/",
                                onClick = {
                                    if (spec == null) return@IconButton
                                    val parent = browsePath.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "/")
                                        .ifBlank { "/" }
                                    loadDir(spec, if (parent.isBlank()) "/" else parent)
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一级")
                            }
                            Text(
                                browsePath.ifBlank { "—" },
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (loadingDir) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            }
                        } else {
                            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                                items(entries.filter { it.isDirectory }, key = { it.path }) { entry ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (spec != null) loadDir(spec, entry.path)
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                                        Text(
                                            entry.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 10.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                items(entries.filter { !it.isDirectory }.take(30), key = { "f-" + it.path }) { entry ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Outlined.InsertDriveFile,
                                            null,
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            entry.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 10.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && !loadingDir && spec != null && browsePath.isNotBlank(),
                        onClick = {
                            if (spec == null) return@TextButton
                            busy = true
                            error = null
                            onOpenWorkspace(spec, browsePath) { r ->
                                busy = false
                                if (r.ok) onDismiss()
                                else error = r.message
                            }
                        },
                    ) { Text("打开此目录") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { step = 1 }) { Text("返回连接配置") }
                },
            )
        }
    }
}
