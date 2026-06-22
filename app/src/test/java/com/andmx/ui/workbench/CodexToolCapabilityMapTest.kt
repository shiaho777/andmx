package com.andmx.ui.workbench

import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexToolCapabilityMapTest {

    @Test
    fun mapGroupsToolsIntoCodexStyleDomains() {
        val map = buildCodexToolCapabilityMap(sampleTools(), mcpServerCount = 1)

        assertEquals("Codex 工具能力地图已成形", map.title)
        assertEquals(8, map.domainCount)
        assertEquals(0, map.emptyDomainCount)
        assertEquals("/tools", map.primaryCommand)
        assertTrue(map.items.first { it.domain == ToolCapabilityDomain.OBSERVE }.tools.any { it.name == "read_file" })
        assertTrue(map.items.first { it.domain == ToolCapabilityDomain.EDIT }.tools.any { it.name == "apply_patch" })
        assertTrue(map.items.first { it.domain == ToolCapabilityDomain.EXECUTE }.tools.any { it.name == "run_shell" })
        assertTrue(map.items.first { it.domain == ToolCapabilityDomain.BROWSE }.tools.any { it.name == "web_search" })
        assertTrue(map.items.first { it.domain == ToolCapabilityDomain.GUI }.tools.any { it.name == "computer_use" })
    }

    @Test
    fun mapFlagsMissingCoreDomains() {
        val map = buildCodexToolCapabilityMap(
            tools = listOf(ToolCapability("read_file", "读取文件", ToolRisk.READ)),
            mcpServerCount = 0,
        )

        assertEquals("Codex 工具能力地图仍有缺口", map.title)
        assertTrue(map.emptyDomainCount >= 3)
        assertEquals("/changes", map.primaryCommand)
    }

    @Test
    fun textShowsDomainDetailsSafetyAndSummary() {
        val map = buildCodexToolCapabilityMap(sampleTools(), mcpServerCount = 1)
        val text = codexToolCapabilityMapText(map)

        assertTrue(text.contains("## 工具能力地图"))
        assertTrue(text.contains("### 观察 · 观察事实"))
        assertTrue(text.contains("### 编辑 · 修改工作区"))
        assertTrue(text.contains("### 执行 · 运行验证"))
        assertTrue(text.contains("### 浏览与截图 · 浏览与视觉参考"))
        assertTrue(text.contains("### 桌面操作 · 桌面 GUI 操作"))
        assertTrue(text.contains("### 外部能力 · MCP 与插件扩展"))
        assertTrue(text.contains("### 自动化 · 长期自动化"))
        assertTrue(text.contains("受保护窗口使用截图证据替代"))
        assertTrue(text.contains("不能自动化 Codex 自身"))
        assertTrue(text.contains("Skills、Plugins 和 MCP"))
        assertTrue(map.summaryLines().any { it.contains("能力域: 8") })
    }

    private fun sampleTools(): List<ToolCapability> = listOf(
        ToolCapability("read_file", "读取文件", ToolRisk.READ),
        ToolCapability("list_dir", "列目录", ToolRisk.READ),
        ToolCapability("apply_patch", "应用补丁", ToolRisk.WRITE),
        ToolCapability("edit_file", "编辑文件", ToolRisk.WRITE),
        ToolCapability("run_shell", "运行命令", ToolRisk.EXECUTE),
        ToolCapability("git", "Git 操作", ToolRisk.EXECUTE),
        ToolCapability("web_search", "搜索网页", ToolRisk.NETWORK),
        ToolCapability("browse", "浏览网页", ToolRisk.NETWORK),
        ToolCapability("computer_use", "桌面 GUI 操作", ToolRisk.EXECUTE),
        ToolCapability("mcp_memory", "MCP 记忆", ToolRisk.EXECUTE),
    )
}
