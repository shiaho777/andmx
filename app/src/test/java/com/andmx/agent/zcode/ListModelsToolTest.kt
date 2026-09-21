package com.andmx.agent.zcode

import com.andmx.agent.multi.SubagentModelCatalog
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ReasoningConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListModelsToolTest {

    private fun provider(
        id: String,
        name: String,
        enabled: Boolean = true,
        apiKey: String = "k",
        models: Map<String, ModelDefinition>,
    ) = ProviderDefinition(
        id = id,
        name = name,
        baseUrl = "https://example.com",
        apiKey = apiKey,
        enabled = enabled,
        models = models,
    )

    @Test
    fun emptyCatalogSaysSo() = runTest {
        val tool = ListModelsTool({ emptyList() }, { "p" to "m" })
        val out = tool.execute(buildJsonObject {}).output
        assertTrue(out.contains("<models count=\"0\">"))
        assertTrue(out.contains("No models are configured"))
    }

    @Test
    fun rowsCarryIdLevelsCurrentDisabled() = runTest {
        val providers = listOf(
            provider(
                id = "openai",
                name = "OpenAI",
                models = mapOf(
                    "gpt-5" to ModelDefinition(
                        contextWindow = 200_000,
                        reasoning = ReasoningConfig.OPENAI_EFFORT,
                    ),
                    "gpt-4o" to ModelDefinition(contextWindow = 128_000),
                ),
            ),
            provider(
                id = "local",
                name = "Local",
                apiKey = "",
                models = mapOf("qwen" to ModelDefinition()),
            ),
        )
        val tool = ListModelsTool({ providers }, { "openai" to "gpt-5" })
        val out = tool.execute(buildJsonObject {}).output
        assertTrue(out.contains("<models count=\"3\">"))
        assertTrue(out.contains("openai::gpt-5 — OpenAI; levels: minimal,low,medium,high (default medium); ctx: 200000 [current]"))
        assertTrue(out.contains("openai::gpt-4o — OpenAI; ctx: 128000"))
        assertTrue(out.contains("local::qwen — Local [disabled: missing api key]"))
    }

    @Test
    fun disabledProviderMarksAllRows() = runTest {
        val providers = listOf(
            provider(
                id = "off",
                name = "Off",
                enabled = false,
                models = mapOf("m1" to ModelDefinition()),
            ),
        )
        val out = ListModelsTool({ providers }, { "" to "" }).execute(buildJsonObject {}).output
        assertTrue(out.contains("[disabled: provider disabled]"))
        assertTrue(!out.contains("[current]"))
    }

    @Test
    fun splitLevelStripsSuffix() {
        assertEquals("a::b" to "high", SubagentModelCatalog.splitLevel("a::b\$high"))
        assertEquals("a::b" to null, SubagentModelCatalog.splitLevel("a::b"))
        assertEquals("a::b" to null, SubagentModelCatalog.splitLevel("a::b\$"))
        assertEquals("inherit" to null, SubagentModelCatalog.splitLevel("inherit"))
    }
}
