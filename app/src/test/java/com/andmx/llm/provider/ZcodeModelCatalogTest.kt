package com.andmx.llm.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZcodeModelCatalogTest {
    private val json = Json.parseToJsonElement(
        """
        {
          "modelRules": [
            {"modelMatch": ".*", "config": {"properties": {"contextWindow": 200000, "supportsToolCall": true, "inputFormat": {"supportsText": true, "supportsImage": false}}, "optionSpecs": {"maxOutputTokens": {"max": 32000}}}},
            {"modelMatch": ".*glm-5\\.3.*", "config": {"properties": {"contextWindow": 1000000, "inputFormat": {"supportsImage": false}}, "optionSpecs": {"maxOutputTokens": {"max": 128000}, "reasoningLevel": {"values": ["low", "high", "max"]}}}}
          ],
          "modelApiRules": [],
          "providerSiteRules": []
        }
        """.trimIndent(),
    ).let { it as kotlinx.serialization.json.JsonObject }

    @Test
    fun specificGlmRuleOverridesDefaultWindowAndOutput() {
        val match = ZcodeModelCatalog.resolve(json, "GLM-5.3", "anthropic-messages", null)
        assertTrue(match.specific)
        assertEquals(1_000_000, match.contextWindow)
        assertEquals(128_000, match.maxOutputTokens)
        assertEquals(listOf("low", "high", "max"), match.reasoning?.effortLevels)
        assertFalse(match.inputModalities.contains("image"))
    }

    @Test
    fun unknownIdKeepsCatchAllDefaults() {
        val match = ZcodeModelCatalog.resolve(json, "my-custom-model", null, null)
        assertFalse(match.specific)
        assertEquals(200_000, match.contextWindow)
        assertEquals(32_000, match.maxOutputTokens)
        assertTrue(match.supportsToolCall)
    }
}
