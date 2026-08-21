package com.andmx.agent

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.LlmStreamEvent
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AgentEngineCancellationTest {

    private class CancellingLlm : LlmApi {
        override suspend fun chat(request: ChatRequest): Result<ApiMessage> =
            Result.success(ApiMessage(role = "assistant", content = "unused"))

        override fun chatStream(request: ChatRequest): Flow<LlmStreamEvent> =
            flow { throw CancellationException("user stopped") }
    }

    @Test
    fun cancellationPropagatesInsteadOfBeingRetried() = runTest {
        val engine = AgentEngine(tools = emptyList(), client = CancellingLlm())
        val turn = TurnContext(
            provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
            model = "test-model",
        )

        try {
            engine.runTurn(ProviderSettings(model = "test-model"), turn, "hi").toList()
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals("user stopped", e.message)
        }
    }
}
