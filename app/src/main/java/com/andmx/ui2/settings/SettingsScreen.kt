package com.andmx.ui2.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import com.andmx.ui2.theme.LocalMotion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Anchor
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.andmx.ui2.settings.pages.AboutPage
import com.andmx.ui2.settings.pages.CodePreviewPage
import com.andmx.ui2.settings.pages.CommandPage
import com.andmx.ui2.settings.pages.GeneralPage
import com.andmx.ui2.settings.pages.PersonalizationPage
import com.andmx.ui2.settings.pages.IndexPage
import com.andmx.ui2.settings.pages.McpPage
import com.andmx.ui2.settings.pages.MemoryViewerPage
import com.andmx.ui2.settings.pages.ModelPage
import com.andmx.ui2.settings.pages.ModelTrajectoryPage
import com.andmx.ui2.settings.pages.PluginPage
import com.andmx.ui2.settings.pages.SkillsPage
import com.andmx.ui2.settings.pages.SubAgentPage
import com.andmx.ui2.settings.pages.AutomationsPage
import com.andmx.ui2.settings.pages.BrowserSettingsPage
import com.andmx.ui2.settings.pages.ComputerUsePage
import com.andmx.ui2.settings.pages.HooksPage
import com.andmx.ui2.settings.pages.ShortcutsPage
import com.andmx.ui2.settings.pages.WorkspaceIgnorePage
import com.andmx.ui2.settings.pages.UsagePage

