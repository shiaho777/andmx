package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexDesignSystemAuditTest {

    @Test
    fun designSystemAuditWaitsForScreenshotEvidence() {
        val audit = buildCodexDesignSystemAudit(
            references = UiReferenceLedger(emptyList()),
            blueprint = blueprint(BlueprintState.WAITING_REFERENCES),
            surfaceMap = surfaceMap(waiting = 1),
            visualAcceptance = visualAcceptance(waiting = 3),
            snapshot = snapshot(uiReferences = 0),
            evidence = EvidenceLedger(emptyList()),
        )

        assertEquals("等待 Codex 截图补齐设计证据", audit.title)
        assertEquals("/references", audit.primaryCommand)
        assertEquals(DesignAuditState.WATCH, audit.items.first { it.title == "截图证据" }.state)
        assertTrue(audit.watchCount > 0)

        val text = codexDesignSystemText(audit)
        assertTrue(text.contains("## Codex 设计系统审计"))
        assertTrue(text.contains("工作台密度"))
        assertTrue(text.contains("Computer Use 不能读取当前 Codex 宿主窗口"))
    }

    @Test
    fun designSystemAuditTracksReferencesSurfacesAndVerification() {
        val audit = buildCodexDesignSystemAudit(
            references = references(),
            blueprint = blueprint(BlueprintState.VERIFYING),
            surfaceMap = surfaceMap(),
            visualAcceptance = visualAcceptance(),
            snapshot = snapshot(uiReferences = 1, changedFiles = 2),
            evidence = EvidenceLedger(
                listOf(EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过")),
            ),
            screenshotExtraction = screenshotExtraction(),
        )

        assertEquals("Codex 设计系统审计已闭环", audit.title)
        assertEquals("/report", audit.primaryCommand)
        assertEquals(0, audit.gapCount)
        assertEquals(0, audit.watchCount)
        assertEquals(audit.items.size, audit.readyCount)
        assertEquals(DesignAuditState.READY, audit.items.first { it.title == "实现与验证" }.state)
        assertEquals("/screenshot-extract", audit.items.first { it.title == "截图证据" }.command)
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
                add(CodexSurfaceSpec("就绪", SurfaceReadiness.READY, "", "", emptyList(), emptyList(), "", "/report"))
            }
        },
        primaryCommand = "/surfaces",
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
