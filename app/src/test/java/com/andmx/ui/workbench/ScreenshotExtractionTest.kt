package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotExtractionTest {

    @Test
    fun extractionWaitsForReferencesBeforeScreenshotsArrive() {
        val summary = buildScreenshotExtractionSummary(
            references = UiReferenceLedger(emptyList()),
            blueprint = blueprint(BlueprintState.WAITING_REFERENCES),
            surfaceMap = surfaceMap(waiting = 1),
            visualAcceptance = visualAcceptance(waiting = 3),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 0),
        )

        assertEquals("等待截图开始解析", summary.title)
        assertEquals("/appshots", summary.primaryCommand)
        assertEquals(1, summary.items.size)
        assertEquals(ScreenshotExtractionState.WAITING_REFERENCE, summary.items.first().state)
        assertTrue(summary.items.first().dimensions.any { it.title == "布局结构" })
    }

    @Test
    fun extractionMovesThroughImplementationAndVerification() {
        val references = references()
        val readyToExtract = buildScreenshotExtractionSummary(
            references = references,
            blueprint = blueprint(BlueprintState.READY_TO_EXTRACT),
            surfaceMap = surfaceMap(),
            visualAcceptance = visualAcceptance(waiting = 2),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 1),
        )
        assertEquals(ScreenshotExtractionState.NEEDS_EXTRACTION, readyToExtract.items.first().state)
        assertEquals("/blueprint", readyToExtract.primaryCommand)

        val needsImplementation = buildScreenshotExtractionSummary(
            references = references,
            blueprint = blueprint(BlueprintState.IMPLEMENTING),
            surfaceMap = surfaceMap(),
            visualAcceptance = visualAcceptance(waiting = 2),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 1),
        )
        assertEquals(ScreenshotExtractionState.NEEDS_IMPLEMENTATION, needsImplementation.items.first().state)
        assertEquals("/changes", needsImplementation.primaryCommand)

        val needsVerification = buildScreenshotExtractionSummary(
            references = references,
            blueprint = blueprint(BlueprintState.IMPLEMENTING),
            surfaceMap = surfaceMap(),
            visualAcceptance = visualAcceptance(waiting = 2),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 1, changedFiles = 2),
        )
        assertEquals(ScreenshotExtractionState.NEEDS_VERIFICATION, needsVerification.items.first().state)
        assertEquals("/visual-check", needsVerification.primaryCommand)
    }

    @Test
    fun extractionCanBecomeReadyWithChangesVerificationAndVisualClosure() {
        val summary = buildScreenshotExtractionSummary(
            references = references(),
            blueprint = blueprint(BlueprintState.VERIFYING),
            surfaceMap = surfaceMap(),
            visualAcceptance = visualAcceptance(),
            designSystem = designSystem(),
            evidence = EvidenceLedger(
                listOf(EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过")),
            ),
            snapshot = snapshot(uiReferences = 1, changedFiles = 2),
        )

        assertEquals("截图复刻解析已闭环", summary.title)
        assertEquals("/report", summary.primaryCommand)
        assertEquals(0, summary.waitingCount)
        assertEquals(ScreenshotExtractionState.READY, summary.items.first().state)

        val text = screenshotExtractionText(summary)
        assertTrue(text.contains("## 截图解析清单"))
        assertTrue(text.contains("### 图 1: codex.png · UI 参考"))
        assertTrue(text.contains("#### 解析维度"))
        assertTrue(text.contains("设计 token"))
        assertTrue(text.contains("#### 验收"))
        assertTrue(text.contains("#### 执行单"))
        assertTrue(text.contains("##### 目标文件"))
        assertTrue(text.contains("##### 验证门槛"))
    }

    @Test
    fun extractionBuildsWorkOrderForCommandPaletteScreenshots() {
        val references = UiReferenceLedger(
            listOf(
                UiReferenceItem(
                    "Codex command palette screenshot.png · UI 参考",
                    "来自截图 · 参考ID: ref:abcd1234 · 本地资产: ui-references/ref-abcd1234.png",
                    image = true,
                    meta = "ref:abcd1234",
                    assetPath = "ui-references/ref-abcd1234.png",
                ),
            ),
        )
        val summary = buildScreenshotExtractionSummary(
            references = references,
            blueprint = blueprint(BlueprintState.READY_TO_EXTRACT),
            surfaceMap = surfaceMapWithCodexSurfaces(),
            visualAcceptance = visualAcceptance(waiting = 2),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 1),
        )

        val order = summary.items.first().workOrder

        assertEquals("图 1 复刻执行单", order.title)
        assertEquals("ref:abcd1234", order.referenceId)
        assertEquals("ui-references/ref-abcd1234.png", order.assetPath)
        assertTrue(order.targetSurface.contains("CommandPalette"))
        assertTrue(order.targetFiles.any { it.endsWith("CommandPalette.kt") })
        assertTrue(order.targetFiles.any { it.endsWith("SearchOverlay.kt") })
        assertTrue(order.targetFiles.any { it.endsWith("SlashCommands.kt") })
        assertTrue(order.observationChecklist.any { it.contains("搜索框") })
        assertTrue(order.observationChecklist.any { it.contains("最近命令") })
        assertTrue(order.implementationMoves.any { it.contains("CommandPalette.kt") })
        assertTrue(order.implementationMoves.any { it.contains("SearchOverlay.kt") })
        assertTrue(order.verificationGates.any { it.contains("CommandPaletteTest") })
        assertTrue(order.verificationGates.any { it.contains("SlashCommandsTest") })
        assertTrue(order.verificationGates.any { it.contains("compileProotDebugKotlin") })
        assertTrue(order.verificationGates.any { it.contains("本地资产路径") })
        assertTrue(order.commands.contains("/trace"))
        assertTrue(screenshotExtractionText(summary).contains("参考ID: `ref:abcd1234`"))
        assertTrue(screenshotExtractionText(summary).contains("本地资产: `ui-references/ref-abcd1234.png`"))
    }

    @Test
    fun extractionBuildsWorkOrderForComposerScreenshots() {
        val references = UiReferenceLedger(
            listOf(UiReferenceItem("Composer attachment preflight screenshot.png · UI 参考", "来自截图", image = true)),
        )
        val summary = buildScreenshotExtractionSummary(
            references = references,
            blueprint = blueprint(BlueprintState.READY_TO_EXTRACT),
            surfaceMap = surfaceMapWithCodexSurfaces(),
            visualAcceptance = visualAcceptance(waiting = 2),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 1),
        )

        val order = summary.items.first().workOrder

        assertTrue(order.targetSurface.contains("Composer"))
        assertTrue(order.targetFiles.any { it.endsWith("Composer.kt") })
        assertTrue(order.targetFiles.any { it.endsWith("Attachment.kt") })
        assertTrue(order.observationChecklist.any { it.contains("文本框") })
        assertTrue(order.observationChecklist.any { it.contains("附件预检") })
        assertTrue(order.implementationMoves.any { it.contains("Composer.kt") })
        assertTrue(order.implementationMoves.any { it.contains("Attachment.kt") })
        assertTrue(order.verificationGates.any { it.contains("AttachmentsTest") })
        assertTrue(order.commands.first() == "/blueprint")
    }

    @Test
    fun extractionBuildsWorkOrderForInspectorScreenshots() {
        val references = UiReferenceLedger(
            listOf(UiReferenceItem("Codex inspector status screenshot.png · UI 参考", "来自截图", image = true)),
        )
        val summary = buildScreenshotExtractionSummary(
            references = references,
            blueprint = blueprint(BlueprintState.READY_TO_EXTRACT),
            surfaceMap = surfaceMapWithCodexSurfaces(),
            visualAcceptance = visualAcceptance(waiting = 2),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 1),
        )

        val order = summary.items.first().workOrder

        assertTrue(order.targetSurface.contains("AgentInspectorPane"))
        assertTrue(order.targetFiles.any { it.endsWith("AgentInspectorPane.kt") })
        assertTrue(order.observationChecklist.any { it.contains("模型") })
        assertTrue(order.observationChecklist.any { it.contains("上下文") })
        assertTrue(order.implementationMoves.any { it.contains("AgentInspectorPane.kt") })
        assertTrue(order.verificationGates.any { it.contains("AgentInspectorModelTest") })
    }

    private fun references() = UiReferenceLedger(
        listOf(UiReferenceItem("codex.png · UI 参考", "来自截图", image = true)),
    )

    private fun blueprint(state: BlueprintState) = UiReplicaBlueprint(
        state = state,
        title = "状态",
        referenceCount = if (state == BlueprintState.WAITING_REFERENCES) 0 else 1,
        extractionTasks = listOf("布局", "控件"),
        targetSurfaces = listOf("ChatPane"),
        acceptanceChecks = listOf("compile"),
        primaryCommand = "/blueprint",
    )

    private fun surfaceMap(
        waiting: Int = 0,
        partial: Int = 0,
    ) = CodexSurfaceMap(
        title = "map",
        referenceCount = if (waiting > 0) 0 else 1,
        surfaces = buildList {
            repeat(waiting) {
                add(CodexSurfaceSpec("等截图$it", SurfaceReadiness.WAITING_REFERENCE, "", "", emptyList(), emptyList(), "", "/references"))
            }
            repeat(partial) {
                add(CodexSurfaceSpec("待补齐$it", SurfaceReadiness.PARTIAL, "", "", emptyList(), emptyList(), "", "/surfaces"))
            }
            if (waiting == 0 && partial == 0) {
                add(CodexSurfaceSpec("就绪", SurfaceReadiness.READY, "", "ChatPane", emptyList(), emptyList(), "", "/report"))
            }
        },
        primaryCommand = "/surfaces",
    )

    private fun surfaceMapWithCodexSurfaces() = CodexSurfaceMap(
        title = "map",
        referenceCount = 1,
        surfaces = listOf(
            CodexSurfaceSpec("会话流", SurfaceReadiness.READY, "codex", "MessageList / ChatPane", emptyList(), emptyList(), "", "/activity"),
            CodexSurfaceSpec("输入区", SurfaceReadiness.READY, "codex", "Composer / Attachments", emptyList(), emptyList(), "", "/references"),
            CodexSurfaceSpec("任务面板", SurfaceReadiness.READY, "codex", "ProgressPopover / WorkbenchScreen", emptyList(), emptyList(), "", "/report"),
            CodexSurfaceSpec("右侧 Inspector", SurfaceReadiness.READY, "codex", "AgentInspectorPane", emptyList(), emptyList(), "", "/architecture"),
            CodexSurfaceSpec("工作区面板", SurfaceReadiness.READY, "codex", "WorkPane / FilePane / TerminalPane / DiffPane / BrowserPane", emptyList(), emptyList(), "", "/changes"),
            CodexSurfaceSpec("命令面板", SurfaceReadiness.READY, "codex", "SearchOverlay / CommandPalette", emptyList(), emptyList(), "", "/help"),
            CodexSurfaceSpec("审批与安全", SurfaceReadiness.READY, "codex", "Approval cards / ToolPolicySummary", emptyList(), emptyList(), "", "/policy"),
            CodexSurfaceSpec("截图复刻流水线", SurfaceReadiness.PARTIAL, "codex", "UiReferenceLedger / UiReplicaBlueprint / CodexSurfaceMap", emptyList(), emptyList(), "", "/trace"),
        ),
        primaryCommand = "/surfaces",
    )

    private fun visualAcceptance(waiting: Int = 0) = VisualAcceptanceSummary(
        title = if (waiting == 0) "视觉复刻可核查" else "等待视觉验证证据",
        referenceCount = 1,
        items = buildList {
            add(VisualAcceptanceItem("截图证据", VisualAcceptanceState.READY, "ref", "/references"))
            repeat(waiting) {
                add(VisualAcceptanceItem("待处理$it", VisualAcceptanceState.NEEDS_VERIFICATION, "wait", "/verify"))
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

    private fun snapshot(
        uiReferences: Int,
        changedFiles: Int = 0,
    ) = AgentInspectorSnapshot(
        project = "andmx",
        model = "gpt-test",
        baseUrl = "https://api.example.test",
        apiConfigured = true,
        approvalModeLabel = "按需",
        goalText = "复刻 Codex UI",
        goalPhaseLabel = "运行中",
        goalNote = "",
        busy = false,
        reasoningEffort = "medium",
        persona = "默认",
        messageCount = 4,
        userMessages = 1,
        assistantMessages = 1,
        toolEvents = if (uiReferences > 0) 1 else 0,
        runningTools = 0,
        failedTools = 0,
        approvalEvents = 0,
        pendingApprovals = 0,
        changedFiles = changedFiles,
        sourceLinks = 0,
        uiReferences = uiReferences,
        tokenEstimate = 1_000,
        contextPressure = "轻量",
        builtInTools = 9,
        totalTools = 9,
        mcpServers = 0,
    )
}
