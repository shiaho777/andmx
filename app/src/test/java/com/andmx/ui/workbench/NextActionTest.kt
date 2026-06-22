package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextActionTest {

    @Test
    fun nextActionPrioritizesSetupAndApprovalBeforeOtherWork() {
        val readyChecklist = checklist()

        assertEquals(
            "先配置模型",
            buildNextActionDecision(baseSnapshot(apiConfigured = false), readyChecklist, emptyList()).title,
        )

        val approval = buildNextActionDecision(
            baseSnapshot(apiConfigured = true, pendingApprovals = 2, changedFiles = 3),
            readyChecklist,
            emptyList(),
        )

        assertEquals(NextActionPriority.BLOCKED, approval.priority)
        assertEquals("处理授权请求", approval.title)
        assertEquals("/activity", approval.command)
    }

    @Test
    fun nextActionRoutesFailuresAndChangesToTheRightLocalCommands() {
        val failedVerification = buildNextActionDecision(
            baseSnapshot(toolEvents = 2),
            checklist(),
            listOf(VerificationEntry(1, "./gradlew test", VerificationState.FAILED, "BUILD FAILED")),
        )

        assertEquals(NextActionPriority.VERIFY, failedVerification.priority)
        assertEquals("处理失败验证", failedVerification.title)
        assertEquals("/verify", failedVerification.command)

        val changes = buildNextActionDecision(
            baseSnapshot(changedFiles = 2, sourceLinks = 1),
            checklist(watchCount = 1),
            listOf(VerificationEntry(2, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
        )

        assertEquals(NextActionPriority.REVIEW, changes.priority)
        assertEquals("审查待变更", changes.title)
        assertEquals("/changes", changes.command)
    }

    @Test
    fun nextActionExplainsReadyAndTextOutput() {
        val decision = buildNextActionDecision(
            baseSnapshot(goalText = "复刻 Codex 工作台", toolEvents = 3),
            checklist(),
            listOf(VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
        )

        assertEquals(NextActionPriority.CONTINUE, decision.priority)
        assertEquals("继续推进或交付", decision.title)
        assertEquals("/checklist", decision.command)

        val text = nextActionText(decision)
        assertTrue(text.contains("## 下一步"))
        assertTrue(text.contains("优先级: **继续**"))
        assertTrue(text.contains("### 证据"))
    }

    @Test
    fun nextActionUsesChecklistMissingItemAfterHardRuntimeStates() {
        val decision = buildNextActionDecision(
            baseSnapshot(goalText = "目标", toolEvents = 0),
            checklist(
                missing = SessionChecklistItem(
                    title = "验证证据",
                    detail = "暂无测试、构建或诊断记录",
                    state = ChecklistState.MISSING,
                    command = "/verify",
                ),
            ),
            emptyList(),
        )

        assertEquals(NextActionPriority.BLOCKED, decision.priority)
        assertEquals("补齐验证证据", decision.title)
        assertEquals("/verify", decision.command)
    }

    @Test
    fun nextActionPrioritizesExtractingUiReferenceBeforeImplementation() {
        val decision = buildNextActionDecision(
            baseSnapshot(goalText = "按截图复刻 Codex UI", uiReferences = 3),
            checklist(watchCount = 1),
            listOf(VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
        )

        assertEquals(NextActionPriority.REVIEW, decision.priority)
        assertEquals("提取界面模式", decision.title)
        assertEquals("/references", decision.command)
        assertTrue(decision.evidence.any { it.contains("3 个 UI 参考") })
    }


    private fun checklist(
        missing: SessionChecklistItem? = null,
        watchCount: Int = 0,
    ): SessionChecklistSummary {
        val watchItems = (1..watchCount).map {
            SessionChecklistItem("注意$it", "需要确认", ChecklistState.WATCH, "/checklist")
        }
        val items = listOfNotNull(missing) + watchItems + listOf(
            SessionChecklistItem("目标", "已设置", ChecklistState.READY, "/plan"),
            SessionChecklistItem("验证证据", "1 条验证通过", ChecklistState.READY, "/verify"),
        )
        return SessionChecklistSummary(
            title = if (missing != null) "还不能交付" else if (watchCount > 0) "接近就绪" else "可以交付或交接",
            detail = "test",
            items = items,
        )
    }

    private fun baseSnapshot(
        apiConfigured: Boolean = true,
        goalText: String = "目标",
        busy: Boolean = false,
        toolEvents: Int = 0,
        runningTools: Int = 0,
        failedTools: Int = 0,
        pendingApprovals: Int = 0,
        changedFiles: Int = 0,
        sourceLinks: Int = 0,
        uiReferences: Int = 0,
        contextPressure: String = "轻量",
    ) = AgentInspectorSnapshot(
        project = "andmx",
        model = "gpt-test",
        baseUrl = "https://api.example.test",
        apiConfigured = apiConfigured,
        approvalModeLabel = "按需",
        goalText = goalText,
        goalPhaseLabel = "运行中",
        goalNote = "",
        busy = busy,
        reasoningEffort = "medium",
        persona = "默认",
        messageCount = 8,
        userMessages = 3,
        assistantMessages = 3,
        toolEvents = toolEvents,
        runningTools = runningTools,
        failedTools = failedTools,
        approvalEvents = pendingApprovals,
        pendingApprovals = pendingApprovals,
        changedFiles = changedFiles,
        sourceLinks = sourceLinks,
        uiReferences = uiReferences,
        tokenEstimate = if (contextPressure == "需要压缩") 120_000 else 2_000,
        contextPressure = contextPressure,
        builtInTools = 9,
        totalTools = 9,
        mcpServers = 0,
    )
}
