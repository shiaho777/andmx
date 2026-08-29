package com.andmx.llm.wire

import com.andmx.llm.ChatRequest
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningLevels
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningRulesApplierTest {

    private val provider = ProviderDefinition(id = "p", name = "p", baseUrl = "http://x")

    private fun encodeAnthropic(config: ReasoningConfig?, effort: String?): String {
        val req = ChatRequest(model = "m", messages = emptyList(), reasoningEffort = effort)
        val providerWithModel = provider.copy(
            models = mapOf(
                "m" to com.andmx.llm.provider.ModelDefinition(
                    contextWindow = 200_000,
                    maxOutputTokens = 128_000,
                    reasoning = config,
                ),
            ),
        )
        return AnthropicMessagesAdapter.encodeRequest(req, providerWithModel)
    }

    @Test
    fun anthropicEffortMaxWritesOutputConfig() {
        val config = ReasoningConfig(
            levels = listOf(ReasoningLevels.EFFORT_MAX, ReasoningLevels.EFFORT_HIGH, ReasoningLevels.EFFORT_LOW),
            defaultLevel = "max",
        )
        val body = kotlinx.serialization.json.Json.parseToJsonElement(encodeAnthropic(config, "max")).jsonObject
        assertEquals("max", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(body["thinking"])
    }

    @Test
    fun anthropicThinkingBudgetLevelWritesThinkingBlock() {
        val config = ReasoningConfig(
            levels = listOf(ReasoningLevels.THINKING_MIN_BUDGET),
            defaultLevel = "enabled",
        )
        val body = kotlinx.serialization.json.Json.parseToJsonElement(encodeAnthropic(config, null)).jsonObject
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(1024, thinking["budget_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun anthropicOffSendsNoReasoningFields() {
        val config = ReasoningConfig(
            levels = listOf(ReasoningLevels.EFFORT_MAX, ReasoningLevels.OFF_LEVEL),
            defaultLevel = "max",
        )
        val body = kotlinx.serialization.json.Json.parseToJsonElement(encodeAnthropic(config, "off")).jsonObject
        assertNull(body["output_config"])
        assertNull(body["thinking"])
    }

    @Test
    fun legacyThinkingPathUnchangedWhenNoLevels() {
        val config = ReasoningConfig(
            style = com.andmx.llm.provider.ReasoningStyle.THINKING,
            defaultBudgetTokens = 4096,
        )
        val body = kotlinx.serialization.json.Json.parseToJsonElement(encodeAnthropic(config, "4096")).jsonObject
        assertEquals(4096, body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(body["output_config"])
    }

    @Test
    fun openAiEffortLevelWritesReasoningEffort() {
        val config = ReasoningConfig(
            levels = listOf(ReasoningLevels.EFFORT_HIGH),
            defaultLevel = "high",
        )
        val req = ChatRequest(model = "m", messages = emptyList(), reasoningEffort = "high")
        val providerWithModel = provider.copy(
            models = mapOf(
                "m" to com.andmx.llm.provider.ModelDefinition(
                    contextWindow = 200_000,
                    maxOutputTokens = 64_000,
                    reasoning = config,
                ),
            ),
        )
        val body = kotlinx.serialization.json.Json
            .parseToJsonElement(OpenAiChatAdapter.encodeRequest(req, providerWithModel)).jsonObject
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("output_config"))
    }

    @Test
    fun noConfigMeansUntouchedBody() {
        val body = kotlinx.serialization.json.Json
            .parseToJsonElement(encodeAnthropic(null, "max")).jsonObject
        assertNull(body["output_config"])
    }

    @Test
    fun rulesForOtherKindAreIgnored() {
        val config = ReasoningConfig(levels = listOf(ReasoningLevels.EFFORT_MAX))
        val req = ChatRequest(model = "m", messages = emptyList(), reasoningEffort = "max")
        val providerWithModel = provider.copy(
            models = mapOf(
                "m" to com.andmx.llm.provider.ModelDefinition(
                    contextWindow = 200_000,
                    maxOutputTokens = 64_000,
                    reasoning = config,
                ),
            ),
        )
        val serialized = OpenAiChatAdapter.encodeRequest(req, providerWithModel)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(serialized).jsonObject
        assertNull(body["output_config"])
        assertEquals(ReasoningLevels.EFFORT_MAX.rules.first { it.kind == ProviderKind.OPENAI }.value, "max")
    }
}
