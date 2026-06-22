package com.andmx.ui.workbench

import com.andmx.agent.PlanItemStatus
import com.andmx.agent.TaskPlanItem
import com.andmx.agent.TaskPlanSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexInteractionFlowTest {

    @Test
    fun flowBlocksWhenGoalOrApiIsMissing() {
        val flow = buildCodexInteractionFlow(
            snapshot = snapshot(apiConfigured = false, goal = ""),
            plan = plan(),
            verifications = emptyList(),
            evidence = EvidenceLedger(emptyList()),
            checklist = checklist(ChecklistState.MISSING),
            nextAction = nextAction(NextActionPriority.BLOCKED, "/model"),
        )

        assertEquals("Codex 交互流程存在阻塞", flow.title)
        assertTrue(flow.blockedCount >= 2)
        assertEquals("/plan", flow.primaryCommand)
        assertEquals(InteractionFlowState.BLOCKED, flow.steps.first { it.title == "目标进入" }.state)
        assertEquals(InteractionFlowState.BLOCKED, flow.steps.first { it.title == "指令与上下文" }.state)
    }

    @Test
    fun flowTracksActiveToolsChangesScreenshotsAndVerification() {
        val flow = buildCodexInteractionFlow(
            snapshot = snapshot(
                toolEvents = 2,
                runningTools = 1,
                changedFiles = 2,
                uiReferences = 1,
                sourceLinks = 1,
                busy = true,
            ),
            plan = plan(active = 1),
            verifications = listOf(VerificationEntry(1, "./gradlew test", VerificationState.RUNNING, "running")),
            evidence = EvidenceLedger(
                listOf(
                    EvidenceItem(EvidenceKind.FILE, "/root/app.kt", "read", target = "/root/app.kt", state = "完成"),
                    EvidenceItem(EvidenceKind.UI_REFERENCE, "codex.png", "截图", state = "图片"),
                ),
            ),
            checklist = checklist(ChecklistState.WATCH),
            nextAction = nextAction(NextActionPriority.ACTIVE, "/activity"),
            screenshotExtraction = screenshotExtraction(waiting = 1),
        )

        assertEquals("Codex 交互流程正在推进", flow.title)
        assertTrue(flow.activeCount >= 4)
        assertEquals(InteractionFlowState.ACTIVE, flow.steps.first { it.title == "工具与审批" }.state)
        assertEquals(InteractionFlowState.ACTIVE, flow.steps.first { it.title == "截图到界面" }.state)
        assertEquals(InteractionFlowState.ACTIVE, flow.steps.first { it.title == "验证证据" }.state)
    }

    @Test
    fun flowTextShowsCurrentActionAndSteps() {
        val flow = buildCodexInteractionFlow(
            snapshot = snapshot(toolEvents = 3, changedFiles = 0, uiReferences = 1),
            plan = plan(),
            verifications = listOf(VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
            evidence = EvidenceLedger(
                listOf(
                    EvidenceItem(EvidenceKind.FILE, "/root/app.kt", "read", target = "/root/app.kt", state = "完成"),
                    EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过"),
                ),
            ),
            checklist = checklist(ChecklistState.READY),
            nextAction = nextAction(NextActionPriority.CONTINUE, "/report"),
            screenshotExtraction = screenshotExtraction(),
        )

        val text = codexInteractionFlowText(flow)

        assertTrue(text.contains("## Codex 交互流程"))
        assertTrue(text.contains("### 当前动作"))
        assertTrue(text.contains("目标进入"))
        assertTrue(text.contains("工具与审批"))
        assertTrue(text.contains("交付与交接"))
    }

    private fun snapshot(
        apiConfigured: Boolean = true,
        goal: String = "复刻 Codex 工作台",
        toolEvents: Int = 0,
        runningTools: Int = 0,
        changedFiles: Int = 0,
        uiReferences: Int = 0,
        sourceLinks: Int = 0,
        busy: Boolean = false,
    ) = AgentInspectorSnapshot(
        project = "andmx",
        model = "gpt-test",
        baseUrl = "https://api.example.test",
        apiConfigured = apiConfigured,
        approvalModeLabel = "按需",
        goalText = goal,
        goalPhaseLabel = if (goal.isBlank()) "空" else "运行中",
        goalNote = "",
        busy = busy,
        reasoningEffort = "medium",
        persona = "默认",
        messageCount = 8,
        userMessages = 3,
        assistantMessages = 3,
        toolEvents = toolEvents,
        runningTools = runningTools,
        failedTools = 0,
        approvalEvents = 0,
        pendingApprovals = 0,
        changedFiles = changedFiles,
        sourceLinks = sourceLinks,
        uiReferences = uiReferences,
        tokenEstimate = 2_000,
        contextPressure = "轻量",
        builtInTools = 9,
        totalTools = 9,
        mcpServers = 0,
    )

    private fun plan(active: Int = 0, blocked: Int = 0) = TaskPlanSnapshot(
        goalText = "复刻 Codex 工作台",
        goalPhaseLabel = "运行中",
        goalNote = "",
        items = buildList {
            add(TaskPlanItem("读取项目", "done", PlanItemStatus.DONE))
            repeat(active) { add(TaskPlanItem("推进$it", "active", PlanItemStatus.ACTIVE)) }
            repeat(blocked) { add(TaskPlanItem("阻塞$it", "blocked", PlanItemStatus.BLOCKED)) }
        },
    )

    private fun checklist(state: ChecklistState) = SessionChecklistSummary(
        title = when (state) {
            ChecklistState.READY -> "可以交付或交接"
            ChecklistState.WATCH -> "接近就绪"
            ChecklistState.MISSING -> "还不能交付"
        },
        detail = "detail",
        items = listOf(
            SessionChecklistItem("目标", "复刻 Codex", state, "/plan"),
            SessionChecklistItem("验证证据", "1 条验证通过", ChecklistState.READY, "/verify"),
        ),
    )

    private fun nextAction(priority: NextActionPriority, command: String) = NextActionDecision(
        priority = priority,
        title = "下一步",
        reason = "reason",
        evidence = listOf("evidence"),
        command = command,
    )

    private fun screenshotExtraction(waiting: Int = 0) = ScreenshotExtractionSummary(
        title = if (waiting == 0) "截图复刻解析已闭环" else "等待验证截图复刻",
        referenceCount = 1,
        items = buildList {
            add(ScreenshotExtractionItem("图 1: codex.png", ScreenshotExtractionState.READY, "ok", emptyList(), emptyList(), emptyList(), "/report"))
            repeat(waiting) {
                add(ScreenshotExtractionItem("待处理$it", ScreenshotExtractionState.NEEDS_VERIFICATION, "wait", emptyList(), emptyList(), emptyList(), "/visual-check"))
            }
        },
        primaryCommand = if (waiting == 0) "/report" else "/visual-check",
    )
}
