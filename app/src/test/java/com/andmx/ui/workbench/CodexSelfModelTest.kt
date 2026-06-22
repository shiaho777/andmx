package com.andmx.ui.workbench

import com.andmx.agent.ApprovalMode
import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSelfModelTest {

    @Test
    fun selfModelIsReadyWhenArchitectureEvidenceAndDesignAreReady() {
        val model = buildCodexSelfModel(
            snapshot = snapshot(apiConfigured = true, uiReferences = 1, toolEvents = 3),
            architecture = architecture(ArchitectureState.READY),
            surfaceMap = surfaceMap(),
            designSystem = designSystem(),
            screenshotExtraction = screenshotExtraction(),
            interactionFlow = interactionFlow(),
            policy = buildToolPolicySummary(ApprovalMode.ASK, tools()),
            evidence = evidence(),
            checklist = checklist(ChecklistState.READY),
            runtime = runtime(RuntimeEnvironmentLevel.READY),
            instructionSummary = instructionSummary(),
        )

        assertEquals("Codex 自我模型已可复用", model.title)
        assertEquals("/report", model.primaryCommand)
        assertEquals(0, model.gapCount)
        assertEquals(0, model.watchCount)
        assertEquals(model.layers.size, model.readyCount)
    }

    @Test
    fun selfModelFlagsMissingGoalSetupAndRuntime() {
        val model = buildCodexSelfModel(
            snapshot = snapshot(apiConfigured = false, totalTools = 0),
            architecture = architecture(ArchitectureState.GAP),
            surfaceMap = surfaceMap(waiting = 1),
            designSystem = designSystem(watch = 1),
            screenshotExtraction = screenshotExtraction(waiting = 1),
            interactionFlow = interactionFlow(blocked = 1),
            policy = buildToolPolicySummary(ApprovalMode.READ_ONLY, emptyList()),
            evidence = EvidenceLedger(emptyList()),
            checklist = checklist(ChecklistState.MISSING),
            runtime = runtime(RuntimeEnvironmentLevel.LIMITED),
            instructionSummary = instructionSummary(apiConfigured = false),
        )

        assertEquals("Codex 自我模型仍有缺口", model.title)
        assertTrue(model.gapCount >= 4)
        assertEquals("/plan", model.primaryCommand)
        assertEquals(SelfModelState.GAP, model.layers.first { it.title == "目标与线程" }.state)
        assertEquals(SelfModelState.GAP, model.layers.first { it.title == "执行环境" }.state)
    }

    @Test
    fun selfModelTextShowsLoopEnvironmentAndLayers() {
        val text = codexSelfModelText(
            buildCodexSelfModel(
                snapshot = snapshot(apiConfigured = true, uiReferences = 1, toolEvents = 2),
                architecture = architecture(ArchitectureState.WATCH),
                surfaceMap = surfaceMap(partial = 1),
                designSystem = designSystem(watch = 1),
                screenshotExtraction = screenshotExtraction(waiting = 1),
                interactionFlow = interactionFlow(watch = 1),
                policy = buildToolPolicySummary(ApprovalMode.ASK, tools()),
                evidence = evidence(),
                checklist = checklist(ChecklistState.WATCH),
                runtime = runtime(RuntimeEnvironmentLevel.WATCH),
                instructionSummary = instructionSummary(),
                toolCapabilityMap = buildCodexToolCapabilityMap(tools(), mcpServerCount = 0),
            ),
        )

        assertTrue(text.contains("## Codex 自我模型"))
        assertTrue(text.contains("### 工作循环"))
        assertTrue(text.contains("### 环境事实"))
        assertTrue(text.contains("### 能力层"))
        assertTrue(text.contains("工具与授权"))
        assertTrue(text.contains("Live UI 限制"))
        assertTrue(text.contains("Codex app 表面"))
        assertTrue(text.contains("in-app browser"))
        assertTrue(text.contains("Computer Use"))
        assertTrue(text.contains("工作循环"))
        assertTrue(text.contains("环境契约"))
        assertTrue(text.contains("能力域"))
    }

    private fun snapshot(
        apiConfigured: Boolean,
        uiReferences: Int = 0,
        toolEvents: Int = 0,
        totalTools: Int = 9,
    ) = AgentInspectorSnapshot(
        project = "andmx",
        model = "gpt-test",
        baseUrl = "https://api.example.test",
        apiConfigured = apiConfigured,
        approvalModeLabel = "按需",
        goalText = if (apiConfigured) "复刻 Codex 工作台" else "",
        goalPhaseLabel = "运行中",
        goalNote = "",
        busy = false,
        reasoningEffort = "medium",
        persona = "默认",
        messageCount = 8,
        userMessages = 3,
        assistantMessages = 3,
        toolEvents = toolEvents,
        runningTools = 0,
        failedTools = 0,
        approvalEvents = 0,
        pendingApprovals = 0,
        changedFiles = 0,
        sourceLinks = 1,
        uiReferences = uiReferences,
        tokenEstimate = 2_000,
        contextPressure = "轻量",
        builtInTools = totalTools,
        totalTools = totalTools,
        mcpServers = 0,
    )

    private fun architecture(state: ArchitectureState) = AgentArchitectureBlueprint(
        state = state,
        title = when (state) {
            ArchitectureState.READY -> "Codex 式执行闭环已成形"
            ArchitectureState.WATCH -> "架构闭环接近可用"
            ArchitectureState.GAP -> "架构链路仍有缺口"
        },
        layers = listOf(
            ArchitectureLayer("目标", state, "detail", "/plan"),
        ),
        flow = listOf("flow"),
        invariants = listOf("invariant"),
        primaryCommand = "/architecture",
    )

    private fun surfaceMap(waiting: Int = 0, partial: Int = 0) = CodexSurfaceMap(
        title = "surface map",
        referenceCount = if (waiting == 0) 1 else 0,
        surfaces = buildList {
            repeat(waiting) { add(CodexSurfaceSpec("等截图$it", SurfaceReadiness.WAITING_REFERENCE, "", "", emptyList(), emptyList(), "", "/references")) }
            repeat(partial) { add(CodexSurfaceSpec("待补齐$it", SurfaceReadiness.PARTIAL, "", "", emptyList(), emptyList(), "", "/surfaces")) }
            if (waiting == 0 && partial == 0) add(CodexSurfaceSpec("就绪", SurfaceReadiness.READY, "", "", emptyList(), emptyList(), "", "/report"))
        },
        primaryCommand = "/surfaces",
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
        currentAction = NextActionDecision(NextActionPriority.CONTINUE, "继续", "reason", emptyList(), "/report"),
        primaryCommand = if (blocked == 0 && watch == 0) "/report" else "/flow",
    )

    private fun evidence() = EvidenceLedger(
        listOf(
            EvidenceItem(EvidenceKind.FILE, "/root/app.kt", "来自工具 read_file", target = "/root/app.kt", state = "完成"),
            EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过"),
        ),
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

    private fun runtime(level: RuntimeEnvironmentLevel): RuntimeEnvironmentSummary =
        RuntimeEnvironmentSummary(
            level = level,
            healthLabel = when (level) {
                RuntimeEnvironmentLevel.READY -> "Linux 沙箱就绪"
                RuntimeEnvironmentLevel.WATCH -> "等待 rootfs"
                RuntimeEnvironmentLevel.LIMITED -> "轻量执行面"
            },
            healthDetail = "detail",
            executionSurface = "Android/proot Alpine",
            bootstrapStatus = "已捆绑",
            rootfsStatus = if (level == RuntimeEnvironmentLevel.READY) "已安装" else "未安装",
            binaryStatus = "proot + loader 就绪",
            abiStatus = "arm64-v8a",
            rootfsPath = "/rootfs",
            usrPath = "/usr",
            tmpPath = "/tmp",
        )

    private fun instructionSummary(apiConfigured: Boolean = true) = InstructionStackSummary(
        apiStatus = if (apiConfigured) "API Key 已配置" else "API Key 未配置",
        mcpStatus = "MCP 未填写",
        customInstructionStatus = "未设置自定义指令",
        customInstructionPreview = "不会额外附加用户自定义指令。",
        visibleLayers = listOf("应用边界", "用户设置", "工具注册"),
        safetyBoundary = "安全边界",
    )

    private fun tools(): List<ToolCapability> = listOf(
        ToolCapability("read_file", "读取文件", ToolRisk.READ),
        ToolCapability("apply_patch", "应用补丁", ToolRisk.WRITE),
        ToolCapability("run_shell", "运行命令", ToolRisk.EXECUTE),
        ToolCapability("web_search", "搜索网页", ToolRisk.NETWORK),
    )
}
