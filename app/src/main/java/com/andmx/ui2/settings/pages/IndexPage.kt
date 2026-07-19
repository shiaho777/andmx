package com.andmx.ui2.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.SwitchRow
import com.andmx.ui2.settings.backAppBar
import com.andmx.workspace.ProjectManager
import com.andmx.workspace.WorkspaceIndex
import com.andmx.workspace.WorkspaceIndexPhase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val s by store.settings.collectAsState(initial = ProviderSettings())
    val scope = rememberCoroutineScope()
    val projectManager = remember { ProjectManager(context) }
    val hostPath by projectManager.hostPath.collectAsState()
    val index = remember { WorkspaceIndex.get(context) }
    val status by index.status.collectAsState()

    fun save(u: ProviderSettings) {
        scope.launch {
            store.update(u)
            val path = hostPath
            if (path != null) {
                index.onProjectSelected(path)
            }
        }
    }

    LaunchedEffect(hostPath) {
        index.refreshStatus(hostPath)
        if (!hostPath.isNullOrBlank() && !hostPath!!.startsWith("ssh://")) {
            if ((s.indexNewFolders && s.indexNewFoldersUserConfigured) || s.instantGrep) {
                index.onProjectSelected(hostPath)
            }
        }
    }

    val indexFoldersChecked = s.indexNewFolders && s.indexNewFoldersUserConfigured

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { backAppBar("索引库", onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            SettingsGroup("代码库") {
                SwitchRow(
                    title = "索引新文件夹",
                    description = "自动索引文件数少于 ${s.indexFileLimit} 的新文件夹。",
                    checked = indexFoldersChecked,
                    onCheckedChange = {
                        save(
                            s.copy(
                                indexNewFolders = it,
                                indexNewFoldersUserConfigured = true,
                            ),
                        )
                    },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "索引存储库以实现即时搜索（测试版）",
                    description = "自动对仓库进行索引，以加快 Grep 搜索速度。所有数据均存储在本地。",
                    checked = s.instantGrep,
                    onCheckedChange = { save(s.copy(instantGrep = it)) },
                )
            }

            SettingsGroup("当前项目") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            projectManager.projectName.ifBlank { "未选择项目" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (hostPath != null) {
                            Text(
                                hostPath!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        } else {
                            Text(
                                "在对话页或文件页打开一个本地项目后，可在此建立索引。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        val phaseLabel = when (status.phase) {
                            WorkspaceIndexPhase.IDLE -> "未索引"
                            WorkspaceIndexPhase.COUNTING -> "统计中"
                            WorkspaceIndexPhase.BUILDING -> "索引中"
                            WorkspaceIndexPhase.READY -> "已就绪"
                            WorkspaceIndexPhase.SKIPPED -> "已跳过"
                            WorkspaceIndexPhase.ERROR -> "失败"
                        }
                        Text(
                            "状态：$phaseLabel",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (status.message.isNotBlank()) {
                            Text(
                                status.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (status.builtAt > 0L) {
                            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(status.builtAt))
                            Text(
                                "更新于 $time",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { index.rebuildCurrent() },
                                enabled = hostPath != null &&
                                    !hostPath!!.startsWith("ssh://") &&
                                    status.phase != WorkspaceIndexPhase.BUILDING &&
                                    status.phase != WorkspaceIndexPhase.COUNTING &&
                                    ((s.indexNewFolders && s.indexNewFoldersUserConfigured) || s.instantGrep),
                            ) {
                                Text("立即索引")
                            }
                            OutlinedButton(
                                onClick = { index.clearCurrent() },
                                enabled = hostPath != null &&
                                    status.phase != WorkspaceIndexPhase.BUILDING &&
                                    status.phase != WorkspaceIndexPhase.COUNTING,
                            ) {
                                Text("清除索引")
                            }
                            TextButton(
                                onClick = {
                                    scope.launch { index.refreshStatus(hostPath) }
                                },
                            ) {
                                Text("刷新")
                            }
                        }
                    }
                }
            }
        }
    }
}
