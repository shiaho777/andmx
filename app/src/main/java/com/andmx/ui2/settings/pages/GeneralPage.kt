package com.andmx.ui2.settings.pages

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            SettingsGroup("界面设置") {
                StackedSettingRow(
                    title = "界面字号",
                    description = "调整应用界面的文字大小，图标和布局尺寸不受影响。",
                ) {
                    androidx.compose.material3.Slider(
                        value = s.uiFontSizePx.toFloat(),
                        onValueChange = { save(s.copy(uiFontSizePx = it.toInt().coerceIn(12, 20))) },
                        valueRange = 12f..20f,
                        steps = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${s.uiFontSizePx}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsGroup("外观") {
                StackedSettingRow(
                    title = "界面主题",
                    description = "选择浅色、深色或跟随系统主题。",
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
                            "zh-CN" to "中文简体",
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
                    description = "在消息流中展示完整的模型思考内容；关闭时每轮仍展示第一次思考。",
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
                SwitchRow(
                    title = "分组探索工具",
                    description = "将连续的读取和搜索工具聚合为 Explore 分组。",
                    checked = s.toolGroupingExplore,
                    onCheckedChange = { save(s.copy(toolGroupingExplore = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "分组终端命令",
                    description = "将连续的非只读 Shell 命令聚合为 Terminal 分组。",
                    checked = s.toolGroupingTerminal,
                    onCheckedChange = { save(s.copy(toolGroupingTerminal = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "分组文件更改",
                    description = "将连续的 Write、Edit 和 ApplyPatch 调用聚合为 Changes 分组。",
                    checked = s.toolGroupingChanges,
                    onCheckedChange = { save(s.copy(toolGroupingChanges = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "提问自动继续",
                    description = "开启后，Agent 提问 5 分钟未回答会自动继续；关闭后，当前和后续提问会一直等待你的回答。",
                    checked = s.askAutoResolve,
                    onCheckedChange = { save(s.copy(askAutoResolve = it)) },
                )
                HorizontalDivider()
                StackedSettingRow(
                    title = "交互行为",
                    description = "在 ZCode 运行时将后续操作加入队列，或引导至下一轮工具调用后运行。",
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
                HorizontalDivider()
                StackedSettingRow(
                    title = "状态面板展开策略",
                    description = "对话底部状态胶囊与面板的展开方式。",
                ) {
                    SegmentedRow(
                        options = listOf(
                            "auto" to "自动展开",
                            "expanded" to "始终展开",
                            "collapsed" to "始终收起",
                        ),
                        selected = s.summaryPanelDisplayMode,
                        onSelect = { save(s.copy(summaryPanelDisplayMode = it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsGroup("动态工作流") {
                SwitchRow(
                    title = "启用动态工作流工具",
                    description = "开放 CreateWorkflow、AmendWorkflow、EvalWorkflowSnippet 等 11 个工作流工具。实验能力：脚本在设备内的 WebView 沙箱执行；切换后空闲会话会重建工具面。",
                    checked = s.dynamicWorkflowToolsEnabled,
                    onCheckedChange = { save(s.copy(dynamicWorkflowToolsEnabled = it)) },
                )
            }

            SettingsGroup("通知") {
                SwitchRow(
                    title = "任务通知",
                    description = "任务完成、失败或需要确认时发送系统通知。",
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
                    description = "留空时自动探测系统终端配置；填写后作为 ZCode 终端的字体覆盖。",
                ) {
                    OutlinedTextField(
                        value = s.terminalFontFamily,
                        onValueChange = { save(s.copy(terminalFontFamily = it)) },
                        placeholder = { Text(tr("留空自动继承，例如 MesloLGS NF, monospace")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            SettingsGroup("任务管理") {
                SwitchRow(
                    title = "自动归档旧任务",
                    description = "定时扫描最近打开过的工作区，将已完成、无未读、未置顶且超过保留期的任务自动归档。",
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
                            "3" to "3 天后归档",
                            "7" to "7 天后归档",
                            "14" to "14 天后归档",
                            "30" to "30 天后归档",
                        ),
                        selected = s.taskAutoArchiveDays.toString(),
                        enabled = s.taskAutoArchive,
                        onSelect = { save(s.copy(taskAutoArchiveDays = it.toInt())) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider()
                StackedSettingRow(
                    title = "闲时窗口",
                    description = "OffPeak 任务排入每日该时刻启动的闲时窗口（本地时间）。",
                ) {
                    SegmentedRow(
                        options = listOf(
                            "0" to "0:00",
                            "2" to "2:00",
                            "4" to "4:00",
                            "6" to "6:00",
                            "22" to "22:00",
                        ),
                        selected = s.offPeakStartHour.toString(),
                        onSelect = { save(s.copy(offPeakStartHour = it.toInt())) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsGroup("输出风格") {
                Text(tr("写进动态系统提示。名称为空时用 custom。正文为空则不注入。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = s.outputStyleName,
                    onValueChange = { save(s.copy(outputStyleName = it)) },
                    placeholder = { Text(tr("风格名称")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = s.outputStylePrompt,
                    onValueChange = { save(s.copy(outputStylePrompt = it)) },
                    placeholder = { Text(tr("风格要求")) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }

            SettingsGroup("自定义指令") {
                Text(tr("追加到系统提示的额外指令，会在所有新任务中生效。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = s.customInstructions,
                    onValueChange = { save(s.copy(customInstructions = it)) },
                    placeholder = { Text(tr("追加到系统提示的额外指令…")) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }

            ApprovalRulesSection()
        }
    }
}

/**
 * 「始终允许本项目」持久规则管理（ZCode allowForProject 对齐）：
 * 展示当前项目的已授权规则，可单独移除或全部清空。
 */
@Composable
private fun ApprovalRulesSection() {
    val context = LocalContext.current
    val store = remember { com.andmx.agent.ApprovalRuleStore(context) }
    val rules by store.rules.collectAsState()
    val scope = rememberCoroutineScope()
    val projectKey = remember {
        com.andmx.agent.ApprovalRuleStore.projectKeyOf(
            com.andmx.workspace.ProjectManager(context).hostPath.value,
        )
    }
    val current = rules[projectKey].orEmpty()
    if (current.isEmpty()) return

    SettingsGroup("项目权限规则") {
        Text(tr("本项目内的持久规则：始终允许 / 每次询问 / 始终拒绝。移除后对应操作回到按模式判定。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val grouped = current.groupBy { it.behavior }
        listOf(
            com.andmx.agent.ApprovalRuleStore.RuleBehavior.ALLOW to "始终允许",
            com.andmx.agent.ApprovalRuleStore.RuleBehavior.ASK to "每次询问",
            com.andmx.agent.ApprovalRuleStore.RuleBehavior.DENY to "始终拒绝",
        ).forEach { (behavior, label) ->
            grouped[behavior].orEmpty().sortedBy { it.display }.forEach { rule ->
                StackedSettingRow(
                    title = rule.display,
                    description = "${rule.toolCanonical} · $label",
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = { scope.launch { store.remove(projectKey, rule) } },
                    ) {
                        Text(tr("移除"))
                    }
                }
                HorizontalDivider()
            }
        }
        androidx.compose.material3.TextButton(
            onClick = { scope.launch { store.clear(projectKey) } },
        ) {
            Text(tr("清除本项目全部规则"), color = MaterialTheme.colorScheme.error)
        }
    }
}
