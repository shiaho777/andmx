package com.andmx.ui.workbench

import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSelfImprovementTest {

    @Test
    fun improvementPlanPrioritizesMissingGoalAndEvidence() {
        val snapshot = snapshot(apiConfigured = false, goal = "")
        val plan = buildCodexSelfImprovementPlan(
            snapshot = snapshot,
            selfModel = selfModel(gap = 2),
            interactionFlow = interactionFlow(blocked = 2),
            toolCapabilityMap = toolMap(mcp = 0),
            visualAcceptance = visualAcceptance(referenceCount = 0, waiting = 3),
            evidence = EvidenceLedger(emptyList()),
            checklist = checklist(ChecklistState.MISSING),
            nextAction = nextAction(NextActionPriority.BLOCKED, "/model"),
        )

        assertEquals("AndMX 自我完善仍有缺口", plan.title)
        assertEquals("/goal", plan.primaryCommand)
        assertTrue(plan.gapCount >= 4)
        assertEquals(SelfImprovementState.GAP, plan.items.first { it.title == "锚定目标" }.state)
        assertEquals(SelfImprovementState.GAP, plan.items.first { it.title == "积累证据链" }.state)
        assertEquals(SelfImprovementState.GAP, plan.items.first { it.title == "采集真实 Codex 视觉上下文" }.state)
        assertEquals("/appshots", plan.items.first { it.title == "采集真实 Codex 视觉上下文" }.command)
    }

    @Test
    fun improvementPlanWatchesScreenshotsAndMcpWhenCoreLoopWorks() {
        val snapshot = snapshot(apiConfigured = true, goal = "复刻 Codex 工作台")
        val plan = buildCodexSelfImprovementPlan(
            snapshot = snapshot,
            selfModel = selfModel(),
                interactionFlow = interactionFlow(),
                toolCapabilityMap = toolMap(mcp = 0),
                visualAcceptance = visualAcceptance(referenceCount = 1, waiting = 2),
                evidence = evidence(verification = true, uiReference = true),
                checklist = checklist(ChecklistState.READY),
                nextAction = nextAction(NextActionPriority.CONTINUE, "/checklist"),
            )

        assertEquals("AndMX 自我完善正在收束", plan.title)
        assertEquals("/tools", plan.primaryCommand)
        assertEquals(SelfImprovementState.WATCH, plan.items.first { it.title == "扩展工具能力" }.state)
        assertEquals(SelfImprovementState.WATCH, plan.items.first { it.title == "吸收截图复刻" }.state)
    }

    @Test
    fun improvementTextIncludesRulesQueueEvidenceAndCommands() {
        val text = codexSelfImprovementText(
            buildCodexSelfImprovementPlan(
                snapshot = snapshot(apiConfigured = true, goal = "复刻 Codex 工作台"),
                selfModel = selfModel(),
                interactionFlow = interactionFlow(),
                toolCapabilityMap = toolMap(mcp = 1),
                visualAcceptance = visualAcceptance(referenceCount = 1, waiting = 0),
                evidence = evidence(verification = true, uiReference = true),
                checklist = checklist(ChecklistState.READY),
                nextAction = nextAction(NextActionPriority.CONTINUE, "/report"),
            ),
        )

        assertTrue(text.contains("## 自我完善路线图"))
        assertTrue(text.contains("### 操作规则"))
        assertTrue(text.contains("### 队列"))
        assertTrue(text.contains("锚定目标"))
        assertTrue(text.contains("采集真实 Codex 视觉上下文"))
        assertTrue(text.contains("吸收截图复刻"))
        assertTrue(text.contains("入口: `/report`"))
    }

    private fun snapshot(
        apiConfigured: Boolean,
        goal: String,
    ) = AgentInspectorSnapshot(
        project = "andmx",
        model = "gpt-test",
        baseUrl = "https://api.example.test",
        apiConfigured = apiConfigured,
        approvalModeLabel = "按需",
        goalText = goal,
        goalPhaseLabel = if (goal.isBlank()) "未开始" else "运行中",
        goalNote = "",
        busy = false,
        reasoningEffort = "medium",
        persona = "默认",
        messageCount = 8,
        userMessages = 3,
        assistantMessages = 3,
        toolEvents = 2,
        runningTools = 0,
        failedTools = 0,
        approvalEvents = 0,
        pendingApprovals = 0,
        changedFiles = 0,
        sourceLinks = 1,
        uiReferences = 1,
        tokenEstimate = 2_000,
        contextPressure = "轻量",
        builtInTools = 9,
        totalTools = 9,
        mcpServers = 0,
    )

    private fun selfModel(gap: Int = 0, watch: Int = 0) = CodexSelfModel(
        title = if (gap > 0) "Codex 自我模型仍有缺口" else "Codex 自我模型已可复用",
        layers = buildList {
            add(CodexSelfModelLayer("目标与线程", SelfModelState.READY, "codex", "andmx", "/plan"))
            repeat(gap) { add(CodexSelfModelLayer("缺口$it", SelfModelState.GAP, "codex", "andmx", "/self-model")) }
            repeat(watch) { add(CodexSelfModelLayer("关注$it", SelfModelState.WATCH, "codex", "andmx", "/self-model")) }
        },
        operatingLoop = listOf("观察", "执行"),
        environmentFacts = listOf("facts"),
        primaryCommand = if (gap > 0 || watch > 0) "/self-model" else "/report",
    )

    private fun interactionFlow(blocked: Int = 0, watch: Int = 0) = CodexInteractionFlow(
        title = if (blocked > 0) "Codex 交互流程存在阻塞" else "Codex 交互流程已闭环",
        steps = buildList {
            add(CodexInteractionStep("目标进入", InteractionFlowState.READY, "codex", "andmx", listOf("ok"), "/plan"))
            repeat(blocked) { add(CodexInteractionStep("阻塞$it", InteractionFlowState.BLOCKED, "codex", "andmx", listOf("gap"), "/flow")) }
            repeat(watch) { add(CodexInteractionStep("注意$it", InteractionFlowState.WATCH, "codex", "andmx", listOf("watch"), "/flow")) }
        },
        currentAction = nextAction(NextActionPriority.CONTINUE, "/report"),
        primaryCommand = if (blocked > 0 || watch > 0) "/flow" else "/report",
    )

    private fun toolMap(mcp: Int) = buildCodexToolCapabilityMap(
        tools = listOf(
            ToolCapability("read_file", "读取文件", ToolRisk.READ),
            ToolCapability("write_file", "写入文件", ToolRisk.WRITE),
            ToolCapability("apply_patch", "应用补丁", ToolRisk.WRITE),
            ToolCapability("run_shell", "运行命令", ToolRisk.EXECUTE),
            ToolCapability("browse", "浏览网页", ToolRisk.NETWORK),
            ToolCapability("computer_use", "桌面操作", ToolRisk.EXECUTE),
        ),
        mcpServerCount = mcp,
    )

    private fun visualAcceptance(referenceCount: Int, waiting: Int) = VisualAcceptanceSummary(
        title = if (waiting == 0) "视觉复刻可核查" else "等待视觉验证证据",
        referenceCount = referenceCount,
        items = buildList {
            add(VisualAcceptanceItem("截图证据", VisualAcceptanceState.READY, "ok", "/references"))
            repeat(waiting) { add(VisualAcceptanceItem("待处理$it", VisualAcceptanceState.NEEDS_VERIFICATION, "wait", "/visual-check")) }
        },
        primaryCommand = if (waiting == 0) "/report" else "/visual-check",
    )

    private fun evidence(verification: Boolean, uiReference: Boolean = false) = EvidenceLedger(
        buildList {
            add(EvidenceItem(EvidenceKind.FILE, "/root/app.kt", "read", target = "/root/app.kt", state = "完成"))
            if (uiReference) add(EvidenceItem(EvidenceKind.UI_REFERENCE, "Codex screenshot", "ref:abcd1234", state = "图片"))
            if (verification) add(EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过"))
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
}
