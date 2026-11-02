package com.andmx.agent

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEngineMemoryFragmentTest {

    private class CapturingLlm : LlmApi {
        val requests = mutableListOf<ChatRequest>()
        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            requests += request
            return Result.success(ApiMessage(role = "assistant", content = "ok"))
        }
    }

    private fun turn() = TurnContext(
        provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
        model = "test-model",
    )

    private fun ChatRequest.systemText(): String =
        messages.filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }

    @Test
    fun memoryFragmentIsReplacedPerTurnInsteadOfAccumulating() = runTest {
        val llm = CapturingLlm()
        val engine = AgentEngine(tools = emptyList(), client = llm)
        val settings = ProviderSettings(model = "test-model")

        engine.setTurnMemoryFragment("# Memory recall\n旧记忆 pnpm")
        engine.runTurn(settings, turn(), "first").toList()

        engine.setTurnMemoryFragment("# Memory recall\n新记忆 gradle")
        engine.runTurn(settings, turn(), "second").toList()

        assertTrue(llm.requests[0].systemText().contains("旧记忆"))
        val second = llm.requests[1].systemText()
        assertTrue(second.contains("新记忆"))
        assertFalse(second.contains("旧记忆"))
        assertEquals(1, Regex("# Memory recall").findAll(second).count())
    }

    @Test
    fun blankFragmentRemovesRecallFromTheSystemPrompt() = runTest {
        val llm = CapturingLlm()
        val engine = AgentEngine(tools = emptyList(), client = llm)
        val settings = ProviderSettings(model = "test-model")

        engine.setTurnMemoryFragment("# Memory recall\n临时记忆")
        engine.runTurn(settings, turn(), "first").toList()
        engine.setTurnMemoryFragment("")
        engine.runTurn(settings, turn(), "second").toList()

        assertTrue(llm.requests[0].systemText().contains("临时记忆"))
        assertFalse(llm.requests[1].systemText().contains("临时记忆"))
    }
}
