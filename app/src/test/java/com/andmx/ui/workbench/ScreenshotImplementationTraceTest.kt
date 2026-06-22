package com.andmx.ui.workbench

import com.andmx.agent.ChangeSummaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotImplementationTraceTest {

    @Test
    fun traceWaitsForScreenshotReferences() {
        val trace = buildScreenshotImplementationTrace(
            board = board(UiReferenceBoardState.WAITING),
            surfaceMap = surfaceMap(),
            changes = emptyList(),
            verifications = emptyList(),
            evidence = EvidenceLedger(emptyList()),
        )

        assertEquals("等待截图建立实现追踪", trace.title)
        assertEquals("/references", trace.primaryCommand)
        assertEquals(TraceState.WAITING_REFERENCE, trace.items.first().state)
        assertTrue(trace.items.first().targetFiles.isNotEmpty())
        assertTrue(trace.items.first().targetFiles.all { it.contains("app/src/main/java/com/andmx/ui/workbench/") })
    }

    @Test
    fun traceMapsScreenshotsToSurfacesFilesAndVerification() {
        val trace = buildScreenshotImplementationTrace(
            board = board(UiReferenceBoardState.VERIFYING, "Codex command palette screenshot.png · UI 参考"),
            surfaceMap = surfaceMap(),
            changes = listOf(ChangeSummaryItem("app/src/main/java/com/andmx/ui/workbench/CommandPalette.kt", 12, 2, false)),
            verifications = listOf(VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
            evidence = EvidenceLedger(listOf(EvidenceItem(EvidenceKind.UI_REFERENCE, "codex.png", "ref", state = "图片"))),
        )

        val item = trace.items.first()
        assertEquals("截图实现等待验证", trace.title)
        assertEquals(TraceState.NEEDS_VERIFICATION, item.state)
        assertTrue(item.targetSurface.contains("CommandPalette"))
        assertTrue(item.targetFiles.any { it.endsWith("CommandPalette.kt") })
        assertTrue(item.changedFiles.any { it.endsWith("CommandPalette.kt") })
        assertTrue(item.verification.any { it.contains("通过") })
        assertEquals("ref:abcd1234", item.referenceId)
        assertEquals("ui-references/ref-abcd1234.png", item.assetPath)
    }

    @Test
    fun traceTextShowsTargetsChangesVerificationAndAcceptance() {
        val text = screenshotImplementationTraceText(
            buildScreenshotImplementationTrace(
                board = board(UiReferenceBoardState.READY),
                surfaceMap = surfaceMap(),
                changes = emptyList(),
                verifications = listOf(VerificationEntry(1, "./gradlew assembleProotDebug", VerificationState.PASSED, "BUILD SUCCESSFUL")),
                evidence = EvidenceLedger(emptyList()),
            ),
        )

        assertTrue(text.contains("## 截图实现追踪"))
        assertTrue(text.contains("#### 目标文件"))
        assertTrue(text.contains("#### 已变更"))
        assertTrue(text.contains("#### 验证"))
        assertTrue(text.contains("#### 验收"))
        assertTrue(text.contains("参考ID: `ref:abcd1234`"))
        assertTrue(text.contains("本地资产: `ui-references/ref-abcd1234.png`"))
    }

    private fun board(
        state: UiReferenceBoardState,
        label: String = "Codex screenshot.png · UI 参考",
    ) = UiReferenceBoard(
        title = "board",
        items = listOf(
            UiReferenceBoardItem(
                label = label,
                kind = UiReferenceKind.CODEX_SCREENSHOT,
                state = state,
                detail = "detail",
                evidence = listOf("evidence", "参考ID: ref:abcd1234", "本地资产: ui-references/ref-abcd1234.png"),
                extractionTargets = listOf("布局结构"),
                commands = listOf("/references"),
            ),
        ),
        primaryCommand = "/references",
    )

    private fun surfaceMap() = CodexSurfaceMap(
        title = "surface map",
        referenceCount = 1,
        surfaces = listOf(
            CodexSurfaceSpec("会话流", SurfaceReadiness.READY, "codex", "MessageList / ChatPane", emptyList(), emptyList(), "ok", "/activity"),
            CodexSurfaceSpec("命令面板", SurfaceReadiness.READY, "codex", "SearchOverlay / CommandPalette", emptyList(), emptyList(), "ok", "/help"),
            CodexSurfaceSpec("截图复刻流水线", SurfaceReadiness.PARTIAL, "codex", "UiReferenceBoard / ScreenshotImplementationTrace", emptyList(), emptyList(), "ok", "/trace"),
        ),
        primaryCommand = "/surfaces",
    )
}
