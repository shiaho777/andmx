package com.andmx.ui2.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.SegmentedRow
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow
import com.andmx.ui2.settings.SwitchRow
import com.andmx.ui2.settings.ThemeModeSelector
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val s by store.settings.collectAsState(initial = ProviderSettings())
    val scope = rememberCoroutineScope()
    fun save(u: ProviderSettings) {
        scope.launch { store.update(u) }
    }
    val localeValue = when (s.locale) {
        "zh", "zh-CN" -> "zh-CN"
        "en", "en-US" -> "en-US"
        else -> "system"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { backAppBar("常规", onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 40.dp),
        ) {
            SettingsGroup("外观") {
                StackedSettingRow(
                    title = "界面主题",
                    description = "切换应用界面使用的主题外观。",
                ) {
                    ThemeModeSelector(
                        selected = s.themeMode,
                        onSelect = { save(s.copy(themeMode = it)) },
                    )
                }
                HorizontalDivider()
                StackedSettingRow(
                    title = "界面语言",
                    description = "选择应用 UI 的显示语言。",
                ) {
                    SegmentedRow(
                        options = listOf(
                            "system" to "系统默认",
                            "zh-CN" to "简体中文",
                            "en-US" to "English",
                        ),
                        selected = localeValue,
                        onSelect = { save(s.copy(locale = it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsGroup("消息流") {
                SwitchRow(
                    title = "显示思考过程",
                    description = "在消息流中展示模型思考内容，默认只展示第一个思考过程。",
                    checked = s.showReasoning,
                    onCheckedChange = { save(s.copy(showReasoning = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "显示待办",
                    description = "在消息流中展示 Todo 工具卡片。",
                    checked = s.showTodos,
                    onCheckedChange = { save(s.copy(showTodos = it)) },
                )
                HorizontalDivider()
                StackedSettingRow(
                    title = "交互行为",
                    description = "在运行时将后续操作加入队列，或引导至下一轮工具调用后运行。",
                ) {
                    SegmentedRow(
                        options = listOf(
                            "queue" to "队列",
                            "guide" to "引导",
                        ),
                        selected = s.interactionBehavior,
                        onSelect = { save(s.copy(interactionBehavior = it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsGroup("通知") {
                SwitchRow(
                    title = "任务通知",
                    description = "任务完成、失败或需要确认时发送通知。",
                    checked = s.notification,
                    onCheckedChange = { save(s.copy(notification = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "通知声音",
                    description = "通知开启后，可单独关闭任务通知提示音。",
                    checked = s.notificationSound,
                    onCheckedChange = { save(s.copy(notificationSound = it)) },
                    enabled = s.notification,
                )
            }

            SettingsGroup("终端") {
                StackedSettingRow(
                    title = "终端字体",
                    description = "留空时自动探测系统终端配置；填写后作为终端的字体覆盖。",
                ) {
                    OutlinedTextField(
                        value = s.terminalFontFamily,
                        onValueChange = { save(s.copy(terminalFontFamily = it)) },
                        placeholder = { Text("留空自动继承，例如 monospace") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            SettingsGroup("任务管理") {
                SwitchRow(
                    title = "自动归档旧任务",
                    description = "将已完成、无未读、未置顶且超过保留期的任务自动归档。",
                    checked = s.taskAutoArchive,
                    onCheckedChange = { save(s.copy(taskAutoArchive = it)) },
                )
                HorizontalDivider()
                StackedSettingRow(
                    title = "归档保留时长",
                    description = "任务最后更新时间早于该时长后，才会进入自动归档候选。",
                ) {
                    SegmentedRow(
                        options = listOf(
                            "3" to "3天",
                            "7" to "7天",
                            "14" to "14天",
                            "30" to "30天",
                        ),
                        selected = s.taskAutoArchiveDays.toString(),
                        onSelect = {
                            save(
                                s.copy(
                                    taskAutoArchive = true,
                                    taskAutoArchiveDays = it.toInt(),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsGroup("自定义指令") {
                Text(
                    "追加到系统提示的额外指令，会在所有新任务中生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = s.customInstructions,
                    onValueChange = { save(s.copy(customInstructions = it)) },
                    placeholder = { Text("追加到系统提示的额外指令…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        }
    }
}
