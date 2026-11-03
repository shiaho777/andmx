package com.andmx.agent.workflow.dynamic

import com.andmx.agent.AgentEngine
import com.andmx.agent.TurnContext
import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicActorHistoryTest {

    private class RecordingLlm(private val reply: String) : LlmApi {
        val requests = mutableListOf<ChatRequest>()

        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            requests += request
            return Result.success(ApiMessage(role = "assistant", content = reply))
        }
    }

    @Test
    fun seededHistoryReachesTheNextEngine() = runTest {
        val turn = TurnContext(
            provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
            model = "test-model",
        )
        val settings = ProviderSettings(model = "test-model")
        val first = AgentEngine(tools = emptyList(), client = RecordingLlm("one"))
        first.runTurn(settings, turn, "first").toList()

        val secondLlm = RecordingLlm("two")
        val second = AgentEngine(tools = emptyList(), client = secondLlm)
        second.seed(DynamicActorHistory.snapshot(first.snapshotHistory()))
        second.runTurn(settings, turn, "second").toList()

        val contents = secondLlm.requests.last().messages.mapNotNull { it.content }
        assertTrue(contents.contains("first"))
        assertTrue(contents.contains("one"))
        assertTrue(contents.contains("second"))
    }

    @Test
    fun snapshotDropsSystemBlocksAndKeepsTurns() {
        val messages = listOf(
            ApiMessage(role = "system", content = "prompt"),
            ApiMessage(role = "user", content = "first"),
            ApiMessage(role = "assistant", content = "one"),
            ApiMessage(role = "user", content = "second"),
            ApiMessage(role = "assistant", content = "two"),
        )

        assertEquals(
            listOf("first", "one", "second", "two"),
            DynamicActorHistory.snapshot(messages).map { it.content },
        )
    }

    @Test
    fun trimStartsAtUserMessage() {
        val messages = buildList {
            add(ApiMessage(role = "user", content = "u0"))
            add(ApiMessage(role = "assistant", content = "a0"))
            add(ApiMessage(role = "user", content = "u1"))
            add(ApiMessage(role = "assistant", content = "a1"))
            add(ApiMessage(role = "user", content = "u2"))
            add(ApiMessage(role = "assistant", content = "a2"))
        }

        val trimmed = DynamicActorHistory.snapshot(messages, limit = 3)

        assertEquals(listOf("u2", "a2"), trimmed.map { it.content })
        assertEquals("user", trimmed.first().role)
    }
}
