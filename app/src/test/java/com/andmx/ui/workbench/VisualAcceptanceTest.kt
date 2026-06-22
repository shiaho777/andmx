package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAcceptanceTest {

    @Test
    fun visualAcceptanceWaitsForScreenshotReferencesFirst() {
        val summary = buildVisualAcceptanceSummary(
            references = UiReferenceLedger(emptyList()),
            blueprint = blueprint(BlueprintState.WAITING_REFERENCES),
            surfaceMap = surfaceMap(waiting = 1),
            snapshot = snapshot(uiReferences = 0),
            evidence = EvidenceLedger(emptyList()),
            verifications = emptyList(),
        )

        assertEquals("等待截图参考", summary.title)
        assertEquals("/references", summary.primaryCommand)
        assertTrue(summary.items.all { it.state == VisualAcceptanceState.NEEDS_REFERENCE || it.title == "控件与状态" || it.title == "交互路径" })
    }

    @Test
    fun visualAcceptanceRequiresImplementationAndVerificationAfterExtraction() {
        val summary = buildVisualAcceptanceSummary(
            references = references(),
            blueprint = blueprint(BlueprintState.READY_TO_EXTRACT),
            surfaceMap = surfaceMap(partial = 1),
            snapshot = snapshot(uiReferences = 1),
            evidence = EvidenceLedger(emptyList()),
            verifications = emptyList(),
        )

        assertEquals("等待提取截图结构", summary.title)
        assertEquals("/blueprint", summary.primaryCommand)
        assertEquals(VisualAcceptanceState.NEEDS_EXTRACTION, summary.items.first { it.title == "布局提取" }.state)
        assertEquals(VisualAcceptanceState.NEEDS_IMPLEMENTATION, summary.items.first { it.title == "Compose 落地" }.state)
    }

    @Test
    fun visualAcceptanceCanBecomeReadyWithChangesAndPassedVerification() {
        val summary = buildVisualAcceptanceSummary(
            references = references(),
            blueprint = blueprint(BlueprintState.VERIFYING),
            surfaceMap = surfaceMap(),
            snapshot = snapshot(uiReferences = 1, changedFiles = 2),
            evidence = EvidenceLedger(listOf(EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过"))),
            verifications = listOf(VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
        )

        assertEquals("视觉复刻可核查", summary.title)
        assertEquals("/report", summary.primaryCommand)
        assertEquals(0, summary.waitingCount)
        assertEquals(summary.items.size, summary.readyCount)

        val text = visualAcceptanceText(summary)
        assertTrue(text.contains("## 视觉验收清单"))
        assertTrue(text.contains("布局提取"))
        assertTrue(text.contains("移动端稳定性"))
        assertTrue(text.contains("验证证据"))
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
