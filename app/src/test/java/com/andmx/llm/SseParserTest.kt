package com.andmx.llm

import com.andmx.llm.wire.OpenAiChatAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Validates SSE delta assembly — especially tool-call arguments that arrive
 * split across multiple chunks, which is the easy thing to get wrong.
 *
 * The assembly logic now lives in [OpenAiChatAdapter]; these tests drive it
 * directly with the same OpenAI delta format the adapter parses.
 */
class SseParserTest {

    private val adapter = OpenAiChatAdapter

    @Test
    fun assemblesContentDeltas() = runTest {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            """data: {"choices":[{"delta":{"content":" 世界"}}]}""",
            "data: [DONE]",
        )
        val collected = StringBuilder()
        val msg = adapter.parseStream(lines) { collected.append(it) }

        assertEquals("Hello 世界", collected.toString())
        assertEquals("Hello 世界", msg.content)
        assertNull(msg.toolCalls)
    }

    @Test
    fun assemblesToolCallAcrossChunks() = runTest {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"run_shell","arguments":"{\"comm"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"and\":\"ls"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":" -la\"}"}}]}}]}""",
            "data: [DONE]",
        )
        val msg = adapter.parseStream(lines) {}

        val calls = msg.toolCalls
        assertEquals(1, calls?.size)
        assertEquals("call_1", calls!!.first().id)
        assertEquals("run_shell", calls.first().function.name)
        assertEquals("""{"command":"ls -la"}""", calls.first().function.arguments)
    }
}
