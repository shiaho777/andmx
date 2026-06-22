package com.andmx.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMethodologyTest {

    @Test
    fun methodologyIncludesCodexStyleOperatingSections() {
        val text = agentMethodologyText(
            AgentMethodologyContext(
                project = "openclaw-main",
                model = "gpt-test",
                approvalModeLabel = "按需",
                builtInToolCount = 9,
                mcpServerCount = 2,
                goalText = "复刻 Codex 工作台",
                goalPhaseLabel = "运行中",
                contextPressure = "轻量",
                tokenEstimate = 2048,
                messageCount = 12,
                toolEvents = 4,
                changedFiles = 2,
                uiReferences = 3,
                evidenceCount = 6,
                verificationPassed = 1,
                checklistWatch = 2,
                nextActionTitle = "提取界面模式",
                nextActionCommand = "/references",
                runtimeSurface = "Android/proot Alpine · proot",
                runtimeHealth = "Linux 沙箱就绪",
                instructionLayers = listOf("系统", "开发者", "用户"),
            ),
        )

        assertTrue(text.contains("## 工作方法"))
        assertTrue(text.contains("### 当前线程"))
        assertTrue(text.contains("### 执行循环"))
        assertTrue(text.contains("### 环境边界"))
        assertTrue(text.contains("### 工具策略"))
        assertTrue(text.contains("### 交互习惯"))
        assertTrue(text.contains("`openclaw-main`"))
        assertTrue(text.contains("`gpt-test`"))
        assertTrue(text.contains("**按需**"))
        assertTrue(text.contains("9 个内置工具, 2 个 MCP 服务器"))
        assertTrue(text.contains("复刻 Codex 工作台"))
        assertTrue(text.contains("提取界面模式 (`/references`)"))
        assertTrue(text.contains("UI 参考 3 · 变更 2 · 证据 6"))
        assertTrue(text.contains("Android/proot Alpine · proot · Linux 沙箱就绪"))
        assertTrue(text.contains("可见指令层: 系统 / 开发者 / 用户"))
        assertTrue(text.contains("`/tools` 将观察、编辑、执行"))
        assertTrue(text.contains("`/self-model` 看指令"))
        assertTrue(text.contains("`/improve` 看自我完善队列"))
        assertTrue(text.contains("`/full`、`/ask`、`/readonly`"))
    }
}
