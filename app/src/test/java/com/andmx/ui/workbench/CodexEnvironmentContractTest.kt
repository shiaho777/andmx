package com.andmx.ui.workbench

import com.andmx.agent.ApprovalMode
import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexEnvironmentContractTest {

    @Test
    fun contractIsReadyWhenConfigRuntimePolicyAndEvidenceAreReady() {
        val contract = buildCodexEnvironmentContract(
            instructionSummary = instructionSummary(),
            runtime = runtime(RuntimeEnvironmentLevel.READY),
            policy = buildToolPolicySummary(ApprovalMode.ASK, tools()),
            snapshot = snapshot(apiConfigured = true, contextPressure = "轻量", uiReferences = 1),
            evidence = EvidenceLedger(listOf(EvidenceItem(EvidenceKind.UI_REFERENCE, "codex.png", "ref", state = "图片"))),
        )

        assertEquals("Codex 环境契约已可审计", contract.title)
        assertEquals(0, contract.gapCount)
        assertEquals(0, contract.watchCount)
        assertEquals("/report", contract.primaryCommand)
    }

    @Test
    fun contractFlagsMissingApiLimitedRuntimeAndHighContext() {
        val contract = buildCodexEnvironmentContract(
            instructionSummary = instructionSummary(apiConfigured = false),
            runtime = runtime(RuntimeEnvironmentLevel.LIMITED),
            policy = buildToolPolicySummary(ApprovalMode.READ_ONLY, tools()),
            snapshot = snapshot(apiConfigured = false, contextPressure = "需要压缩"),
            evidence = EvidenceLedger(emptyList()),
        )

        assertEquals("Codex 环境契约仍有缺口", contract.title)
        assertTrue(contract.gapCount >= 3)
        assertEquals("/model", contract.primaryCommand)
        assertEquals(EnvironmentContractState.GAP, contract.layers.first { it.title == "指令优先级" }.state)
        assertEquals(EnvironmentContractState.GAP, contract.layers.first { it.title == "运行沙箱" }.state)
    }

    @Test
    fun contractTextShowsInvariantsAndLayers() {
        val text = codexEnvironmentContractText(
            buildCodexEnvironmentContract(
                instructionSummary = instructionSummary(),
                runtime = runtime(RuntimeEnvironmentLevel.READY),
                policy = buildToolPolicySummary(ApprovalMode.ASK, tools()),
                snapshot = snapshot(apiConfigured = true, contextPressure = "轻量", uiReferences = 1),
                evidence = EvidenceLedger(listOf(EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "ok", state = "通过"))),
            ),
        )

        assertTrue(text.contains("## Codex 环境契约"))
        assertTrue(text.contains("### 不变量"))
        assertTrue(text.contains("### 契约层"))
        assertTrue(text.contains("内部系统提示词"))
        assertTrue(text.contains("工具授权"))
        assertTrue(text.contains("截图/UI 参考进入"))
    }

    private fun instructionSummary(apiConfigured: Boolean = true) = InstructionStackSummary(
        apiStatus = if (apiConfigured) "API Key 已配置" else "API Key 未配置",
        mcpStatus = "MCP 未填写",
        customInstructionStatus = "未设置自定义指令",
        customInstructionPreview = "不会额外附加用户自定义指令。",
        visibleLayers = listOf("应用边界", "用户设置", "工具注册"),
        safetyBoundary = "安全边界",
    )

    private fun runtime(level: RuntimeEnvironmentLevel) = RuntimeEnvironmentSummary(
        level = level,
        healthLabel = when (level) {
            RuntimeEnvironmentLevel.READY -> "Linux 沙箱就绪"
            RuntimeEnvironmentLevel.WATCH -> "等待 rootfs"
            RuntimeEnvironmentLevel.LIMITED -> "轻量执行面"
        },
        healthDetail = "detail",
        executionSurface = "Android/proot Alpine",
        bootstrapStatus = "已捆绑",
        rootfsStatus = "已安装",
        binaryStatus = "proot + loader 就绪",
        abiStatus = "arm64-v8a",
        rootfsPath = "/rootfs",
        usrPath = "/usr",
        tmpPath = "/tmp",
    )

    private fun snapshot(
        apiConfigured: Boolean,
        contextPressure: String,
        uiReferences: Int = 0,
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
        toolEvents = 2,
        runningTools = 0,
        failedTools = 0,
        approvalEvents = 0,
        pendingApprovals = 0,
        changedFiles = 0,
        sourceLinks = 1,
        uiReferences = uiReferences,
        tokenEstimate = 2_000,
        contextPressure = contextPressure,
        builtInTools = tools().size,
        totalTools = tools().size,
        mcpServers = 0,
    )

    private fun tools(): List<ToolCapability> = listOf(
        ToolCapability("read_file", "读取文件", ToolRisk.READ),
        ToolCapability("apply_patch", "应用补丁", ToolRisk.WRITE),
        ToolCapability("run_shell", "运行命令", ToolRisk.EXECUTE),
        ToolCapability("web_search", "搜索网页", ToolRisk.NETWORK),
    )
}
