package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionStackSummaryTest {

    @Test
    fun summaryShowsConfiguredInstructionLayers() {
        val summary = buildInstructionStackSummary(
            apiConfigured = true,
            mcpConfigured = true,
            customInstructions = "优先读取代码再修改",
            builtInTools = 9,
            mcpServers = 2,
        )

        assertEquals("API Key 已配置", summary.apiStatus)
        assertEquals("2 个 MCP 已连接", summary.mcpStatus)
        assertEquals("已设置自定义指令", summary.customInstructionStatus)
        assertEquals("优先读取代码再修改", summary.customInstructionPreview)
        assertTrue(summary.visibleLayers.any { it.contains("9 个内置工具, 2 个 MCP 服务器") })
        assertTrue(summary.safetyBoundary.contains("不会作为普通文本暴露"))
        assertTrue(summary.contractSummary.contains("/instructions"))
    }

    @Test
    fun summaryShowsFallbacksForMissingConfig() {
        val summary = buildInstructionStackSummary(
            apiConfigured = false,
            mcpConfigured = false,
            customInstructions = "",
            builtInTools = 0,
            mcpServers = 0,
        )

        assertEquals("API Key 未配置", summary.apiStatus)
        assertEquals("MCP 未填写", summary.mcpStatus)
        assertEquals("未设置自定义指令", summary.customInstructionStatus)
        assertEquals("不会额外附加用户自定义指令。", summary.customInstructionPreview)
    }

    @Test
    fun summaryShowsPendingMcpConfigBeforeConnection() {
        val summary = buildInstructionStackSummary(
            apiConfigured = true,
            mcpConfigured = true,
            customInstructions = "",
            builtInTools = 9,
            mcpServers = 0,
        )

        assertEquals("MCP 已填写,等待连接", summary.mcpStatus)
    }
}
