package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPresentationTest {

    private fun tc(
        name: String = "Bash",
        args: String = "{\"command\":\"ls\"}",
        output: String? = null,
        running: Boolean = false,
        error: Boolean = false,
    ) = ToolCall(id = "t", name = name, args = args, output = output, isRunning = running, isError = error)

    @Test
    fun statusMachine() {
        assertEquals(ToolPresentation.Status.PENDING, ToolPresentation.status(tc(args = "", output = null, running = true)))
        assertEquals(ToolPresentation.Status.RUNNING, ToolPresentation.status(tc(running = true)))
        assertEquals(ToolPresentation.Status.COMPLETED, ToolPresentation.status(tc(output = "ok")))
        assertEquals(ToolPresentation.Status.FAILED, ToolPresentation.status(tc(output = "boom", error = true)))
        assertEquals(ToolPresentation.Status.DENIED, ToolPresentation.status(tc(output = "已被用户拒绝执行", error = true)))
        assertEquals(ToolPresentation.Status.STOPPED, ToolPresentation.status(tc(output = "已停止", error = true)))
    }

    @Test
    fun kindLabels() {
        assertEquals("执行中", ToolPresentation.kindLabel(tc(name = "Bash", running = true)))
        assertEquals("已执行", ToolPresentation.kindLabel(tc(name = "Bash")))
        assertEquals("已读取", ToolPresentation.kindLabel(tc(name = "Read")))
        assertEquals("失败", ToolPresentation.kindLabel(tc(error = true)))
        assertEquals("已拒绝", ToolPresentation.kindLabel(tc(output = "已被用户拒绝执行", error = true)))
    }

    @Test
    fun groupingRules() {
        // ZCode changes/execute/read 三分组：只读扇出、写改、命令各自成组。
        for (name in listOf("Read", "ListDir", "Grep", "Glob", "get_goal", "TodoRead")) {
            assertEquals(name, ToolPresentation.GroupKind.READ, ToolPresentation.groupKind(name))
        }
        for (name in listOf("Write", "Edit", "MultiEdit", "apply_patch")) {
            assertEquals(name, ToolPresentation.GroupKind.CHANGE, ToolPresentation.groupKind(name))
        }
        for (name in listOf("Bash", "Git")) {
            assertEquals(name, ToolPresentation.GroupKind.EXECUTE, ToolPresentation.groupKind(name))
        }
        for (name in listOf("WebFetch", "Agent", "TodoWrite", "AskUserQuestion")) {
            assertNull(name, ToolPresentation.groupKind(name))
        }
    }

    @Test
    fun todoItemsParseFromWriteArgs() {
        val args = """{"todos":[{"content":"修 bug","status":"in_progress","priority":"high"},{"content":"写测试","status":"completed"},{"content":"提交","status":"pending"}]}"""
        val items = ToolPresentation.todoItems(tc(name = "TodoWrite", args = args))!!
        assertEquals(3, items.size)
        assertEquals("修 bug", items[0].content)
        assertEquals("in_progress", items[0].status)
        assertEquals("pending", items[2].status)
    }

    @Test
    fun todoItemsRejectsNonTodoOrMalformed() {
        assertNull(ToolPresentation.todoItems(tc(name = "Read", args = """{"todos":[]}""")))
        assertNull(ToolPresentation.todoItems(tc(name = "TodoWrite", args = "not-json")))
        assertNull(ToolPresentation.todoItems(tc(name = "TodoWrite", args = """{"todos":[]}""")))
    }

    @Test
    fun summaryCollapsesWhitespace() {
        val s = ToolPresentation.summary(tc(name = "Bash", args = "{\"command\":\"echo   a\\n\\nb\"}"))
        assertEquals("echo a b", s)
    }

    @Test
    fun summaryIsCappedAt160Chars() {
        val s = ToolPresentation.summary(tc(name = "Bash", args = "{\"command\":\"${"x".repeat(500)}\"}"))
        assertTrue(s.length <= 160)
        assertTrue(s.isNotBlank())
    }

    @Test
    fun secondaryCountsNonBlankOutputLines() {
        assertNull(ToolPresentation.secondary(tc(running = true)))
        assertNull(ToolPresentation.secondary(tc()))
        assertEquals("1 行", ToolPresentation.secondary(tc(output = "one")))
        assertEquals("3 行", ToolPresentation.secondary(tc(output = "a\n\nb\nc")))
    }

    @Test
    fun secondaryShowsFirstLineWhenFailed() {
        val s = ToolPresentation.secondary(tc(output = "E: file not found\nstack line", error = true))
        assertEquals("E: file not found", s)
    }

    @Test
    fun collapsibleDefaults() {
        assertFalse(ToolPresentation.isCollapsible(tc(name = "Read")))
        // ZCode ask-question：等待中单行不展开，完成后可展开回看问答。
        assertFalse(ToolPresentation.isCollapsible(tc(name = "AskUserQuestion", running = true)))
        assertTrue(ToolPresentation.isCollapsible(tc(name = "AskUserQuestion")))
        assertTrue(ToolPresentation.isCollapsible(tc(name = "Bash")))
        assertTrue(ToolPresentation.isCollapsible(tc(name = "Write")))
    }

    @Test
    fun secondaryShowsTodoProgress() {
        val args = """{"todos":[{"content":"a","status":"completed"},{"content":"b","status":"pending"}]}"""
        assertEquals("1/2", ToolPresentation.secondary(tc(name = "TodoWrite", args = args)))
        assertEquals("1/2", ToolPresentation.secondary(tc(name = "TodoWrite", args = args, running = true)))
        assertNull(ToolPresentation.secondary(tc(name = "TodoWrite", args = """{"todos":[]}""")))
    }

    @Test
    fun askNeverAutoExpands() {
        assertFalse(ToolPresentation.defaultExpanded(tc(name = "AskUserQuestion")))
        assertFalse(ToolPresentation.defaultExpanded(tc(name = "AskUserQuestion", running = true)))
        assertFalse(ToolPresentation.defaultExpanded(tc(name = "AskUserQuestion", error = true)))
    }

    @Test
    fun editToolsExpandByDefaultWhenCompleted() {
        assertTrue(ToolPresentation.defaultExpanded(tc(name = "Edit")))
        assertTrue(ToolPresentation.defaultExpanded(tc(name = "ApplyPatch")))
        assertFalse(ToolPresentation.defaultExpanded(tc(name = "Bash")))
        assertTrue(ToolPresentation.defaultExpanded(tc(name = "Bash", running = true)))
        assertTrue(ToolPresentation.defaultExpanded(tc(name = "Bash", error = true)))
    }
}
