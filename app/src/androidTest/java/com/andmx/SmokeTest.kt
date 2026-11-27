package com.andmx

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andmx.data.AndmxDatabase
import com.andmx.data.ConversationEntity
import com.andmx.data.MessageEntity
import com.andmx.llm.VideoAttachments
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.settings.ProviderStore
import com.andmx.settings.SettingsStore
import com.andmx.ui2.MainActivity2
import com.andmx.ui2.settings.UiLang
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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
        if (compose.onAllNodes(androidx.compose.ui.test.hasTestTag("product-first-skip")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("product-first-skip").performScrollTo().performClick()
        }
    }

    @Test
    fun offlineSampleCanDeclineApplyAndRestoreARealPrivateFile() {
        compose.onNodeWithContentDescription("切换侧边栏").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasSetTextAction() and hasText("搜索设置")).performTextInput("快速入门")
        compose.onNodeWithText("重播离线样例或使用你自己的文件").performClick()
        compose.onNodeWithTag("product-first-sample").performClick()
        val content = compose.onNodeWithTag("product-task-content")
        content.assertTextContains("Status: draft", substring = true)
        compose.onNodeWithTag("product-sample-propose").performClick()
        compose.onNodeWithText("拒绝").performScrollTo().performClick()
        content.assertTextContains("Status: draft", substring = true)
        compose.onNodeWithTag("product-sample-propose").performScrollTo().performClick()
        compose.onNodeWithText("仅本次允许").performScrollTo().performClick()
        compose.onNodeWithText("还原此文件").performClick()
        compose.onNodeWithText("还原").performClick()
        content.assertTextContains("Status: draft", substring = true)
        val directory = java.io.File(compose.activity.noBackupFilesDir, "first-task")
        val sampleId = JSONObject(directory.resolve("SAMPLE.json").readText()).getString("id")
        check(directory.resolve("$sampleId.txt").readText() == "# AndMX sample\n\nStatus: draft\n")
    }

    @Test
    fun firstRealTaskConfirmsAnIsolatedModelRequestAndExportsACopy() {
        val activity = compose.activity
        val providers = ProviderStore(activity)
        val providerId = "product-local-fixture"
        val original = activity.cacheDir.resolve("product-source.txt").apply { writeText("status: draft") }
        val exported = activity.cacheDir.resolve("product-copy.txt").apply { writeText("") }
        val server = MockWebServer()
        val instrument = InstrumentationRegistry.getInstrumentation()
        fun intercept(action: String, file: java.io.File): Instrumentation.ActivityMonitor {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val filter = IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            }
            return instrument.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)), true)
        }
        val picker = intercept(Intent.ACTION_OPEN_DOCUMENT, original)
        val save = intercept(Intent.ACTION_CREATE_DOCUMENT, exported)
        try {
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val revised = JSONObject().put("content", "status: reviewed").toString()
            val response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":${JSONObject.quote(revised)}},\"finish_reason\":\"stop\"}]}"
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response))
            runBlocking {
                providers.upsert(ProviderDefinition(
                    id = providerId,
                    name = "Local product fixture",
                    kind = ProviderKind.OPENAI,
                    baseUrl = "http://127.0.0.1:${server.port}/v1",
                    apiKey = "test-only",
                    models = mapOf("revision-fixture" to ModelDefinition()),
                ))
            }
            compose.onNodeWithContentDescription("切换侧边栏").performClick()
            compose.onNodeWithText("设置").performClick()
            compose.onNode(hasSetTextAction() and hasText("搜索设置")).performTextInput("快速入门")
            compose.onNodeWithText("重播离线样例或使用你自己的文件").performClick()
            compose.onNodeWithTag("product-first-document").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Local product fixture · revision-fixture").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("导入文本文件").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("status: draft").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction() and hasText("你想修改什么？")).performTextInput("Mark reviewed")
            compose.onNodeWithText("请求改动").performScrollTo().performClick()
            check(server.requestCount == 0)
            compose.onNodeWithText("取消").performClick()
            check(server.requestCount == 0)
            compose.onNodeWithText("请求改动").performScrollTo().performClick()
            compose.onNodeWithText("发送并预览改动").performClick()
            val request = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            val body = JSONObject(request.body.readUtf8())
            check(body.getString("model") == "revision-fixture")
            check(!body.has("tools"))
            val sent = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
            check(sent.getString("file") == "status: draft" && sent.getString("instruction") == "Mark reviewed")
            check(original.readText() == "status: draft" && exported.readText().isEmpty())
            compose.waitUntil(5_000) { compose.onAllNodesWithText("仅本次允许").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("仅本次允许").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("还原此文件").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("关闭差异").performClick()
            compose.onNodeWithText("保存修改后的副本").performScrollTo().performClick()
            compose.waitUntil(5_000) { exported.readText() == "status: reviewed" }
            check(original.readText() == "status: draft")
            check(server.requestCount == 1)
        } finally {
            instrument.removeMonitor(picker)
            instrument.removeMonitor(save)
            original.delete()
            exported.delete()
            runBlocking { providers.delete(providerId) }
            server.shutdown()
        }
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
        compose.onNodeWithContentDescription("关闭搜索").assertIsDisplayed().performClick()

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
    fun videoPickerAppearsOnlyForTheSelectedCompatibleModel() {
        val settings = SettingsStore(compose.activity)
        val providers = ProviderStore(compose.activity)
        val id = "video-picker-smoke"
        val initial = runBlocking { settings.settings.first() }
        try {
            runBlocking {
                providers.upsert(ProviderDefinition(
                    id = id,
                    name = "Video picker fixture",
                    kind = ProviderKind.OPENAI,
                    baseUrl = "http://127.0.0.1:1",
                    apiKey = "fixture",
                    models = mapOf(
                        "picker-text" to ModelDefinition(inputModalities = listOf("text")),
                        "picker-video" to ModelDefinition(inputModalities = listOf("text", "video")),
                    ),
                ))
                settings.update(initial.copy(activeProviderId = id, model = "picker-text"))
            }
            compose.onNodeWithContentDescription("切换侧边栏").performClick()
            compose.onNodeWithText("新建任务").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("picker-text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("添加上下文").performClick()
            compose.onNodeWithText("添加文档").assertIsDisplayed()
            compose.onNodeWithText("添加视频").assertDoesNotExist()
            Espresso.pressBack()

            compose.onNodeWithContentDescription("选择模型").performClick()
            compose.onNodeWithText("picker-video").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("picker-video").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("添加上下文").performClick()
            compose.onNodeWithText("添加视频").assertIsDisplayed()
            val oversized = compose.activity.cacheDir.resolve("oversized-picker-fixture.mp4")
            RandomAccessFile(oversized, "rw").use { it.setLength(VideoAttachments.MAX_FILE_BYTES + 1) }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val picker = IntentFilter(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("video/*")
            }
            val monitor = instrumentation.addMonitor(
                picker,
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(oversized))),
                true,
            )
            try {
                compose.onNodeWithText("添加视频").performClick()
                val limit = "每条消息最多 20 MiB 视频"
                compose.waitUntil(8_000) { compose.onAllNodesWithText(limit).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(limit).assertIsDisplayed()
                compose.onNodeWithText(oversized.name).assertDoesNotExist()
                compose.onNodeWithText("知道了").performClick()
            } finally {
                instrumentation.removeMonitor(monitor)
                oversized.delete()
            }
        } finally {
            runBlocking {
                settings.update(initial)
                providers.delete(id)
            }
        }
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
