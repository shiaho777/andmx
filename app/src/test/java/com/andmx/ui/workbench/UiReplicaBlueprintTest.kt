package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiReplicaBlueprintTest {

    @Test
    fun blueprintWaitsForReferencesWhenNoScreenshotsExist() {
        val blueprint = buildUiReplicaBlueprint(
            references = UiReferenceLedger(emptyList()),
            snapshot = snapshot(),
            evidence = EvidenceLedger(emptyList()),
        )

        assertEquals(BlueprintState.WAITING_REFERENCES, blueprint.state)
        assertEquals("/references", blueprint.primaryCommand)
        assertEquals(0, blueprint.referenceCount)

        val text = uiReplicaBlueprintText(blueprint)
        assertTrue(text.contains("## UI 复刻蓝图"))
        assertTrue(text.contains("等待参考"))
        assertTrue(text.contains("### 提取任务"))
        assertTrue(text.contains("布局"))
    }

    @Test
    fun blueprintMovesFromExtractionToVerification() {
        val references = UiReferenceLedger(
            listOf(UiReferenceItem("Codex screenshot.png · UI 参考", "来自用户图片/截图输入", image = true)),
        )

        val ready = buildUiReplicaBlueprint(
            references = references,
            snapshot = snapshot(uiReferences = 1),
            evidence = EvidenceLedger(emptyList()),
        )
        assertEquals(BlueprintState.READY_TO_EXTRACT, ready.state)
        assertEquals("/blueprint", ready.primaryCommand)

        val implementing = buildUiReplicaBlueprint(
            references = references,
            snapshot = snapshot(uiReferences = 1, changedFiles = 2),
            evidence = EvidenceLedger(emptyList()),
        )
        assertEquals(BlueprintState.IMPLEMENTING, implementing.state)
        assertEquals("/changes", implementing.primaryCommand)

        val verifying = buildUiReplicaBlueprint(
            references = references,
            snapshot = snapshot(uiReferences = 1, changedFiles = 2),
            evidence = EvidenceLedger(
                listOf(EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过")),
            ),
        )
        assertEquals(BlueprintState.VERIFYING, verifying.state)
        assertEquals("/verify", verifying.primaryCommand)
        assertTrue(uiReplicaBlueprintText(verifying).contains("### 验收"))
    }

    private fun snapshot(
        uiReferences: Int = 0,
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
        toolEvents = 0,
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
