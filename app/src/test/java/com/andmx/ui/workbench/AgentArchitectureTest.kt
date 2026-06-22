package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentArchitectureTest {

    @Test
    fun architectureBlueprintIsReadyWhenExecutionLoopHasEvidence() {
        val blueprint = buildAgentArchitectureBlueprint(
            snapshot = snapshot(apiConfigured = true, toolEvents = 3, uiReferences = 1),
            runtime = runtime(RuntimeEnvironmentLevel.READY),
            evidence = evidence(),
            checklist = checklist(ChecklistState.READY),
            parity = parity(ParityState.READY),
        )

        assertEquals(ArchitectureState.READY, blueprint.state)
        assertEquals("Codex 式执行闭环已成形", blueprint.title)
        assertEquals(0, blueprint.gapCount)
        assertEquals("/report", blueprint.primaryCommand)
    }

    @Test
    fun architectureBlueprintFlagsSetupAndRuntimeGaps() {
        val blueprint = buildAgentArchitectureBlueprint(
            snapshot = snapshot(apiConfigured = false, totalTools = 0),
            runtime = runtime(RuntimeEnvironmentLevel.LIMITED),
            evidence = EvidenceLedger(emptyList()),
            checklist = checklist(ChecklistState.MISSING),
            parity = parity(ParityState.GAP),
        )

        assertEquals(ArchitectureState.GAP, blueprint.state)
        assertTrue(blueprint.gapCount >= 3)
        assertEquals("/plan", blueprint.primaryCommand)
        assertEquals(ArchitectureState.GAP, blueprint.layers.first { it.title == "指令栈与模型配置" }.state)
        assertEquals(ArchitectureState.GAP, blueprint.layers.first { it.title == "执行环境" }.state)
    }

    @Test
    fun architectureTextShowsFlowLayersAndInvariants() {
        val text = agentArchitectureText(
            buildAgentArchitectureBlueprint(
                snapshot = snapshot(apiConfigured = true, changedFiles = 1, uiReferences = 1),
                runtime = runtime(RuntimeEnvironmentLevel.WATCH),
                evidence = evidence(),
                checklist = checklist(ChecklistState.WATCH),
                parity = parity(ParityState.WATCH),
            ),
        )

        assertTrue(text.contains("## 系统架构蓝图"))
        assertTrue(text.contains("### 执行链路"))
        assertTrue(text.contains("### 架构层"))
        assertTrue(text.contains("### 不变量"))
        assertTrue(text.contains("目标与会话状态"))
        assertTrue(text.contains("工具路由与审批"))
        assertTrue(text.contains("验证与交付"))
    }

    private fun snapshot(
        apiConfigured: Boolean,
        toolEvents: Int = 0,
        runningTools: Int = 0,
        pendingApprovals: Int = 0,
        changedFiles: Int = 0,
        sourceLinks: Int = 1,
        uiReferences: Int = 0,
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
        runningTools = runningTools,
        failedTools = 0,
        approvalEvents = pendingApprovals,
        pendingApprovals = pendingApprovals,
        changedFiles = changedFiles,
        sourceLinks = sourceLinks,
        uiReferences = uiReferences,
        tokenEstimate = 2_000,
        contextPressure = "轻量",
        builtInTools = totalTools,
        totalTools = totalTools,
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

    private fun parity(state: ParityState) = CodexParityAudit(
        listOf(
            CodexParityItem("三栏工作台", ParityState.READY, "ready", "/status"),
            CodexParityItem("交付清单", state, "detail", "/checklist"),
        ),
    )
}
