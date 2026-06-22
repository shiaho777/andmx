package com.andmx.ui.workbench

import com.andmx.agent.ApprovalMode
import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexParityAuditTest {

    @Test
    fun parityAuditFlagsRuntimeAndChecklistGaps() {
        val audit = buildCodexParityAudit(
            snapshot = snapshot(contextPressure = "轻量"),
            runtime = runtime(RuntimeEnvironmentLevel.LIMITED),
            policy = buildToolPolicySummary(ApprovalMode.ASK, tools()),
            evidence = EvidenceLedger(emptyList()),
            checklist = SessionChecklistSummary(
                title = "还不能交付",
                detail = "缺验证",
                items = listOf(
                    SessionChecklistItem("验证证据", "暂无测试", ChecklistState.MISSING, "/verify"),
                ),
            ),
            designSystem = designSystem(watch = 1),
            screenshotExtraction = screenshotExtraction(waiting = 1),
            interactionFlow = interactionFlow(blocked = 1),
            selfModel = selfModel(watch = 1),
        )

        assertEquals("仍有 Codex 对标缺口", audit.title)
        assertTrue(audit.gapCount >= 2)
        assertEquals(ParityState.GAP, audit.items.first { it.title == "本地执行环境" }.state)
        assertEquals(ParityState.GAP, audit.items.first { it.title == "交付清单" }.state)
        assertEquals(ParityState.WATCH, audit.items.first { it.title == "系统架构蓝图" }.state)
        assertEquals(ParityState.WATCH, audit.items.first { it.title == "Codex UI 表面地图" }.state)
        assertEquals(ParityState.WATCH, audit.items.first { it.title == "视觉验收清单" }.state)
        assertEquals(ParityState.WATCH, audit.items.first { it.title == "Codex 设计系统" }.state)
        assertEquals(ParityState.WATCH, audit.items.first { it.title == "截图解析流水线" }.state)
        assertEquals(ParityState.GAP, audit.items.first { it.title == "Codex 交互流程" }.state)
        assertEquals(ParityState.WATCH, audit.items.first { it.title == "Codex 自我模型" }.state)

        val text = codexParityText(audit)
        assertTrue(text.contains("## Codex 对标审计"))
        assertTrue(text.contains("**缺口** · 本地执行环境"))
        assertTrue(text.contains("### 下一步"))
    }

    @Test
    fun parityAuditRecognizesReadyCodexWorkbenchLoop() {
        val audit = buildCodexParityAudit(
            snapshot = snapshot(uiReferences = 1, toolEvents = 3),
            runtime = runtime(RuntimeEnvironmentLevel.READY),
            policy = buildToolPolicySummary(ApprovalMode.ASK, tools()),
            evidence = EvidenceLedger(
                listOf(
                    EvidenceItem(EvidenceKind.FILE, "/root/app.kt", "read", "/root/app.kt", "完成"),
                    EvidenceItem(EvidenceKind.UI_REFERENCE, "codex.png · UI 参考", "来自截图", state = "图片"),
                    EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过"),
                ),
            ),
            checklist = SessionChecklistSummary(
                title = "可以交付或交接",
                detail = "ok",
                items = listOf(
                    SessionChecklistItem("目标", "已设置", ChecklistState.READY, "/plan"),
                    SessionChecklistItem("验证证据", "1 条验证通过", ChecklistState.READY, "/verify"),
                ),
            ),
            designSystem = designSystem(),
            screenshotExtraction = screenshotExtraction(),
            interactionFlow = interactionFlow(),
            selfModel = selfModel(),
        )

        assertEquals(0, audit.gapCount)
        assertEquals(0, audit.items.count { it.state == ParityState.GAP })
        assertEquals(ParityState.READY, audit.items.first { it.title == "截图/UI 参考" }.state)
        assertEquals(ParityState.READY, audit.items.first { it.title == "Codex UI 表面地图" }.state)
        assertEquals(ParityState.READY, audit.items.first { it.title == "视觉验收清单" }.state)
        assertEquals(ParityState.READY, audit.items.first { it.title == "Codex 设计系统" }.state)
        assertEquals(ParityState.READY, audit.items.first { it.title == "截图解析流水线" }.state)
        assertEquals(ParityState.READY, audit.items.first { it.title == "Codex 交互流程" }.state)
        assertEquals(ParityState.READY, audit.items.first { it.title == "Codex 自我模型" }.state)
        assertEquals(ParityState.READY, audit.items.first { it.title == "证据账本" }.state)
        assertEquals(ParityState.READY, audit.items.first { it.title == "系统架构蓝图" }.state)
        assertTrue(codexParityText(audit).contains("Codex 对标能力已成形"))
    }

    private fun snapshot(
        contextPressure: String = "轻量",
        uiReferences: Int = 0,
        toolEvents: Int = 0,
        changedFiles: Int = 0,
    ) = AgentInspectorSnapshot(
        project = "andmx",
        model = "gpt-test",
        baseUrl = "https://api.example.test",
        apiConfigured = true,
        approvalModeLabel = "按需",
        goalText = "复刻 Codex",
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
        changedFiles = changedFiles,
        sourceLinks = 1,
        uiReferences = uiReferences,
        tokenEstimate = if (contextPressure == "需要压缩") 120_000 else 2_000,
        contextPressure = contextPressure,
        builtInTools = 9,
        totalTools = 9,
        mcpServers = 0,
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

    private fun tools(): List<ToolCapability> = listOf(
        ToolCapability("read_file", "读取文件", ToolRisk.READ),
        ToolCapability("apply_patch", "应用补丁", ToolRisk.WRITE),
        ToolCapability("run_shell", "运行命令", ToolRisk.EXECUTE),
        ToolCapability("web_search", "搜索网页", ToolRisk.NETWORK),
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
        title = if (blocked == 0 && watch == 0) "Codex 交互流程已闭环" else "Codex 交互流程存在阻塞",
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
}
