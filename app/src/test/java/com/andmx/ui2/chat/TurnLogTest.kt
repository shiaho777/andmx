package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLogTest {
    private fun user(id: Long, content: String) = ChatMessage(id = id, role = "user", content = content, sortKey = id)
    private fun assistant(id: Long, content: String, isProcess: Boolean = false) =
        ChatMessage(id = id, role = "assistant", content = content, sortKey = id, isProcess = isProcess, createdAt = id)
    private fun tool(id: String, name: String, args: String, output: String?, running: Boolean = false, error: Boolean = false, sortKey: Long = 0L) =
        ToolCall(id = id, name = name, args = args, output = output, isRunning = running, isError = error, sortKey = sortKey)

    @Test
    fun returnsEmptyWhenTheTargetMessageIsMissing() {
        assertEquals("", turnLog(9, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun aTurnWithOnlyItsUserMessageRendersJustThatUserBlock() {
        assertEquals(
            "## 用户\nonly\n",
            turnLog(1, listOf(user(1, "only")), emptyList(), emptyList(), emptyList(), emptyList()),
        )
    }

    @Test
    fun boundsTheTurnAtTheOwningUserMessageAndOrdersBySortKeyThenArrival() {
        val messages = listOf(
            user(10, "first question"),
            assistant(20, "intermediate", isProcess = true),
            user(30, "second question"),
            assistant(50, "final"),
        )
        val reasonings = listOf(ReasoningItem(id = "t1", content = "thinking", sortKey = 40))
        val tools = listOf(
            tool("t-a", "Read", "path", "body", running = true, sortKey = 35),
            tool("t-b", "Grep", "", null, running = false, error = true, sortKey = 42),
        )
        val log = turnLog(
            messageId = 50,
            messages = messages,
            reasonings = reasonings,
            tools = tools,
            approvals = listOf(ApprovalItem(id = "ap", toolName = "Edit", summary = "file", modeLabel = "ask", sortKey = 45)),
            subAgents = listOf(SubAgentItem(id = "s1", task = "task", state = "FAILED", result = "boom", sortKey = 48)),
        )
        assertTrue(log.startsWith("## 用户\nsecond question\n"))
        assertTrue(log.contains("## 工具 · Read · 运行中\n参数:\npath\n输出:\nbody"))
        assertTrue(log.contains("## 工具 · Grep · 失败\n(无输出)"))
        assertTrue(log.contains("## 思考\nthinking"))
        assertTrue(log.contains("## 审批 · Edit · pending\nfile"))
        assertTrue(log.contains("## 子代理 · FAILED\n任务: task\n结果:\nboom"))
        assertTrue(log.endsWith("## 助手\nfinal\n"))
        assertEquals(-1, log.indexOf("## 用户\nfirst question"))
        assertEquals(-1, log.indexOf("## 过程"))
        assertTrue(log.indexOf("## 工具") < log.indexOf("## 思考"))
        assertTrue(log.indexOf("## 思考") < log.indexOf("## 审批"))
    }

    @Test
    fun aZeroSortKeyToolWithAnIdIsKeptInsideItsTurn() {
        val log = turnLog(
            messageId = 5,
            messages = listOf(user(5, "q"), assistant(6, "a")),
            reasonings = emptyList(),
            tools = listOf(tool("t", "Bash", "ls", "out")),
            approvals = emptyList(),
            subAgents = emptyList(),
        )
        assertTrue(log.contains("## 工具 · Bash · 完成\n参数:\nls\n输出:\nout"))
        assertEquals("## 用户\nq\n\n## 工具 · Bash · 完成\n参数:\nls\n输出:\nout\n", log)
    }
}
