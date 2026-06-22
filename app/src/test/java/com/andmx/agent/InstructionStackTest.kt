package com.andmx.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionStackTest {

    @Test
    fun instructionStackShowsVisibleConfigurationAndSafetyBoundary() {
        val text = instructionStackText(
            InstructionStackSnapshot(
                model = "gpt-test",
                baseUrl = "https://api.example.test/v1",
                apiConfigured = true,
                persona = "务实",
                reasoningEffort = "medium",
                approvalModeLabel = "按需",
                customInstructions = "优先读取代码再修改",
                builtInToolCount = 9,
                mcpServerCount = 2,
                mcpConfigured = true,
                environmentContractText = "## Codex 环境契约\n- 状态: **Codex 环境契约已可审计**",
            ),
        )

        assertTrue(text.contains("## 指令栈"))
        assertTrue(text.contains("`gpt-test`"))
        assertTrue(text.contains("API Key: 已配置"))
        assertTrue(text.contains("语气: 务实"))
        assertTrue(text.contains("优先读取代码再修改"))
        assertTrue(text.contains("9 个内置工具, 2 个已连接 MCP 服务器"))
        assertTrue(text.contains("内部系统提示词和运行时安全策略不会作为普通文本暴露"))
        assertTrue(text.contains("## Codex 环境契约"))
    }

    @Test
    fun instructionStackShowsEmptyCustomInstructionsFallback() {
        val text = instructionStackText(
            InstructionStackSnapshot(
                model = "gpt-test",
                baseUrl = "https://api.example.test/v1",
                apiConfigured = false,
                persona = "",
                reasoningEffort = "off",
                approvalModeLabel = "只读",
                customInstructions = "",
                builtInToolCount = 0,
                mcpServerCount = 0,
                mcpConfigured = false,
            ),
        )

        assertTrue(text.contains("API Key: 未配置"))
        assertTrue(text.contains("语气: 默认"))
        assertTrue(text.contains("- 未设置"))
        assertTrue(text.contains("MCP 配置: 未填写"))
    }
}
