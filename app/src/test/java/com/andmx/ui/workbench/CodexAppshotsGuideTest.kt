package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppshotsGuideTest {

    @Test
    fun guidePrioritizesReferenceCaptureWhenNoVisualContextExists() {
        val guide = buildCodexAppshotCaptureGuide(
            references = UiReferenceLedger(emptyList()),
            evidence = EvidenceLedger(emptyList()),
            snapshot = snapshot(changedFiles = 0),
        )

        assertEquals(AppshotCaptureState.WAITING_REFERENCE, guide.state)
        assertEquals("/references", guide.primaryCommand)
        assertTrue(guide.sourceFacts.any { it.contains("Appshots") })
        assertTrue(guide.sourceFacts.any { it.contains("不能自动化 Codex 自身") })
    }

    @Test
    fun guideMovesToExtractionWhenReferenceExists() {
        val guide = buildCodexAppshotCaptureGuide(
            references = UiReferenceLedger(
                listOf(
                    UiReferenceItem(
                        label = "Codex command palette screenshot",
                        detail = "来自用户图片/截图输入",
                        image = true,
                        meta = "1440x900 · image/png · ref:abcd1234",
                        assetPath = "ui-references/ref-abcd1234.png",
                    ),
                ),
            ),
            evidence = EvidenceLedger(
                listOf(
                    EvidenceItem(EvidenceKind.UI_REFERENCE, "Codex command palette screenshot", "ref:abcd1234"),
                ),
            ),
            snapshot = snapshot(changedFiles = 0),
        )

        assertEquals(AppshotCaptureState.READY_TO_EXTRACT, guide.state)
        assertEquals(1, guide.codexReferenceCount)
        assertEquals(1, guide.assetCount)
        assertEquals("/screenshot-extract", guide.primaryCommand)
    }

    @Test
    fun guideTextIncludesLandingPathAndSafetyNotes() {
        val text = codexAppshotCaptureGuideText(
            buildCodexAppshotCaptureGuide(
                references = UiReferenceLedger(emptyList()),
                evidence = EvidenceLedger(emptyList()),
                snapshot = snapshot(changedFiles = 0),
            ),
        )

        assertTrue(text.contains("## Codex Appshots 采集单"))
        assertTrue(text.contains("### 官方能力事实"))
        assertTrue(text.contains("### 采集路径"))
        assertTrue(text.contains("### AndMX 落地路径"))
        assertTrue(text.contains("入口: `/screenshot-extract`"))
        assertTrue(text.contains("### 安全与边界"))
    }

    private fun snapshot(changedFiles: Int) = AgentInspectorSnapshot(
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
        messageCount = 2,
        userMessages = 1,
        assistantMessages = 1,
        toolEvents = 0,
        runningTools = 0,
        failedTools = 0,
        approvalEvents = 0,
        pendingApprovals = 0,
        changedFiles = changedFiles,
        sourceLinks = 0,
        uiReferences = 0,
        tokenEstimate = 800,
        contextPressure = "轻量",
        builtInTools = 9,
        totalTools = 9,
        mcpServers = 0,
    )
}
