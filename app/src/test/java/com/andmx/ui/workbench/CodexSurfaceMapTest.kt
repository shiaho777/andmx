package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSurfaceMapTest {

    @Test
    fun surfaceMapWaitsForScreenshotsBeforeReplicaPipelineCanFinish() {
        val map = buildCodexSurfaceMap(
            references = UiReferenceLedger(emptyList()),
            snapshot = snapshot(uiReferences = 0),
            blueprint = blueprint(BlueprintState.WAITING_REFERENCES),
        )

        assertEquals("等待截图补全 UI 表面", map.title)
        assertTrue(map.waitingCount > 0)
        assertEquals("/references", map.primaryCommand)
        assertEquals(SurfaceReadiness.WAITING_REFERENCE, map.surfaces.first { it.title == "截图复刻流水线" }.readiness)
        assertEquals(SurfaceReadiness.READY, map.surfaces.first { it.title == "线程与项目模式" }.readiness)
    }

    @Test
    fun surfaceMapTracksCodexWorkbenchSurfacesWhenReferencesExist() {
        val map = buildCodexSurfaceMap(
            references = UiReferenceLedger(listOf(UiReferenceItem("codex.png · UI 参考", "来自截图", image = true))),
            snapshot = snapshot(uiReferences = 1, changedFiles = 1),
            blueprint = blueprint(BlueprintState.IMPLEMENTING),
        )

        assertEquals("Codex UI 表面正在复刻", map.title)
        assertEquals(1, map.referenceCount)
        assertTrue(map.readyCount >= 8)
        assertTrue(map.partialCount >= 3)
        assertEquals(0, map.waitingCount)
        assertEquals("/changes", map.primaryCommand)
        assertTrue(map.surfaces.any { it.title == "浏览与桌面操作" })
        assertTrue(map.surfaces.any { it.title == "扩展" })
    }

    @Test
    fun surfaceMapTextListsConcreteSurfacesAndAcceptanceChecks() {
        val text = codexSurfaceMapText(
            buildCodexSurfaceMap(
                references = UiReferenceLedger(listOf(UiReferenceItem("codex.png · UI 参考", "来自截图", image = true))),
                snapshot = snapshot(uiReferences = 1),
                blueprint = blueprint(BlueprintState.READY_TO_EXTRACT),
            ),
        )

        assertTrue(text.contains("## Codex UI 表面地图"))
        assertTrue(text.contains("### 会话流"))
        assertTrue(text.contains("### 线程与项目模式"))
        assertTrue(text.contains("### 输入区"))
        assertTrue(text.contains("### 任务面板"))
        assertTrue(text.contains("### 右侧 Inspector"))
        assertTrue(text.contains("### 工作区面板"))
        assertTrue(text.contains("### Git 评审与交付"))
        assertTrue(text.contains("### 命令面板"))
        assertTrue(text.contains("### 审批与安全"))
        assertTrue(text.contains("### 浏览与桌面操作"))
        assertTrue(text.contains("### 扩展"))
        assertTrue(text.contains("### 截图复刻流水线"))
        assertTrue(text.contains("AndMX 落点"))
        assertTrue(text.contains("验收"))
        assertTrue(text.contains("Local/Worktree/Cloud"))
        assertTrue(text.contains("Computer Use"))
        assertTrue(text.contains("Skills、Plugins、MCP"))
        assertTrue(text.contains("依据"))
    }

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

    private fun blueprint(state: BlueprintState) = UiReplicaBlueprint(
        state = state,
        title = "状态",
        referenceCount = if (state == BlueprintState.WAITING_REFERENCES) 0 else 1,
        extractionTasks = listOf("布局", "控件"),
        targetSurfaces = listOf("ChatPane", "AgentInspectorPane"),
        acceptanceChecks = listOf("compile", "test", "assemble"),
        primaryCommand = "/blueprint",
    )
}
