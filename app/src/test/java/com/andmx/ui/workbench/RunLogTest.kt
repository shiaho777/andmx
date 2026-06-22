package com.andmx.ui.workbench

import com.andmx.agent.ToolRisk
import com.andmx.ui.conversation.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogTest {

    @Test
    fun runLogSummarizesConversationItemsNewestFirst() {
        val entries = runLogEntries(
            listOf(
                ChatItem.User(1, "请检查项目\n并修复"),
                ChatItem.Assistant(2, "我先读取结构"),
                ChatItem.ToolUse(3, "call-1", "read_file", """{"path":"/root/app.kt"}""", output = "ok", running = false),
                ChatItem.Approval(
                    key = 4,
                    toolName = "run_shell",
                    summary = "command=./gradlew test",
                    risk = ToolRisk.EXECUTE,
                    resolved = false,
                ),
            ),
        )

        assertEquals(listOf(RunLogKind.APPROVAL, RunLogKind.TOOL, RunLogKind.ASSISTANT, RunLogKind.USER), entries.map { it.kind })
        assertEquals(RunLogState.WAITING, entries[0].state)
        assertEquals("授权 · 执行 · run_shell", entries[0].title)
        assertEquals(RunLogState.DONE, entries[1].state)
        assertEquals("工具 · read_file", entries[1].title)
        assertEquals("/root/app.kt", entries[1].detail)
        assertEquals(RunLogTargetKind.FILE, entries[1].targetKind)
        assertEquals("/root/app.kt", entries[1].targetPath)
        assertEquals("请检查项目 并修复", entries[3].detail)
    }

    @Test
    fun runLogMapsToolAndApprovalStates() {
        val entries = runLogEntries(
            listOf(
                ChatItem.ToolUse(1, "call-1", "run_shell", """{"command":"bad"}""", running = false, error = true),
                ChatItem.ToolUse(2, "call-2", "web_search", """{"query":"andmx"}""", running = true),
                ChatItem.Approval(3, "write_file", "path=/root/a", ToolRisk.WRITE, resolved = true, allowed = false),
                ChatItem.Approval(4, "write_file", "path=/root/b", ToolRisk.WRITE, resolved = true, allowed = true),
            ),
        )

        assertEquals(RunLogState.DONE, entries[0].state)
        assertEquals(RunLogState.DENIED, entries[1].state)
        assertEquals(RunLogState.RUNNING, entries[2].state)
        assertEquals("https://duckduckgo.com/?q=andmx", entries[2].targetUrl)
        assertEquals(RunLogTargetKind.WEB, entries[2].targetKind)
        assertEquals(RunLogState.FAILED, entries[3].state)
    }

    @Test
    fun runLogRespectsLimit() {
        val entries = runLogEntries(
            (1L..20L).map { ChatItem.Assistant(it, "msg $it") },
            limit = 5,
        )

        assertEquals(listOf(20L, 19L, 18L, 17L, 16L), entries.map { it.key })
    }

    @Test
    fun activitySummaryIncludesRecentTimelineTargetsAndRelatedCommands() {
        val text = activitySummaryText(
            listOf(
                ChatItem.User(1, "了解这个项目"),
                ChatItem.ToolUse(2, "call-1", "read_file", """{"path":"/root/app.kt"}""", output = "ok", running = false),
                ChatItem.ToolUse(3, "call-2", "web_search", """{"query":"codex ui"}""", running = true),
                ChatItem.Approval(4, "run_shell", "command=./gradlew test", ToolRisk.EXECUTE, resolved = true, allowed = false),
            ),
        )

        assertTrue(text.contains("## 最近活动"))
        assertTrue(text.contains("- 拒绝 · 授权 · 执行 · run_shell: command=./gradlew test"))
        assertTrue(text.contains("- 运行中 · 工具 · web_search: codex ui"))
        assertTrue(text.contains("  - 网页: https://duckduckgo.com/?q=codex+ui"))
        assertTrue(text.contains("- 完成 · 工具 · read_file: /root/app.kt"))
        assertTrue(text.contains("  - 文件: `/root/app.kt`"))
        assertTrue(text.contains("- `/plan` 查看当前计划"))
        assertTrue(text.contains("- `/changes` 查看待审变更"))
        assertTrue(text.contains("- `/verify` 查看验证结果"))
    }

    @Test
    fun activitySummaryShowsFallbackWhenEmpty() {
        val text = activitySummaryText(emptyList())

        assertTrue(text.contains("## 最近活动"))
        assertTrue(text.contains("- 尚无运行记录"))
        assertTrue(text.contains("### 建议"))
    }
}
