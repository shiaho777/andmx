package com.andmx.agent.workflow

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ReasoningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelReferenceResolverTest {

    private fun entry(
        providerId: String,
        modelId: String,
        current: Boolean = false,
        disabledReason: String? = null,
        reasoningLevels: List<String> = emptyList(),
        defaultReasoningLevel: String? = null,
    ) = ModelCatalogEntry(
        providerId = providerId,
        modelId = modelId,
        reasoningLevels = reasoningLevels,
        defaultReasoningLevel = defaultReasoningLevel,
        current = current,
        disabledReason = disabledReason,
    )

    private fun failure(text: String, entries: List<ModelCatalogEntry>) =
        ModelReferenceResolver.resolve(text, entries) as ModelReferenceResolution.Failed

    @Test
    fun qualifiedReferenceUsesRegistrySpelling() {
        val result = ModelReferenceResolver.resolve(
            "OpenAI/GPT-5",
            listOf(entry("openai", "gpt-5", current = true)),
        ) as ModelReferenceResolution.Ok

        assertEquals("openai/gpt-5", result.canonical)
    }

    @Test
    fun bareNameStaysInsideTheSessionProvider() {
        val result = failure(
            "claude-x",
            listOf(
                entry("openai", "gpt-5", current = true),
                entry("anthropic", "claude-x"),
            ),
        )

        assertEquals("other_provider", result.reason)
        assertTrue(result.message.contains("anthropic/claude-x"))
        assertTrue(result.message.contains("A bare model name never moves the subagents"))
    }

    @Test
    fun bareNameMatchesTheSessionProviderEntry() {
        val result = ModelReferenceResolver.resolve(
            "GPT-5",
            listOf(
                entry("openai", "gpt-5", current = true),
                entry("proxy", "gpt-5"),
            ),
        ) as ModelReferenceResolution.Ok

        assertEquals("openai/gpt-5", result.canonical)
    }

    @Test
    fun bareNameAcrossProvidersWithoutCurrentIsAmbiguous() {
        val result = failure(
            "gpt-5",
            listOf(entry("openai", "gpt-5"), entry("proxy", "gpt-5")),
        )

        assertEquals("ambiguous", result.reason)
        assertTrue(result.message.contains("openai/gpt-5"))
        assertTrue(result.message.contains("proxy/gpt-5"))
    }

    @Test
    fun disabledEntryIsNeverSilentlyPicked() {
        val result = failure(
            "gpt-5",
            listOf(entry("openai", "gpt-5", current = true, disabledReason = "missing api key")),
        )

        assertEquals("disabled", result.reason)
        assertTrue(result.message.contains("missing api key"))
    }

    @Test
    fun disabledSessionEntryDoesNotFallThroughToAnotherProvider() {
        val result = failure(
            "gpt-5",
            listOf(
                entry("openai", "gpt-5", current = true, disabledReason = "missing api key"),
                entry("proxy", "gpt-5"),
            ),
        )

        assertEquals("other_provider", result.reason)
    }

    @Test
    fun reasoningLevelIsCanonicalisedAndDefaultIsFilledIn() {
        val withLevel = ModelReferenceResolver.resolve(
            "openai/gpt-5\$HIGH",
            listOf(
                entry(
                    "openai",
                    "gpt-5",
                    current = true,
                    reasoningLevels = listOf("low", "high"),
                    defaultReasoningLevel = "low",
                ),
            ),
        ) as ModelReferenceResolution.Ok
        assertEquals("openai/gpt-5\$high", withLevel.canonical)

        val withDefault = ModelReferenceResolver.resolve(
            "gpt-5",
            listOf(
                entry(
                    "openai",
                    "gpt-5",
                    current = true,
                    reasoningLevels = listOf("low", "high"),
                    defaultReasoningLevel = "low",
                ),
            ),
        ) as ModelReferenceResolution.Ok
        assertEquals("openai/gpt-5\$low", withDefault.canonical)
    }

    @Test
    fun modelWithoutLevelsGetsNoSuffix() {
        val result = ModelReferenceResolver.resolve(
            "gpt-4o",
            listOf(entry("openai", "gpt-4o", current = true)),
        ) as ModelReferenceResolution.Ok

        assertEquals("openai/gpt-4o", result.canonical)

        val failure = failure("gpt-4o\$high", listOf(entry("openai", "gpt-4o", current = true)))
        assertEquals("reasoning_level_unknown", failure.reason)
        assertTrue(failure.message.contains("has no reasoning levels"))
    }

    @Test
    fun unknownReasoningLevelListsThatModelsLevels() {
        val result = failure(
            "gpt-5\$ultra",
            listOf(
                entry(
                    "openai",
                    "gpt-5",
                    current = true,
                    reasoningLevels = listOf("low", "high"),
                    defaultReasoningLevel = "low",
                ),
            ),
        )

        assertEquals("reasoning_level_unknown", result.reason)
        assertTrue(result.message.contains("Its levels are: low, high."))
        assertTrue(result.message.contains("Omit the suffix to use low."))
    }

    @Test
    fun notFoundListsAvailableModelsAndCurrentMarker() {
        val result = failure(
            "nope",
            listOf(entry("openai", "gpt-5", current = true), entry("openai", "gpt-4o")),
        )

        assertEquals("not_found", result.reason)
        assertTrue(result.message.contains("openai/gpt-5 [current]"))
        assertTrue(result.message.contains("openai/gpt-4o"))
        assertTrue(result.message.contains("call ListModels"))
    }

    @Test
    fun catalogEntriesMirrorTheListModelsSurface() {
        val providers = listOf(
            ProviderDefinition(
                id = "openai",
                name = "OpenAI",
                baseUrl = "https://example.com",
                apiKey = "",
                enabled = true,
                models = mapOf(
                    "gpt-5" to ModelDefinition(
                        contextWindow = 200_000,
                        reasoning = ReasoningConfig.OPENAI_EFFORT,
                    ),
                ),
            ),
            ProviderDefinition(
                id = "proxy",
                name = "",
                baseUrl = "https://proxy.example.com",
                enabled = false,
                models = mapOf("gpt-5" to ModelDefinition()),
            ),
        )

        val entries = ModelReferenceResolver.catalogEntries(providers, "openai", "gpt-5")

        assertEquals(2, entries.size)
        assertTrue(entries[0].current)
        assertEquals("missing api key", entries[0].disabledReason)
        assertTrue(entries[0].reasoningLevels.contains("high"))
        assertEquals("medium", entries[0].defaultReasoningLevel)
        assertEquals("provider disabled", entries[1].disabledReason)
        assertNull(entries[1].providerLabel)
    }
}
