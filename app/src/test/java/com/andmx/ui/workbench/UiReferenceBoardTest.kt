package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiReferenceBoardTest {

    @Test
    fun boardWaitsForScreenshotReferences() {
        val board = buildUiReferenceBoard(
            references = UiReferenceLedger(emptyList()),
            blueprint = blueprint(BlueprintState.WAITING_REFERENCES),
            screenshotExtraction = screenshotExtraction(waiting = 1),
            visualAcceptance = visualAcceptance(waiting = 1),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 0),
        )

        assertEquals("等待截图建立参考板", board.title)
        assertEquals("/references", board.primaryCommand)
        assertEquals(1, board.openCount)
        assertEquals(UiReferenceBoardState.WAITING, board.items.first().state)
    }

    @Test
    fun boardClassifiesCodexScreenshotsAndTracksExtraction() {
        val board = buildUiReferenceBoard(
            references = references(),
            blueprint = blueprint(BlueprintState.READY_TO_EXTRACT),
            screenshotExtraction = screenshotExtraction(waiting = 1),
            visualAcceptance = visualAcceptance(waiting = 1),
            designSystem = designSystem(watch = 1),
            evidence = EvidenceLedger(listOf(EvidenceItem(EvidenceKind.UI_REFERENCE, "Codex screenshot.png", "来自截图", state = "图片"))),
            snapshot = snapshot(uiReferences = 1),
        )

        assertEquals("截图参考等待解析", board.title)
        assertEquals(1, board.codexCount)
        assertEquals(UiReferenceKind.CODEX_SCREENSHOT, board.items.first().kind)
        assertEquals(UiReferenceBoardState.READY_TO_EXTRACT, board.items.first().state)
        assertEquals("/screenshot-extract", board.primaryCommand)
    }

    @Test
    fun boardTextShowsPerImageTargetsAndEvidence() {
        val text = uiReferenceBoardText(
            buildUiReferenceBoard(
                references = references(),
                blueprint = blueprint(BlueprintState.VERIFYING),
                screenshotExtraction = screenshotExtraction(),
                visualAcceptance = visualAcceptance(),
                designSystem = designSystem(),
                evidence = EvidenceLedger(listOf(EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过"))),
                snapshot = snapshot(uiReferences = 1, changedFiles = 1),
            ),
        )

        assertTrue(text.contains("## 截图参考板"))
        assertTrue(text.contains("Codex screenshot.png"))
        assertTrue(text.contains("#### 解析目标"))
        assertTrue(text.contains("设计 token"))
        assertTrue(text.contains("#### 证据"))
    }

    private fun references() = UiReferenceLedger(
        listOf(UiReferenceItem("Codex screenshot.png · UI 参考", "来自用户图片/截图输入", image = true)),
    )

    private fun blueprint(state: BlueprintState) = UiReplicaBlueprint(
        state = state,
        title = "状态",
        referenceCount = if (state == BlueprintState.WAITING_REFERENCES) 0 else 1,
        extractionTasks = listOf("布局"),
        targetSurfaces = listOf("ChatPane"),
        acceptanceChecks = listOf("compile"),
        primaryCommand = "/blueprint",
    )

    private fun screenshotExtraction(waiting: Int = 0) = ScreenshotExtractionSummary(
        title = if (waiting == 0) "截图复刻解析已闭环" else "等待验证截图复刻",
        referenceCount = 1,
        items = buildList {
            add(ScreenshotExtractionItem("图 1: Codex screenshot.png", ScreenshotExtractionState.READY, "ok", emptyList(), emptyList(), emptyList(), "/report"))
            repeat(waiting) {
                add(ScreenshotExtractionItem("待处理$it", ScreenshotExtractionState.NEEDS_EXTRACTION, "wait", emptyList(), emptyList(), emptyList(), "/blueprint"))
            }
        },
        primaryCommand = if (waiting == 0) "/report" else "/blueprint",
    )

    private fun visualAcceptance(waiting: Int = 0) = VisualAcceptanceSummary(
        title = if (waiting == 0) "视觉复刻可核查" else "等待截图参考",
        referenceCount = if (waiting == 0) 1 else 0,
        items = buildList {
            add(VisualAcceptanceItem("截图证据", if (waiting == 0) VisualAcceptanceState.READY else VisualAcceptanceState.NEEDS_REFERENCE, "ref", "/references"))
            repeat(waiting) {
                add(VisualAcceptanceItem("待处理$it", VisualAcceptanceState.NEEDS_REFERENCE, "wait", "/references"))
            }
        },
        primaryCommand = if (waiting == 0) "/report" else "/references",
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

    private fun snapshot(uiReferences: Int, changedFiles: Int = 0) = AgentInspectorSnapshot(
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
