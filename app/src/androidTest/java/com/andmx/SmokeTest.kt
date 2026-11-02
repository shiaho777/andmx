package com.andmx

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
