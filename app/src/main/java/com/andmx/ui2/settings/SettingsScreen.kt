package com.andmx.ui2.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.andmx.ui2.settings.pages.CodePreviewPage
import com.andmx.ui2.settings.pages.CommandPage
import com.andmx.ui2.settings.pages.GeneralPage
import com.andmx.ui2.settings.pages.IndexPage
import com.andmx.ui2.settings.pages.McpPage
import com.andmx.ui2.settings.pages.ModelPage
import com.andmx.ui2.settings.pages.PluginPage
import com.andmx.ui2.settings.pages.SkillsPage
import com.andmx.ui2.settings.pages.SubAgentPage
import com.andmx.ui2.settings.pages.UsagePage

enum class SettingsPage(val title: String, val icon: ImageVector) {
    HOME("设置", Icons.Outlined.Tune),
    GENERAL("常规", Icons.Outlined.Tune),
    CODE_PREVIEW("代码预览", Icons.Outlined.Code),
    MODEL("模型设置", Icons.Outlined.Dns),
    SKILLS("技能", Icons.Outlined.Bolt),
    SUBAGENT("子智能体", Icons.Outlined.SmartToy),
    MCP("MCP 服务器", Icons.Outlined.Storage),
    PLUGIN("插件管理", Icons.Outlined.Extension),
    COMMAND("命令", Icons.Outlined.Terminal),
    INDEX("索引库", Icons.AutoMirrored.Outlined.ManageSearch),
    USAGE("使用统计", Icons.Outlined.QueryStats)
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

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState == SettingsPage.HOME) {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            } else {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            }
        },
        label = "settingsPage",
        modifier = modifier.fillMaxSize(),
    ) { target ->
        when (target) {
            SettingsPage.HOME -> SettingsHome(onOpen = { page = it }, onClose = onClose)
            SettingsPage.GENERAL -> GeneralPage(backToHome)
            SettingsPage.CODE_PREVIEW -> CodePreviewPage(backToHome)
            SettingsPage.MODEL -> ModelPage(backToHome)
            SettingsPage.SKILLS -> SkillsPage(onBack = backToHome, onCloseSettings = onClose)
            SettingsPage.SUBAGENT -> SubAgentPage(backToHome)
            SettingsPage.MCP -> McpPage(backToHome)
            SettingsPage.PLUGIN -> PluginPage(backToHome)
            SettingsPage.COMMAND -> CommandPage(backToHome)
            SettingsPage.INDEX -> IndexPage(backToHome)
            SettingsPage.USAGE -> UsagePage(backToHome)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    onOpen: (SettingsPage) -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            val items = listOf(
                SettingsPage.GENERAL, SettingsPage.CODE_PREVIEW, SettingsPage.MODEL,
                SettingsPage.SKILLS, SettingsPage.SUBAGENT, SettingsPage.MCP,
                SettingsPage.PLUGIN, SettingsPage.COMMAND, SettingsPage.INDEX,
                SettingsPage.USAGE,
            )
            val descriptions = mapOf(
                SettingsPage.GENERAL to "主题、语言与当前窗口体验",
                SettingsPage.CODE_PREVIEW to "代码高亮、字号与换行",
                SettingsPage.MODEL to "模型供应商与默认模型",
                SettingsPage.SKILLS to "已安装技能管理",
                SettingsPage.SUBAGENT to "子智能体配置",
                SettingsPage.MCP to "MCP 服务器连接",
                SettingsPage.PLUGIN to "插件安装与管理",
                SettingsPage.COMMAND to "斜杠命令",
                SettingsPage.INDEX to "工作区索引与搜索",
                SettingsPage.USAGE to "会话与模型用量统计",
            )
            items.forEach { item ->
                SettingsItem(
                    icon = item.icon,
                    title = item.title,
                    subtitle = descriptions[item],
                    onClick = { onOpen(item) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
    }
}
