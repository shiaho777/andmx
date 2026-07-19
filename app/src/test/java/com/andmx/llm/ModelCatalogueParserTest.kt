package com.andmx.llm

import com.andmx.llm.wire.OpenAiChatAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogueParserTest {
    @Test
    fun parsesOpenAiEnvelope() {
        val body = """{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"}]}"""
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), OpenAiChatAdapter.parseModelCatalogue(body))
    }

    @Test
    fun parsesOllamaModelsEnvelope() {
        val body = """{"models":[{"name":"llama3:8b"},{"name":"qwen2.5:7b"}]}"""
        assertEquals(listOf("llama3:8b", "qwen2.5:7b"), OpenAiChatAdapter.parseModelCatalogue(body))
    }

    @Test
    fun filtersAndSorts() {
        val body = """{"data":[{"id":"z-model"},{"id":"a-model"},{"id":"a-model"}]}"""
        assertEquals(listOf("a-model", "z-model"), OpenAiChatAdapter.parseModelCatalogue(body))
    }

    @Test
    fun emptyOnGarbage() {
        assertTrue(OpenAiChatAdapter.parseModelCatalogue("not-json").isEmpty())
    }
}