enum class SettingsPage(val title: String, val icon: ImageVector) {
    HOME("设置", Icons.Outlined.Tune),
    GENERAL("常规", Icons.Outlined.Tune),
    PERSONALIZATION("个性化", Icons.Outlined.Person),
    CODE_PREVIEW("代码预览", Icons.Outlined.Code),
    MODEL("模型设置", Icons.Outlined.Dns),
    SKILLS("技能", Icons.Outlined.Bolt),
    SUBAGENT("子智能体", Icons.Outlined.SmartToy),
    MCP("MCP 服务器", Icons.Outlined.Storage),
    PLUGIN("插件", Icons.Outlined.Extension),
    COMMAND("命令", Icons.Outlined.Terminal),
    AUTOMATIONS("自动化", Icons.Outlined.Alarm),
    HOOKS("Hooks", Icons.Outlined.Anchor),
    BROWSER("浏览器控制", Icons.Outlined.Language),
    COMPUTER("Android Use", Icons.Outlined.PhoneAndroid),
    SHORTCUTS("键盘快捷键", Icons.Outlined.Keyboard),
    WORKSPACE_SEARCH("工作区搜索", Icons.Outlined.Search),
    INDEX("索引库", Icons.AutoMirrored.Outlined.ManageSearch),
    USAGE("使用统计", Icons.Outlined.QueryStats),
    MODEL_TRAJECTORY("模型调用轨迹", Icons.Outlined.QueryStats),
    MEMORY("记忆", Icons.Outlined.Storage),
    ABOUT("关于", Icons.Outlined.Info),
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    initialPage: SettingsPage = SettingsPage.HOME,
) {
    var page by remember { mutableStateOf(initialPage) }
    LaunchedEffect(initialPage) {
        page = initialPage
    }
    val backToHome = { page = SettingsPage.HOME }
    BackHandler(enabled = true) {
        if (page != SettingsPage.HOME) backToHome() else onClose()
    }

    val motion = LocalMotion.current
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState == SettingsPage.HOME) motion.pop() else motion.push()
        },
        label = "settingsPage",
        modifier = modifier.fillMaxSize(),
    ) { target ->
        when (target) {
            SettingsPage.HOME -> SettingsHome(onOpen = { page = it }, onClose = onClose)
            SettingsPage.GENERAL -> GeneralPage(backToHome)
            SettingsPage.PERSONALIZATION -> PersonalizationPage(backToHome)
            SettingsPage.CODE_PREVIEW -> CodePreviewPage(backToHome)
            SettingsPage.MODEL -> ModelPage(backToHome)
            SettingsPage.SKILLS -> SkillsPage(onBack = backToHome, onCloseSettings = onClose)
            SettingsPage.SUBAGENT -> SubAgentPage(backToHome)
            SettingsPage.MCP -> McpPage(backToHome)
            SettingsPage.PLUGIN -> PluginPage(backToHome)
            SettingsPage.COMMAND -> CommandPage(backToHome)
            SettingsPage.AUTOMATIONS -> AutomationsPage(backToHome)
            SettingsPage.HOOKS -> HooksPage(backToHome)
            SettingsPage.BROWSER -> BrowserSettingsPage(backToHome)
            SettingsPage.COMPUTER -> ComputerUsePage(backToHome)
            SettingsPage.SHORTCUTS -> ShortcutsPage(backToHome)
            SettingsPage.WORKSPACE_SEARCH -> WorkspaceIgnorePage(backToHome)
            SettingsPage.INDEX -> IndexPage(backToHome)
            SettingsPage.USAGE -> UsagePage(backToHome)
            SettingsPage.MODEL_TRAJECTORY -> ModelTrajectoryPage(backToHome)
            SettingsPage.MEMORY -> MemoryViewerPage(backToHome)
            SettingsPage.ABOUT -> AboutPage(backToHome)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    onOpen: (SettingsPage) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val groups = listOf(
        "基础设置" to listOf(
            SettingsPage.GENERAL, SettingsPage.CODE_PREVIEW, SettingsPage.MODEL,
            SettingsPage.BROWSER, SettingsPage.COMPUTER, SettingsPage.SHORTCUTS,
            SettingsPage.WORKSPACE_SEARCH,
        ),
        "Agent 能力" to listOf(
            SettingsPage.PERSONALIZATION, SettingsPage.MEMORY, SettingsPage.SUBAGENT, SettingsPage.PLUGIN,
            SettingsPage.MCP, SettingsPage.SKILLS, SettingsPage.COMMAND,
            SettingsPage.AUTOMATIONS, SettingsPage.HOOKS,
        ),
        "数据与统计" to listOf(
            SettingsPage.INDEX, SettingsPage.USAGE, SettingsPage.MODEL_TRAJECTORY,
        ),
        "关于" to listOf(SettingsPage.ABOUT),
    )
    val descriptions = mapOf(
        SettingsPage.GENERAL to "语言与当前窗口体验",
        SettingsPage.PERSONALIZATION to "写一段全局指令，应用到本机 Agent 的后续会话。",
        SettingsPage.CODE_PREVIEW to "设置代码内容的主题、字号和显示方式，不受界面字号影响。",
        SettingsPage.MODEL to "模型供应商与默认模型",
        SettingsPage.SKILLS to "管理项目级与用户级技能。启用后可在聊天里通过 \$skill-name 使用。",
        SettingsPage.SUBAGENT to "管理 ZCode Agent 运行时消费的用户级子智能体 Markdown 文件。",
        SettingsPage.MCP to "管理 ZCode Agent 使用的 MCP 服务器配置。",
        SettingsPage.PLUGIN to "启用或停用已安装的插件。插件可打包技能、命令、Hooks 和 MCP 服务器。",
        SettingsPage.COMMAND to "管理 ZCode Agent 的 .md 命令文件。命令可通过 /command-name 在聊天中调用。",
        SettingsPage.AUTOMATIONS to "查看、开关和删除已保存的定时任务。",
        SettingsPage.HOOKS to "查看已启用插件声明的钩子。",
        SettingsPage.BROWSER to "开关网页抓取，以及证书校验和抓取缓存。",
        SettingsPage.COMPUTER to "查看无障碍和屏幕录制是否已打开，并前往系统设置。",
        SettingsPage.SHORTCUTS to "外接键盘在输入框里实际生效的快捷键。",
        SettingsPage.WORKSPACE_SEARCH to "编辑当前工作区的 .zcodeignore。",
        SettingsPage.INDEX to "工作区索引与搜索",
        SettingsPage.USAGE to "查看会话活跃度与模型用量的粗略统计。",
        SettingsPage.MODEL_TRAJECTORY to "逐次模型调用的来源、Token 与输入输出",
        SettingsPage.MEMORY to "在工作区中保存并复用长期上下文，新会话生效。开启后可能增加模型调用和 Token 成本。",
        SettingsPage.ABOUT to "版本、构建与诊断信息",
    )
    val filtered = filterSettingsGroups(query, groups, descriptions)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(tr("设置")) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                colors = settingsTopBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(tr("搜索设置")) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = tr("清除"))
                        }
                    }
                },
            )
            if (filtered.isEmpty()) {
                Text(
                    tr("没有找到相关设置"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                filtered.forEach { (title, items) ->
                    Text(
                        tr(title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                    items.forEach { item ->
                        SettingsItem(
                            icon = item.icon,
                            title = item.title,
                            subtitle = descriptions[item],
                            onClick = { onOpen(item) },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
    }
}
