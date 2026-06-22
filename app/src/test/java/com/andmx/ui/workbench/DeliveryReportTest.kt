package com.andmx.ui.workbench

import com.andmx.agent.ChangeSummaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryReportTest {

    @Test
    fun reportIsReadyWhenSessionHasNoBlockingGaps() {
        val report = buildDeliveryReport(
            snapshot = snapshot(),
            changes = emptyList(),
            verifications = listOf(VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
            evidence = evidence(),
            checklist = checklist(ChecklistState.READY),
            nextAction = nextAction(),
            parity = parity(ParityState.READY),
            blueprint = blueprint(),
            visualAcceptance = visualAcceptance(),
            referenceBoard = referenceBoard(),
            screenshotTrace = screenshotTrace(),
            designSystem = designSystem(),
            screenshotExtraction = screenshotExtraction(),
            interactionFlow = interactionFlow(),
            selfModel = selfModel(),
        )

        assertEquals(DeliveryReportState.READY, report.state)
        assertEquals("可以交付或交接", report.title)
    }

    @Test
    fun reportRequiresVerificationWhenChangedFilesHaveNoPassedChecks() {
        val report = buildDeliveryReport(
            snapshot = snapshot(changedFiles = 1),
            changes = listOf(ChangeSummaryItem("app/src/main/Foo.kt", added = 12, removed = 2, isNew = false)),
            verifications = emptyList(),
            evidence = evidence(),
            checklist = checklist(ChecklistState.READY),
            nextAction = nextAction(command = "/verify"),
            parity = parity(ParityState.READY),
            blueprint = blueprint(BlueprintState.IMPLEMENTING),
            visualAcceptance = visualAcceptance(waiting = 1),
            referenceBoard = referenceBoard(open = 1),
            screenshotTrace = screenshotTrace(waiting = 1),
            designSystem = designSystem(watch = 1),
            screenshotExtraction = screenshotExtraction(waiting = 1),
            interactionFlow = interactionFlow(watch = 1),
            selfModel = selfModel(watch = 1),
        )

        assertEquals(DeliveryReportState.NEEDS_VERIFICATION, report.state)
        assertEquals("先补验证证据", report.title)
    }

    @Test
    fun reportBlocksOnChecklistFailuresVerificationFailuresOrParityGaps() {
        val report = buildDeliveryReport(
            snapshot = snapshot(changedFiles = 1),
            changes = listOf(ChangeSummaryItem("app/src/main/Foo.kt", added = 4, removed = 0, isNew = true)),
            verifications = listOf(VerificationEntry(2, "./gradlew test", VerificationState.FAILED, "BUILD FAILED")),
            evidence = evidence(),
            checklist = checklist(ChecklistState.MISSING),
            nextAction = nextAction(command = "/checklist"),
            parity = parity(ParityState.GAP),
            blueprint = blueprint(BlueprintState.VERIFYING),
            visualAcceptance = visualAcceptance(waiting = 1),
            referenceBoard = referenceBoard(open = 1),
            screenshotTrace = screenshotTrace(waiting = 1),
            designSystem = designSystem(watch = 1),
            screenshotExtraction = screenshotExtraction(waiting = 1),
            interactionFlow = interactionFlow(blocked = 1),
            selfModel = selfModel(watch = 1),
        )

        assertEquals(DeliveryReportState.BLOCKED, report.state)
        assertEquals("仍有阻塞缺口", report.title)
    }

    @Test
    fun reportTextContainsDeliverySectionsAndCounts() {
        val text = deliveryReportText(
            buildDeliveryReport(
                snapshot = snapshot(changedFiles = 1),
                changes = listOf(ChangeSummaryItem("app/src/main/Foo.kt", added = 4, removed = 1, isNew = false)),
                verifications = listOf(VerificationEntry(3, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
                evidence = evidence(uiReferences = 1, verifications = 1),
                checklist = checklist(ChecklistState.READY),
                nextAction = nextAction(),
                parity = parity(ParityState.READY),
                blueprint = blueprint(BlueprintState.VERIFYING, references = 1),
                visualAcceptance = visualAcceptance(),
                referenceBoard = referenceBoard(),
                screenshotTrace = screenshotTrace(),
                designSystem = designSystem(),
                screenshotExtraction = screenshotExtraction(),
                interactionFlow = interactionFlow(),
                selfModel = selfModel(),
                methodologySummary = listOf("观察: 项目和截图进入证据链", "收束: 通过 /report 保持可交付"),
                environmentContractSummary = listOf("状态: Codex 环境契约已可审计", "契约层: 已具备 5 · 注意 0 · 缺口 0"),
                toolCapabilitySummary = listOf("状态: Codex 工具能力地图已成形", "能力域: 6 · 工具: 9 · MCP: 1"),
            ),
        )

        assertTrue(text.contains("## 交付报告"))
        assertTrue(text.contains("### 变更"))
        assertTrue(text.contains("### 验证"))
        assertTrue(text.contains("### 证据"))
        assertTrue(text.contains("### 工作方法"))
        assertTrue(text.contains("### 环境契约"))
        assertTrue(text.contains("### 工具能力"))
        assertTrue(text.contains("### UI 蓝图"))
        assertTrue(text.contains("### 视觉验收"))
        assertTrue(text.contains("### 截图参考板"))
        assertTrue(text.contains("### 截图实现追踪"))
        assertTrue(text.contains("### 设计系统"))
        assertTrue(text.contains("### 截图解析"))
        assertTrue(text.contains("### 交互流程"))
        assertTrue(text.contains("### 自我模型"))
        assertTrue(text.contains("### Codex 对标"))
        assertTrue(text.contains("### 清单"))
        assertTrue(text.contains("`app/src/main/Foo.kt`"))
        assertTrue(text.contains("- UI 参考: 1"))
        assertTrue(text.contains("- 验证: 1"))
        assertTrue(text.contains("观察: 项目和截图进入证据链"))
        assertTrue(text.contains("入口: `/method`"))
        assertTrue(text.contains("状态: Codex 环境契约已可审计"))
        assertTrue(text.contains("入口: `/instructions`"))
        assertTrue(text.contains("状态: Codex 工具能力地图已成形"))
        assertTrue(text.contains("入口: `/tools`"))
    }

    private fun snapshot(changedFiles: Int = 0) = AgentInspectorSnapshot(
        project = "andmx",
        model = "gpt-test",
        baseUrl = "https://api.example.test",
        apiConfigured = true,
        approvalModeLabel = "按需",
        goalText = "复刻 Codex 工作台",
        goalPhaseLabel = "运行中",
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
        changedFiles = changedFiles,
        sourceLinks = 1,
        uiReferences = 1,
        tokenEstimate = 2_000,
        contextPressure = "轻量",
        builtInTools = 9,
        totalTools = 9,
        mcpServers = 0,
    )

    private fun checklist(state: ChecklistState) = SessionChecklistSummary(
        title = when (state) {
            ChecklistState.READY -> "可以交付或交接"
            ChecklistState.WATCH -> "接近就绪"
            ChecklistState.MISSING -> "还不能交付"
        },
        detail = "detail",
        items = listOf(
            SessionChecklistItem("目标", "复刻 Codex 工作台", state, "/plan"),
            SessionChecklistItem("验证证据", "1 条验证通过", ChecklistState.READY, "/verify"),
        ),
    )

    private fun nextAction(command: String = "/checklist") = NextActionDecision(
        priority = NextActionPriority.CONTINUE,
        title = "继续推进或交付",
        reason = "目标、计划、验证和恢复状态没有硬缺口。",
        evidence = listOf("清单: 可以交付或交接"),
        command = command,
    )

    private fun evidence(uiReferences: Int = 0, verifications: Int = 0): EvidenceLedger =
        EvidenceLedger(
            buildList {
                repeat(uiReferences) { index ->
                    add(EvidenceItem(EvidenceKind.UI_REFERENCE, "ref-$index.png", "来自截图", state = "图片"))
                }
                repeat(verifications) { index ->
                    add(EvidenceItem(EvidenceKind.VERIFY, "./gradlew test $index", "BUILD SUCCESSFUL", state = "通过"))
                }
            },
        )

    private fun parity(state: ParityState) = CodexParityAudit(
        items = listOf(
            CodexParityItem("三栏工作台", ParityState.READY, "ready", "/status"),
            CodexParityItem("交付清单", state, "detail", "/checklist"),
        ),
    )

    private fun blueprint(
        state: BlueprintState = BlueprintState.VERIFYING,
        references: Int = 1,
    ) = UiReplicaBlueprint(
        state = state,
        title = "核验复刻结果",
        referenceCount = references,
        extractionTasks = listOf("布局"),
        targetSurfaces = listOf("ChatPane"),
        acceptanceChecks = listOf("assembleProotDebug"),
        primaryCommand = "/verify",
    )

    private fun visualAcceptance(waiting: Int = 0) = VisualAcceptanceSummary(
        title = if (waiting == 0) "视觉复刻可核查" else "等待视觉验证证据",
        referenceCount = 1,
        items = buildList {
            add(VisualAcceptanceItem("截图证据", VisualAcceptanceState.READY, "ok", "/references"))
            repeat(waiting) { add(VisualAcceptanceItem("验证证据$it", VisualAcceptanceState.NEEDS_VERIFICATION, "wait", "/verify")) }
        },
        primaryCommand = if (waiting == 0) "/report" else "/verify",
    )

    private fun referenceBoard(open: Int = 0) = UiReferenceBoard(
        title = if (open == 0) "截图参考板已闭环" else "截图参考等待验证",
        items = buildList {
            add(
                UiReferenceBoardItem(
                    label = "图/附件 1: Codex screenshot.png · UI 参考",
                    kind = UiReferenceKind.CODEX_SCREENSHOT,
                    state = UiReferenceBoardState.READY,
                    detail = "ok",
                    evidence = listOf("ref"),
                    extractionTargets = listOf("布局结构"),
                    commands = listOf("/report"),
                ),
            )
            repeat(open) {
                add(
                    UiReferenceBoardItem(
                        label = "待处理$it",
                        kind = UiReferenceKind.UI_SCREENSHOT,
                        state = UiReferenceBoardState.VERIFYING,
                        detail = "wait",
                        evidence = listOf("wait"),
                        extractionTargets = listOf("验证证据"),
                        commands = listOf("/visual-check"),
                    ),
                )
            }
        },
        primaryCommand = if (open == 0) "/report" else "/visual-check",
    )

    private fun screenshotTrace(waiting: Int = 0) = ScreenshotImplementationTrace(
        title = if (waiting == 0) "截图实现追踪已闭环" else "截图实现等待验证",
        items = buildList {
            add(
                ScreenshotTraceItem(
                    reference = "图/附件 1: Codex screenshot.png · UI 参考",
                    state = TraceState.READY,
                    targetSurface = "SearchOverlay / CommandPalette",
                    targetFiles = listOf("app/src/main/java/com/andmx/ui/workbench/CommandPalette.kt"),
                    changedFiles = emptyList(),
                    verification = listOf("通过 · ./gradlew test"),
                    acceptance = listOf("ok"),
                    command = "/report",
                ),
            )
            repeat(waiting) {
                add(
                    ScreenshotTraceItem(
                        reference = "待处理$it",
                        state = TraceState.NEEDS_VERIFICATION,
                        targetSurface = "AgentInspectorPane",
                        targetFiles = listOf("app/src/main/java/com/andmx/ui/workbench/AgentInspectorPane.kt"),
                        changedFiles = listOf("app/src/main/java/com/andmx/ui/workbench/AgentInspectorPane.kt"),
                        verification = listOf("暂无验证记录"),
                        acceptance = listOf("verify"),
                        command = "/verify",
                    ),
                )
            }
        },
        primaryCommand = if (waiting == 0) "/report" else "/verify",
    )

    private fun designSystem(watch: Int = 0, gap: Int = 0) = CodexDesignSystemAudit(
        title = if (watch == 0 && gap == 0) "Codex 设计系统审计已闭环" else "Codex 设计系统正在对齐",
        referenceCount = 1,
        items = buildList {
            add(CodexDesignSystemItem("工作台密度", DesignAuditState.READY, "principle", "andmx", "/surfaces"))
            repeat(watch) { add(CodexDesignSystemItem("注意$it", DesignAuditState.WATCH, "principle", "andmx", "/design-system")) }
            repeat(gap) { add(CodexDesignSystemItem("缺口$it", DesignAuditState.GAP, "principle", "andmx", "/design-system")) }
        },
        primaryCommand = if (watch == 0 && gap == 0) "/report" else "/design-system",
    )

    private fun selfModel(watch: Int = 0, gap: Int = 0) = CodexSelfModel(
        title = if (watch == 0 && gap == 0) "Codex 自我模型已可复用" else "Codex 自我模型接近闭环",
        layers = buildList {
            add(CodexSelfModelLayer("目标与线程", SelfModelState.READY, "codex", "andmx", "/plan"))
            repeat(watch) { add(CodexSelfModelLayer("关注$it", SelfModelState.WATCH, "codex", "andmx", "/self-model")) }
            repeat(gap) { add(CodexSelfModelLayer("缺口$it", SelfModelState.GAP, "codex", "andmx", "/self-model")) }
        },
        operatingLoop = listOf("观察", "执行"),
        environmentFacts = listOf("环境"),
        primaryCommand = if (watch == 0 && gap == 0) "/report" else "/self-model",
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

    private fun interactionFlow(blocked: Int = 0, watch: Int = 0) = CodexInteractionFlow(
        title = if (blocked == 0 && watch == 0) "Codex 交互流程已闭环" else "Codex 交互流程仍需关注",
        steps = buildList {
            add(CodexInteractionStep("目标进入", InteractionFlowState.READY, "codex", "andmx", listOf("ok"), "/plan"))
            repeat(blocked) {
                add(CodexInteractionStep("阻塞$it", InteractionFlowState.BLOCKED, "codex", "andmx", listOf("gap"), "/flow"))
            }
            repeat(watch) {
                add(CodexInteractionStep("注意$it", InteractionFlowState.WATCH, "codex", "andmx", listOf("watch"), "/flow"))
            }
        },
        currentAction = nextAction(),
        primaryCommand = if (blocked == 0 && watch == 0) "/report" else "/flow",
    )
}
