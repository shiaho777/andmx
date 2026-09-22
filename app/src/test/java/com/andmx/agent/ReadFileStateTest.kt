package com.andmx.agent

import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadFileStateTest {

    private fun readCall(id: String, path: String) = ApiMessage(
        role = "assistant",
        toolCalls = listOf(
            ApiToolCall(id, "function", ApiFunctionCall("read_file", """{"path":"$path"}""")),
        ),
    )

    @Test
    fun recordedFileReplaysWithLineNumbers() {
        val state = ReadFileState()
        state.record("/w/a.kt", "l1\nl2")
        val out = state.postCompactReminders()
        assertEquals(1, out.size)
        assertTrue(out[0].contains("<system-reminder>"))
        assertTrue(out[0].contains("file_path\":\"/w/a.kt"))
        assertTrue(out[0].contains("1\tl1\n2\tl2"))
    }

    @Test
    fun oversizedFileDegradesToReferenceNote() {
        val state = ReadFileState()
        state.record("/w/big.kt", "x".repeat(60_000))
        val out = state.postCompactReminders()
        assertEquals(1, out.size)
        assertTrue(out[0].contains("too large to include"))
        assertTrue(!out[0].contains("Result of calling"))
    }

    @Test
    fun preservedAndGitPathsSkipped() {
        val state = ReadFileState()
        state.record("/w/a.kt", "a")
        state.record("/w/.git/config", "g")
        val out = state.postCompactReminders(preservedPaths = setOf("/w/a.kt"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun writeStateNotReplayed() {
        val state = ReadFileState()
        state.record("/w/a.kt", "new", sourceTool = ReadFileState.WRITE_TOOL)
        assertTrue(state.postCompactReminders().isEmpty())
    }

    @Test
    fun maxFilesCapsReplay() {
        val state = ReadFileState()
        repeat(8) { state.record("/w/f$it.kt", "c$it") }
        assertEquals(ReadFileState.MAX_REPLAY_FILES, state.postCompactReminders().size)
    }

    @Test
    fun hydrateRebuildsFromToolCalls() {
        val state = ReadFileState()
        val messages = listOf(
            readCall("c1", "/w/a.kt"),
            ApiMessage(role = "tool", content = "contents of a", toolCallId = "c1", name = "read_file"),
            readCall("c2", "/w/b.kt"),
            ApiMessage(role = "tool", content = "contents of b", toolCallId = "c2", name = "read_file"),
        )
        state.hydrate(messages)
        val entries = state.entries()
        assertEquals(2, entries.size)
        assertEquals("contents of a", entries[0].content)
        assertEquals(1, state.postCompactReminders(preservedPaths = setOf("/w/a.kt")).size)
    }
}
