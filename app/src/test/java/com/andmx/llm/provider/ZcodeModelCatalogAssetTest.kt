package com.andmx.llm.provider

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ZcodeModelCatalogAssetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rules = ZcodeModelCatalog.load(context)

    @Test
    fun assetCarriesUpstreamRevision() {
        assertEquals(43, rules["revision"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun minimaxM31FlashUsesExactCatalogRule() {
        val match = ZcodeModelCatalog.resolve(rules, "MiniMax-M3.1-Flash", "anthropic-messages", null)
        assertTrue(match.specific)
        assertEquals(1_000_000, match.contextWindow)
        assertEquals(524_288, match.maxOutputTokens)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), match.reasoning?.effortLevels)
        assertEquals(listOf("text", "image", "video"), match.inputModalities)
        assertTrue(match.supportsToolCall)
        assertFalse(match.supportsJsonSchemaOutput)
    }

    @Test
    fun openRouterMiniMaxM31FlashUsesSiteRule() {
        val match = ZcodeModelCatalog.resolve(
            rules,
            "minimax/minimax-m3.1-flash",
            "anthropic-messages",
            "https://openrouter.ai/api/v1",
        )
        assertEquals(1_048_576, match.contextWindow)
        assertEquals(524_288, match.maxOutputTokens)
        assertTrue(match.supportsJsonSchemaOutput)
    }

    @Test
    fun goMiniMaxM31FlashUsesChatBudget() {
        val match = ZcodeModelCatalog.resolve(
            rules,
            "minimax-m3.1-flash",
            "openai-chat-completions",
            "https://opencode.ai/zen/go/v1",
        )
        assertEquals(1_000_000, match.contextWindow)
        assertEquals(131_072, match.maxOutputTokens)
        assertEquals(listOf("max"), match.reasoning?.effortLevels)
        assertFalse(match.supportsJsonSchemaOutput)
    }

    @Test
    fun kimiK31ProResolvesFamilyRule() {
        val match = ZcodeModelCatalog.resolve(rules, "kimi-k3.1-pro", "anthropic-messages", null)
        assertTrue(match.specific)
        assertEquals(1_048_576, match.contextWindow)
        assertEquals(131_072, match.maxOutputTokens)
        assertEquals(listOf("low", "high", "max"), match.reasoning?.effortLevels)
        assertTrue(match.inputModalities.contains("image"))
    }
}
