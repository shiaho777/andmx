package com.andmx

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andmx.data.AndmxDatabase
import com.andmx.data.ConversationEntity
import com.andmx.data.MessageEntity
import com.andmx.settings.SettingsStore
import com.andmx.ui2.MainActivity2
import com.andmx.ui2.settings.UiLang
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity2>()

    /**
     * 断言文案是中文；CI 模拟器默认 en_US，若不固定语言，Drawer 的
     * tr("设置") 会变成 "Settings" 而让测试与语言设置耦合。
     */
    @Before
    fun forceChineseLocale() {
        val store = SettingsStore(compose.activity)
        runBlocking {
            val current = store.settings.first()
            if (current.locale != "zh") store.update(current.copy(locale = "zh"))
        }
        compose.waitUntil(5_000) { UiLang.tag == "zh" }
    }

    @Test
    fun coldLaunchDrawerSearchAndSettingsStayLocal() {
        compose.onNodeWithContentDescription("切换侧边栏").assertIsDisplayed().performClick()
        compose.onNodeWithText("搜索任务").assertIsDisplayed()
        compose.onNodeWithText("搜索命令").assertIsDisplayed()
        compose.onNodeWithText("设置").assertIsDisplayed()

        compose.onNodeWithText("搜索任务").performClick()
        compose.onNode(hasSetTextAction() and hasText("搜索任务..."))
            .assertIsDisplayed()
            .performTextInput("offline-smoke-no-matching-task")
        compose.onNodeWithContentDescription("清除搜索").assertIsDisplayed().performClick()

        compose.onNodeWithText("搜索命令").performClick()
        compose.onNode(hasSetTextAction() and hasText("搜索命令、设置与入口…"))
            .assertIsDisplayed()
            .performTextInput("settings")
        compose.onNodeWithText("打开应用设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("常规").assertIsDisplayed()
        compose.onNodeWithText("代码预览").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("切换侧边栏").assertIsDisplayed().performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("常规").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("切换侧边栏").assertIsDisplayed()
    }

    @Test
    fun builtInBrowserRejectsFileSchemes() {
        compose.onNodeWithContentDescription("切换侧边栏").performClick()
        compose.onNodeWithText("新建任务").performClick()
        compose.onNodeWithText("浏览器").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction() and hasText("网址"))
            .assertIsDisplayed()
            .performTextInput("file:///data/local/tmp/index.html")
        compose.onNodeWithText("打开").performClick()
        compose.onNodeWithText("无法打开网址或保存页面。").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("更多操作").assertIsDisplayed()
    }
    @Test
    fun draftWorkspaceAndModelSettingsAreReachableFromComposer() {
        compose.onNodeWithContentDescription("切换侧边栏").performClick()
        compose.onNodeWithText("新建任务").performClick()
        compose.onNodeWithContentDescription("选择项目").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择模型").assertIsDisplayed().performClick()
        compose.onNodeWithText("管理模型").assertIsDisplayed().performClick()
        compose.onNodeWithText("模型设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("选择模型").assertIsDisplayed()
    }

    @Test
    fun taskSearchOpensMatchInsideCollapsedProcess() {
        val dao = AndmxDatabase.get(compose.activity).dao()
        val title = "History task fixture"
        val query = "checkpoint-42%"
        val narration = "Read $query before applying changes."
        val created = System.currentTimeMillis()
        val id = runBlocking {
            val id = dao.insertConversation(ConversationEntity(project = "/root/project", title = title))
            listOf(
                "user" to "Check the build",
                "assistant" to narration,
                "tool" to "Workspace read complete",
                "assistant" to "Ready to continue.",
            ).forEachIndexed { index, (role, text) ->
                dao.insertMessage(
                    MessageEntity(
                        conversationId = id,
                        role = role,
                        content = text,
                        toolName = if (role == "tool") "Read" else null,
                        createdAt = created + index,
                    ),
                )
            }
            id
        }
        try {
            compose.onNodeWithContentDescription("切换侧边栏").performClick()
            compose.onNodeWithText("搜索任务").performClick()
            compose.onNode(hasSetTextAction() and hasText("搜索任务...")).performTextInput(query)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(narration).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(narration).assertIsDisplayed()
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithContentDescription("在任务中查找").assertIsDisplayed()
            compose.onNodeWithText("1/1").assertIsDisplayed()
            compose.onNodeWithText(narration).assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithText(narration).assertDoesNotExist()
        } finally {
            runBlocking { dao.deleteConversation(id) }
        }
    }

}
