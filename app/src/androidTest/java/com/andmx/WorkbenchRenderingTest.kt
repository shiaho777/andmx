package com.andmx

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andmx.ui2.chat.Composer
import com.andmx.ui2.chat.ChatMessage
import com.andmx.ui2.chat.ChatScrollMemory
import com.andmx.ui2.chat.ConversationViewport
import com.andmx.ui2.chat.ApprovalBanner
import com.andmx.ui2.chat.ApprovalRequest
import com.andmx.ui2.chat.ApprovalScope
import com.andmx.ui2.chat.ExecMode
import com.andmx.ui2.chat.FileRewindDialog
import com.andmx.ui2.chat.TimelineItem
import com.andmx.ui2.chat.ToolCall
import com.andmx.ui2.chat.ToolGroupCard
import com.andmx.ui2.chat.rememberConversationViewport
import com.andmx.ui2.markdown.MarkdownView
import com.andmx.ui2.settings.tr
import com.andmx.ui2.theme.AndMX2Theme
import com.andmx.agent.ToolRisk
import com.andmx.workspace.FileChange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkbenchRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun runningTaskKeepsStopAndQueueActionsAvailableForADraft() {
        var sends = 0
        var stops = 0
        compose.setContent {
            AndMX2Theme {
                Composer(
                    value = "A follow-up draft",
                    onValueChange = {},
                    onSend = { sends++ },
                    isLoading = true,
                    onStop = { stops++ },
                    modelLabel = "",
                    selectedModel = "",
                    activeProviderId = "",
                    providers = emptyList(),
                    onSwitchModel = { _, _ -> },
                    reasoningEffort = "",
                    reasoning = null,
                    onReasoningSelected = {},
                    execMode = ExecMode.from("confirm"),
                    onExecModeSelected = {},
                )
            }
        }
        compose.onNodeWithContentDescription(tr("停止生成")).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(tr("加入队列")).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals(1, sends)
        }
    }

    @Test
    fun longCodeBlockRevealsItsTailOnlyAfterExpansion() {
        val code = (1..30).joinToString("\n") { "val step$it = $it" }
        compose.setContent {
            AndMX2Theme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MarkdownView("```kotlin\n$code\n```")
                }
            }
        }
        compose.onNodeWithText("val step30 = 30", substring = true).assertDoesNotExist()
        compose.onNodeWithText(tr("展开代码"), substring = true).performClick()
        compose.onNodeWithText("val step30 = 30", substring = true).assertExists()
    }

    @Test
    fun toolGroupKeepsManualFoldWhenAnotherToolArrives() {
        fun read(id: String, running: Boolean = false) = ToolCall(
            id = id,
            name = "Read",
            args = """{"file_path":"/root/project/$id.md"}""",
            isRunning = running,
        )
        val tools = mutableStateOf(listOf(read("overview"), read("checks", running = true)))
        compose.setContent {
            AndMX2Theme { ToolGroupCard(tools.value) }
        }
        compose.onNodeWithText("overview.md").assertHasClickAction()
        compose.onNodeWithText("2 步", substring = true).performClick()
        compose.runOnIdle { tools.value = tools.value + read("build", running = true) }
        val group = compose.onNodeWithText("3 步", substring = true)
        group.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, tr("已折叠")))
        compose.onNodeWithText("overview.md").assertDoesNotExist()
        group.performClick()
        compose.onNodeWithText("overview.md").assertHasClickAction()
    }

    @Test
    fun viewportFollowsNewRepliesOnlyUntilUserScrollsAway() {
        fun row(id: Long) = TimelineItem.Message(
            ChatMessage(id = id, role = "assistant", content = "Reply $id", sortKey = id),
        )
        val replies = mutableStateOf((30L downTo 1L).map(::row))
        val selected = mutableStateOf(-1L)
        lateinit var viewport: ConversationViewport
        compose.setContent {
            AndMX2Theme {
                val context = LocalContext.current
                val memory = remember { ChatScrollMemory(context) }
                viewport = rememberConversationViewport(selected.value, replies.value, false, memory)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(260.dp)
                        .nestedScroll(viewport.scrollConnection).testTag("viewport"),
                    state = viewport.list,
                    reverseLayout = true,
                ) {
                    items(replies.value, key = { it.stableId }) {
                        Text(it.message.content, Modifier.height(56.dp))
                    }
                }
            }
        }
        compose.runOnIdle { replies.value = listOf(row(31)) + replies.value }
        compose.onNodeWithText("Reply 31").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, viewport.list.firstVisibleItemIndex) }
        compose.onNodeWithTag("viewport").performTouchInput { swipeDown() }
        var readingKey: Any? = null
        compose.runOnIdle {
            assertEquals(false, viewport.following)
            readingKey = viewport.list.layoutInfo.visibleItemsInfo.minByOrNull { it.index }?.key
            replies.value = listOf(row(32)) + replies.value
        }
        compose.runOnIdle {
            assertEquals(readingKey, viewport.list.layoutInfo.visibleItemsInfo.minByOrNull { it.index }?.key)
        }
    }

    @Test
    fun commandDetailStaysOpenAfterItsGroupIsCollapsedAndReopened() {
        val first = ToolCall(
            id = "first",
            name = "Bash",
            args = """{"command":"printf workdir"}""",
            output = "/root/project",
            isRunning = false,
        )
        val second = first.copy(id = "second", args = """{"command":"echo ready"}""", output = "ready")
        compose.setContent { AndMX2Theme { ToolGroupCard(listOf(first, second)) } }
        val group = compose.onNodeWithText("执行 2 条命令")
        group.performClick()
        compose.onNodeWithText("printf workdir").performClick()
        compose.onNodeWithText("/root/project").assertIsDisplayed()
        group.performClick()
        compose.onNodeWithText("/root/project").assertDoesNotExist()
        group.performClick()
        compose.onNodeWithText("/root/project").assertIsDisplayed()
    }

    @Test
    fun approvalShowsTheActualCommandAndDispatchesOnlyTheChosenScope() {
        var once = 0
        var scope: ApprovalScope? = null
        val request = ApprovalRequest(
            conversationId = 1L,
            toolName = "Bash",
            risk = ToolRisk.EXECUTE,
            summary = "Check the document",
            modeLabel = "构建",
            inputPreview = "short preview",
            rulePreview = "命令前缀: printf …",
            arguments = """{"command":"printf 'verified output'"}""",
            projectKey = "/sample",
        )
        compose.setContent {
            AndMX2Theme {
                ApprovalBanner(request, onAllow = { once++ }, onDeny = {}, onScope = { scope = it })
            }
        }
        compose.onNodeWithText("printf 'verified output'").assertIsDisplayed()
        compose.onNodeWithText(tr("授权范围")).performClick()
        compose.onNodeWithText(tr("允许本会话")).performClick()
        compose.runOnIdle {
            assertEquals(0, once)
            assertEquals(ApprovalScope.SESSION_ALLOW, scope)
        }
    }

    @Test
    fun recordedChangePreviewKeepsReviewSeparateFromRevert() {
        val path = "/root/project/README.md"
        var reverted: String? = null
        compose.setContent {
            AndMX2Theme {
                FileRewindDialog(
                    changes = listOf(FileChange(path, "Old instructions", "New instructions")),
                    onRevertOne = { reverted = it },
                    onRevertAll = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("README.md").performClick()
        compose.onNodeWithText("Old instructions").assertIsDisplayed()
        compose.onNodeWithText("New instructions").assertIsDisplayed()
        compose.onNodeWithText(tr("还原此文件")).performClick()
        compose.runOnIdle { assertEquals(null, reverted) }
        compose.onNodeWithText(tr("返回")).performClick()
        compose.onNodeWithText("New instructions").assertIsDisplayed()
        compose.onNodeWithText(tr("还原此文件")).performClick()
        compose.onNodeWithText(tr("还原")).performClick()
        compose.runOnIdle { assertEquals(path, reverted) }
    }
}
