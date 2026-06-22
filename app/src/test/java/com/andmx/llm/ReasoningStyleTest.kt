package com.andmx.llm

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningStyle
import com.andmx.llm.wire.AnthropicMessagesAdapter
import com.andmx.llm.wire.OpenAiChatAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates that reasoning parameters are translated per the model's declared
 * [ReasoningConfig], not by a global guess:
 * - OpenAI EFFORT models emit reasoning_effort only for accepted levels
 * - Anthropic THINKING models emit thinking.budget_tokens from the declaration
 * - NONE-style models (gpt-4o, deepseek-reasoner) emit nothing
 */
class ReasoningStyleTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── OpenAI EFFORT ────────────────────────────────────────────────────────

    private val openaiProvider = ProviderDefinition(
        id = "openai", name = "OpenAI", kind = ProviderKind.OPENAI,
        baseUrl = "https://api.openai.com/v1", apiKey = "k",
        models = mapOf(
            "o3" to ModelDefinition(
                contextWindow = 200_000, maxOutputTokens = 100_000,
                reasoning = ReasoningConfig.OPENAI_EFFORT,
            ),
            "gpt-4o" to ModelDefinition(contextWindow = 128_000, maxOutputTokens = 16_384),
        ),
    )

    @Test
    fun openaiEmitsAcceptedEffortLevel() {
        val req = ChatRequest(model = "o3", messages = listOf(ApiMessage("user", "x")), reasoningEffort = "high")
        val body = json.parseToJsonElement(OpenAiChatAdapter.encodeRequest(req, openaiProvider)).jsonObject
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun openaiEmitsMinimalLevel() {
        val req = ChatRequest(model = "o3", messages = listOf(ApiMessage("user", "x")), reasoningEffort = "minimal")
        val body = json.parseToJsonElement(OpenAiChatAdapter.encodeRequest(req, openaiProvider)).jsonObject
        assertEquals("minimal", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun openaiStripsEffortForUnsupportedModel() {
        // gpt-4o has no reasoning config — must NOT send reasoning_effort.
        val req = ChatRequest(model = "gpt-4o", messages = listOf(ApiMessage("user", "x")), reasoningEffort = "high")
        val body = json.parseToJsonElement(OpenAiChatAdapter.encodeRequest(req, openaiProvider)).jsonObject
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun openaiStripsEffortWhenOff() {
        val req = ChatRequest(model = "o3", messages = listOf(ApiMessage("user", "x")), reasoningEffort = "off")
        val body = json.parseToJsonElement(OpenAiChatAdapter.encodeRequest(req, openaiProvider)).jsonObject
        assertNull(body["reasoning_effort"])
    }

    // ── Anthropic THINKING ──────────────────────────────────────────────────

    private val anthropicProvider = ProviderDefinition(
        id = "anthropic", name = "Anthropic", kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1", apiKey = "k",
        models = mapOf(
            "claude-sonnet-4" to ModelDefinition(
                contextWindow = 200_000, maxOutputTokens = 16_384,
                reasoning = ReasoningConfig.ANTHROPIC_THINKING,
            ),
        ),
    )

    @Test
    fun anthropicEmitsThinkingFromDeclaration() {
        val req = ChatRequest(
            model = "claude-sonnet-4",
            messages = listOf(ApiMessage("user", "x")),
            reasoningEffort = "enabled",
        )
        val body = json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(req, anthropicProvider)).jsonObject
        val thinking = body["thinking"]?.jsonObject
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
        // budget comes from the model declaration (16_000), NOT a hardcoded ladder.
        assertEquals(16_000, thinking?.get("budget_tokens")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun anthropicAcceptsNumericBudgetOverride() {
        val req = ChatRequest(
            model = "claude-sonnet-4",
            messages = listOf(ApiMessage("user", "x")),
            reasoningEffort = "8000", // user-supplied budget overrides the default
        )
        val body = json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(req, anthropicProvider)).jsonObject
        val budget = body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.content?.toInt()
        assertEquals(8_000, budget)
    }

    @Test
    fun anthropicClampsBudgetToMinimum() {
        // Below the 1024 floor → clamped up to 1024.
        val req = ChatRequest(
            model = "claude-sonnet-4",
            messages = listOf(ApiMessage("user", "x")),
            reasoningEffort = "100",
        )
        val body = json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(req, anthropicProvider)).jsonObject
        val budget = body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.content?.toInt()
        assertEquals(1_024, budget)
    }

    @Test
    fun anthropicOmitsThinkingWhenOff() {
        val req = ChatRequest(
            model = "claude-sonnet-4",
            messages = listOf(ApiMessage("user", "x")),
            reasoningEffort = "off",
        )
        val body = json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(req, anthropicProvider)).jsonObject
        assertNull(body["thinking"])
    }

    // ── NONE style (deepseek-reasoner, gpt-4o) ───────────────────────────────

    @Test
    fun noneStyleModelSendsNoReasoning() {
        // deepseek-reasoner is not adjustable — even if effort is set, nothing ships.
        val deepseek = ProviderDefinition(
            id = "deepseek", name = "DeepSeek", kind = ProviderKind.OPENAI,
            baseUrl = "https://api.deepseek.com/v1", apiKey = "k",
            models = mapOf("deepseek-reasoner" to ModelDefinition(contextWindow = 128_000, maxOutputTokens = 32_768)),
        )
        val req = ChatRequest(
            model = "deepseek-reasoner",
            messages = listOf(ApiMessage("user", "x")),
            reasoningEffort = "high",
        )
        val body = json.parseToJsonElement(OpenAiChatAdapter.encodeRequest(req, deepseek)).jsonObject
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun effortConfigContainsRealOpenAILevels() {
        // Sanity: the preset ladder matches OpenAI's real values, including minimal.
        val levels = ReasoningConfig.OPENAI_EFFORT.effortLevels
        assertTrue(levels.contains("minimal"))
        assertTrue(levels.contains("low"))
        assertTrue(levels.contains("medium"))
        assertTrue(levels.contains("high"))
    }
}
