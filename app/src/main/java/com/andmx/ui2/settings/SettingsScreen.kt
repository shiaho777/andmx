package com.andmx.ui2.settings

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
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
    INDEX("索引库", Icons.Outlined.Shield),
    USAGE("使用统计", Icons.Outlined.QueryStats)
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf(SettingsPage.HOME) }
    val back = { page = SettingsPage.HOME }

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
        modifier = modifier
    ) { target ->
        when (target) {
            SettingsPage.HOME -> SettingsHome(onOpen = { page = it })
            SettingsPage.GENERAL -> GeneralPage(back)
            SettingsPage.CODE_PREVIEW -> CodePreviewPage(back)
            SettingsPage.MODEL -> ModelPage(back)
            SettingsPage.SKILLS -> SkillsPage(back)
            SettingsPage.SUBAGENT -> SubAgentPage(back)
            SettingsPage.MCP -> McpPage(back)
            SettingsPage.PLUGIN -> PluginPage(back)
            SettingsPage.COMMAND -> CommandPage(back)
            SettingsPage.INDEX -> IndexPage(back)
            SettingsPage.USAGE -> UsagePage(back)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(onOpen: (SettingsPage) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val items = listOf(
                SettingsPage.GENERAL, SettingsPage.CODE_PREVIEW, SettingsPage.MODEL,
                SettingsPage.SKILLS, SettingsPage.SUBAGENT, SettingsPage.MCP,
                SettingsPage.PLUGIN, SettingsPage.COMMAND, SettingsPage.INDEX,
                SettingsPage.USAGE
            )
            items.forEach { item ->
                SettingsItem(
                    icon = item.icon,
                    title = item.title,
                    onClick = { onOpen(item) }
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
    }
}
