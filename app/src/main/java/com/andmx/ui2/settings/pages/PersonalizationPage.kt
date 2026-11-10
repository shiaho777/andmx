package com.andmx.ui2.settings.pages

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val USER_INSTRUCTIONS_PATH = "/root/.zcode/AGENTS.md"
private const val USER_INSTRUCTIONS_MAX_BYTES = 100 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val fs = remember { GuestFs(ProotRuntime(context)) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val bytes = remember(draft) { draft.toByteArray(Charsets.UTF_8).size }
    val dirty = draft != saved
    val tooLarge = bytes > USER_INSTRUCTIONS_MAX_BYTES

    LaunchedEffect(Unit) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { fs.readText(USER_INSTRUCTIONS_PATH, USER_INSTRUCTIONS_MAX_BYTES) }
        }
        loading = false
        result.onSuccess {
            saved = it
            draft = it
        }.onFailure {
            if (it is java.io.FileNotFoundException || it is IllegalArgumentException) {
                saved = ""
                draft = ""
            } else {
                error = it.message ?: "读取失败"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { backAppBar(tr("个性化"), onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                tr("应用到本机 Agent 的后续会话；远程设备需单独配置。"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = !loading,
                minLines = 8,
                maxLines = 20,
                placeholder = { Text(tr("例如：回答保持简洁，先给结论，再给理由。")) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (dirty) tr("有未保存的修改") else tr("已保存"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dirty) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${bytes / 1024} KB / 100 KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tooLarge) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = dirty && !tooLarge && !loading,
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { fs.writeText(USER_INSTRUCTIONS_PATH, draft) }
                            }
                            result.onSuccess {
                                saved = draft
                                error = null
                            }.onFailure {
                                error = it.message ?: "保存失败"
                            }
                        }
                    },
                ) { Text(tr("保存")) }
                TextButton(
                    enabled = dirty && !loading,
                    onClick = { draft = saved },
                ) { Text(tr("取消")) }
            }
            if (tooLarge) {
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("内容超过 100 KB，Agent 会截断；请先精简再保存。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
