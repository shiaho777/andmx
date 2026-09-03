package com.andmx.agent

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCallTraceTest {

    private fun reset() = ModelCallTrace.clear()

    @Test
    fun `record appends and keeps insertion order`() {
        reset()
        val a = ModelCallTrace.record(
            source = ModelCallTrace.Source.MAIN,
            model = "gpt-test",
            inputTokens = 100,
            cachedInputTokens = 20,
            outputTokens = 30,
            finish = ModelCallTrace.Finish.STOP,
            inputPreview = "in",
            outputPreview = "out",
            durationMs = 1200,
            atMs = 1_000L,
        )
        val b = ModelCallTrace.record(
            source = ModelCallTrace.Source.COMPACT,
            model = "gpt-test",
            inputTokens = 10,
            cachedInputTokens = 0,
            outputTokens = 5,
            finish = ModelCallTrace.Finish.TOOL_CALLS,
            inputPreview = "in2",
            outputPreview = "out2",
            durationMs = 300,
            atMs = 2_000L,
        )
        val calls = ModelCallTrace.calls.value
        assertEquals(listOf(a.seq, b.seq), calls.map { it.seq })
        assertEquals(ModelCallTrace.Source.MAIN, calls[0].source)
        assertEquals(ModelCallTrace.Finish.TOOL_CALLS, calls[1].finish)
        assertEquals(130, calls[0].totalTokens)
    }

    @Test
    fun `ring buffer evicts oldest beyond MAX_CALLS`() {
        reset()
        repeat(ModelCallTrace.MAX_CALLS + 10) { i ->
            ModelCallTrace.record(
                source = ModelCallTrace.Source.MAIN,
                model = "m",
                inputTokens = i,
                cachedInputTokens = 0,
                outputTokens = 0,
                finish = ModelCallTrace.Finish.STOP,
                inputPreview = "",
                outputPreview = "",
                durationMs = 0,
                atMs = i.toLong(),
            )
        }
        val calls = ModelCallTrace.calls.value
        assertEquals(ModelCallTrace.MAX_CALLS, calls.size)
        assertEquals(ModelCallTrace.MAX_CALLS + 10 - 1, calls.last().inputTokens)
        assertEquals(10, calls.first().inputTokens)
    }

    @Test
    fun `summary returns count and total tokens`() {
        reset()
        ModelCallTrace.record(
            source = ModelCallTrace.Source.MAIN, model = "m",
            inputTokens = 100, cachedInputTokens = 0, outputTokens = 40,
            finish = ModelCallTrace.Finish.STOP, inputPreview = "", outputPreview = "",
            durationMs = 0, atMs = 0,
        )
        ModelCallTrace.record(
            source = ModelCallTrace.Source.SUBAGENT, model = "m",
            inputTokens = 1, cachedInputTokens = 0, outputTokens = 9,
            finish = ModelCallTrace.Finish.STOP, inputPreview = "", outputPreview = "",
            durationMs = 0, atMs = 0,
        )
        val (count, total) = ModelCallTrace.summary()
        assertEquals(2, count)
        assertEquals(150, total)
    }

    @Test
    fun `previews are truncated to PREVIEW_CHARS`() {
        reset()
        val long = "x".repeat(ModelCallTrace.PREVIEW_CHARS + 500)
        ModelCallTrace.record(
            source = ModelCallTrace.Source.MAIN, model = "m",
            inputTokens = 1, cachedInputTokens = 0, outputTokens = 1,
            finish = ModelCallTrace.Finish.STOP, inputPreview = long, outputPreview = long,
            durationMs = 0, atMs = 0,
        )
        val call = ModelCallTrace.calls.value.single()
        assertEquals(ModelCallTrace.PREVIEW_CHARS, call.inputPreview.length)
        assertEquals(ModelCallTrace.PREVIEW_CHARS, call.outputPreview.length)
    }

    @Test
    fun `clear empties the trace`() {
        reset()
        ModelCallTrace.record(
            source = ModelCallTrace.Source.MAIN, model = "m",
            inputTokens = 1, cachedInputTokens = 0, outputTokens = 1,
            finish = ModelCallTrace.Finish.STOP, inputPreview = "", outputPreview = "",
            durationMs = 0, atMs = 0,
        )
        assertTrue(ModelCallTrace.calls.value.isNotEmpty())
        ModelCallTrace.clear()
        assertTrue(ModelCallTrace.calls.value.isEmpty())
        val (count, _) = ModelCallTrace.summary()
        assertEquals(0, count)
    }

    @Test
    fun `TracedLlm records stream call with usage and finish`() = runTest {
        reset()
        val delegate = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest) =
                Result.failure<com.andmx.llm.ApiMessage>(UnsupportedOperationException())

            override fun chatStream(request: com.andmx.llm.ChatRequest) = flowOf(
                com.andmx.llm.LlmStreamEvent.Content("hello"),
                com.andmx.llm.LlmStreamEvent.UsageUpdate(
                    com.andmx.llm.TokenUsage(
                        inputTokens = 50, cachedInputTokens = 10, outputTokens = 25,
                    ),
                ),
                com.andmx.llm.LlmStreamEvent.Completed(
                    com.andmx.llm.ApiMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            com.andmx.llm.ApiToolCall(
                                id = "c1",
                                function = com.andmx.llm.ApiFunctionCall("read_file", "{}"),
                            ),
                        ),
                    ),
                ),
            )
        }
        val traced = TracedLlm(delegate, ModelCallTrace.Source.MAIN)
        val request = com.andmx.llm.ChatRequest(
            model = "m",
            messages = listOf(
                com.andmx.llm.ApiMessage(role = "system", content = "sys"),
                com.andmx.llm.ApiMessage(role = "user", content = "hi"),
            ),
        )
        val events = traced.chatStream(request).toList()
        assertEquals(3, events.size)
        val call = ModelCallTrace.calls.value.single()
        assertEquals(ModelCallTrace.Source.MAIN, call.source)
        assertEquals("m", call.model)
        assertEquals(50, call.inputTokens)
        assertEquals(10, call.cachedInputTokens)
        assertEquals(25, call.outputTokens)
        assertEquals(ModelCallTrace.Finish.TOOL_CALLS, call.finish)
        assertEquals("read_file {}", call.outputPreview)
        assertTrue(call.inputPreview.contains("hi"))
    }

    @Test
    fun `TracedLlm records non-stream chat call`() = runTest {
        reset()
        val delegate = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest) =
                Result.success(com.andmx.llm.ApiMessage(role = "assistant", content = "answer"))

            override fun chatStream(request: com.andmx.llm.ChatRequest) = throw UnsupportedOperationException()
        }
        val traced = TracedLlm(delegate, ModelCallTrace.Source.COMPACT)
        val request = com.andmx.llm.ChatRequest(
            model = "m",
            messages = listOf(com.andmx.llm.ApiMessage(role = "user", content = "compact this")),
        )
        traced.chat(request)
        val call = ModelCallTrace.calls.value.single()
        assertEquals(ModelCallTrace.Source.COMPACT, call.source)
        assertEquals(ModelCallTrace.Finish.STOP, call.finish)
        assertEquals("answer", call.outputPreview)
        assertTrue(call.inputPreview.contains("compact this"))
    }
}
