package com.andmx.ui.workbench

import com.andmx.agent.ApprovalMode
import com.andmx.agent.Decision
import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicySummaryTest {

    @Test
    fun policySummaryCountsDecisionsForAskMode() {
        val summary = buildToolPolicySummary(
            mode = ApprovalMode.ASK,
            tools = sampleTools(),
        )

        assertEquals(ApprovalMode.ASK, summary.mode)
        assertEquals(2, summary.autoCount)
        assertEquals(3, summary.promptCount)
        assertEquals(0, summary.denyCount)
        assertEquals(Decision.AUTO, summary.rows.first { it.risk == ToolRisk.READ }.decision)
        assertEquals(Decision.PROMPT, summary.rows.first { it.risk == ToolRisk.WRITE }.decision)
        assertEquals(Decision.PROMPT, summary.rows.first { it.risk == ToolRisk.EXECUTE }.decision)
        assertEquals(Decision.PROMPT, summary.rows.first { it.risk == ToolRisk.NETWORK }.decision)
    }

    @Test
    fun policySummaryCountsFullAndReadOnlyModes() {
        val full = buildToolPolicySummary(ApprovalMode.FULL, sampleTools())
        assertEquals(5, full.autoCount)
        assertEquals(0, full.promptCount)
        assertEquals(0, full.denyCount)

        val readOnly = buildToolPolicySummary(ApprovalMode.READ_ONLY, sampleTools())
        assertEquals(2, readOnly.autoCount)
        assertEquals(0, readOnly.promptCount)
        assertEquals(3, readOnly.denyCount)
    }

    @Test
    fun policyTextExplainsMatrixAndModeCommands() {
        val text = toolPolicyText(buildToolPolicySummary(ApprovalMode.READ_ONLY, sampleTools()))

        assertTrue(text.contains("## 授权策略"))
        assertTrue(text.contains("当前模式: **只读**"))
        assertTrue(text.contains("**自动** · 读取"))
        assertTrue(text.contains("**阻止** · 写入"))
        assertTrue(text.contains("/full"))
        assertTrue(text.contains("/ask"))
        assertTrue(text.contains("/readonly"))
    }

    @Test
    fun policyTextExplainsLiveUiSafetyBoundaries() {
        val summary = buildToolPolicySummary(ApprovalMode.FULL, sampleTools())
        val text = toolPolicyText(summary)

        assertEquals(4, summary.boundaryRows.size)
        assertEquals(1, summary.boundaryAutoCount)
        assertEquals(1, summary.boundaryPromptCount)
        assertEquals(1, summary.boundaryDenyCount)
        assertEquals(1, summary.boundaryHandoffCount)
        assertTrue(text.contains("### Live UI 安全边界"))
        assertTrue(text.contains("这些边界高于 `/full`、`/ask`、`/readonly`"))
        assertTrue(text.contains("**询问** · 会改变外部状态"))
        assertTrue(text.contains("**阻止** · 宿主与受保护窗口"))
        assertTrue(text.contains("**交给用户** · 必须用户接管"))
        assertTrue(text.contains("CAPTCHA"))
    }

    private fun sampleTools(): List<ToolCapability> = listOf(
        ToolCapability("read_file", "读取文件", ToolRisk.READ),
        ToolCapability("list_dir", "列目录", ToolRisk.READ),
        ToolCapability("apply_patch", "应用补丁", ToolRisk.WRITE),
        ToolCapability("run_shell", "运行命令", ToolRisk.EXECUTE),
        ToolCapability("web_search", "搜索网页", ToolRisk.NETWORK),
    )
}
