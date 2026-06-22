package com.andmx.ui.workbench

import com.andmx.agent.ToolRisk
import com.andmx.ui.conversation.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInspectorModelTest {

    @Test
    fun snapshotCountsMessagesToolsApprovalsSourcesAndContext() {
        val snapshot = buildAgentInspectorSnapshot(
            project = "andmx",
            model = "gpt-test",
            baseUrl = "https://api.example.test",
            apiConfigured = true,
            approvalModeLabel = "按需",
            goalText = "复刻 Codex 工作台",
            goalPhaseLabel = "运行中",
            goalNote = "正在检查状态",
            busy = true,
            reasoningEffort = "high",
            persona = "precise",
            items = listOf(
                ChatItem.User(1, "继续"),
                ChatItem.User(6, "看图\n🖼 Codex screenshot.png · UI 参考"),
                ChatItem.Assistant(2, "我来处理"),
                ChatItem.ToolUse(3, "call-1", "read_file", """{"path":"/root/app.kt"}""", "fun main() {}", running = false),
                ChatItem.ToolUse(4, "call-2", "browse", """{"url":"https://example.com"}""", running = true),
                ChatItem.Approval(5, "apply_patch", "修改文件", risk = ToolRisk.WRITE),
            ),
            changedFiles = 2,
            builtInTools = 9,
            totalTools = 11,
            mcpServers = 1,
        )

        assertEquals(6, snapshot.messageCount)
        assertEquals(2, snapshot.userMessages)
        assertEquals(1, snapshot.assistantMessages)
        assertEquals(2, snapshot.toolEvents)
        assertEquals(1, snapshot.runningTools)
        assertEquals(1, snapshot.approvalEvents)
        assertEquals(1, snapshot.pendingApprovals)
        assertEquals(2, snapshot.sourceLinks)
        assertEquals(1, snapshot.uiReferences)
        assertEquals(2, snapshot.changedFiles)
        assertEquals(9, snapshot.builtInTools)
        assertEquals(11, snapshot.totalTools)
        assertTrue(snapshot.tokenEstimate > 0)
    }

    @Test
    fun nextActionPrioritizesSetupApprovalBusyDiffAndGoal() {
        val base = AgentInspectorSnapshot(
            project = "andmx",
            model = "gpt-test",
            baseUrl = "",
            apiConfigured = true,
            approvalModeLabel = "按需",
            goalText = "目标",
            goalPhaseLabel = "待继续",
            goalNote = "",
            busy = false,
            reasoningEffort = "medium",
            persona = "默认",
            messageCount = 1,
            userMessages = 1,
            assistantMessages = 0,
            toolEvents = 0,
            runningTools = 0,
            failedTools = 0,
            approvalEvents = 0,
            pendingApprovals = 0,
            changedFiles = 0,
            sourceLinks = 0,
            uiReferences = 0,
            tokenEstimate = 100,
            contextPressure = "轻量",
            builtInTools = 9,
            totalTools = 9,
            mcpServers = 0,
        )

        assertEquals("先配置模型与 API 密钥", inspectorNextAction(base.copy(apiConfigured = false)))
        assertEquals("处理 2 个授权请求", inspectorNextAction(base.copy(pendingApprovals = 2)))
        assertEquals("等待当前工具和模型收束", inspectorNextAction(base.copy(busy = true)))
        assertEquals("检查失败工具输出并重试", inspectorNextAction(base.copy(failedTools = 1)))
        assertEquals("审查 Diff 面板中的 3 个变更", inspectorNextAction(base.copy(changedFiles = 3)))
        assertEquals("先提取 2 个 UI 参考的界面模式", inspectorNextAction(base.copy(uiReferences = 2)))
        assertEquals("运行 /handoff 后开启新线程", inspectorNextAction(base.copy(contextPressure = "需要压缩")))
        assertEquals("输入目标开始当前线程", inspectorNextAction(base.copy(goalText = "")))
        assertEquals("继续推进目标或生成交接摘要", inspectorNextAction(base))
    }

    @Test
    fun contextHandoffAdviceEscalatesByPressureAndThreadShape() {
        val base = AgentInspectorSnapshot(
            project = "andmx",
            model = "gpt-test",
            baseUrl = "",
            apiConfigured = true,
            approvalModeLabel = "按需",
            goalText = "目标",
            goalPhaseLabel = "待继续",
            goalNote = "",
            busy = false,
            reasoningEffort = "medium",
            persona = "默认",
            messageCount = 4,
            userMessages = 2,
            assistantMessages = 2,
            toolEvents = 0,
            runningTools = 0,
            failedTools = 0,
            approvalEvents = 0,
            pendingApprovals = 0,
            changedFiles = 0,
            sourceLinks = 0,
            uiReferences = 0,
            tokenEstimate = 100,
            contextPressure = "轻量",
            builtInTools = 9,
            totalTools = 9,
            mcpServers = 0,
        )

        assertEquals(HandoffAdviceLevel.OK, contextHandoffAdvice(base).level)
        assertEquals("/context", contextHandoffAdvice(base).primaryCommand)
        assertEquals(HandoffAdviceLevel.WATCH, contextHandoffAdvice(base.copy(messageCount = 30)).level)
        assertEquals(HandoffAdviceLevel.WATCH, contextHandoffAdvice(base.copy(changedFiles = 1)).level)
        assertEquals(HandoffAdviceLevel.RECOMMENDED, contextHandoffAdvice(base.copy(contextPressure = "偏高")).level)
        assertEquals(HandoffAdviceLevel.REQUIRED, contextHandoffAdvice(base.copy(contextPressure = "需要压缩")).level)
        assertEquals("/handoff", contextHandoffAdvice(base.copy(contextPressure = "需要压缩")).primaryCommand)
    }
}
