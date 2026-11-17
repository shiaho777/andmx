package com.andmx.agent.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSearchTest {

    private fun plugin(name: String, description: String = "", category: String = "uncategorized") =
        PluginMarketplace.CatalogPlugin(name = name, description = description, category = category)

    @Test
    fun englishTokenizationSplitsWordsAndLowercases() {
        assertEquals(
            listOf("hello", "world", "foo", "bar", "42"),
            PluginSearch.tokenize("Hello, World! foo_bar-42"),
        )
    }

    @Test
    fun chineseTokenizationUsesBigrams() {
        assertEquals(
            listOf("插件", "件市", "市场"),
            PluginSearch.tokenize("插件市场"),
        )
    }

    @Test
    fun singleChineseCharIsKept() {
        assertEquals(listOf("装"), PluginSearch.tokenize("装"))
    }

    @Test
    fun mixedChineseEnglishTokenization() {
        assertEquals(
            listOf("ai", "插件", "search"),
            PluginSearch.tokenize("AI插件 search"),
        )
    }

    @Test
    fun emptyQueryReturnsNoResults() {
        val docs = listOf(PluginSearch.Document("a", "cat dog"))
        assertTrue(PluginSearch.rank("", docs).isEmpty())
        assertTrue(PluginSearch.rank("   ", docs).isEmpty())
        assertTrue(PluginSearch.rank("!!!", docs).isEmpty())
    }

    @Test
    fun emptyDocumentsReturnNoResults() {
        assertTrue(PluginSearch.rank("cat", emptyList()).isEmpty())
    }

    @Test
    fun higherTermFrequencyRanksHigherAtEqualLength() {
        val docs = listOf(
            PluginSearch.Document("low", "cat dog dog dog"),
            PluginSearch.Document("high", "cat cat cat dog"),
        )
        val ranked = PluginSearch.rank("cat", docs)
        assertEquals("high", ranked.first().id)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun shorterDocumentRanksHigherViaLengthNormalization() {
        val filler = (1..30).joinToString(" ") { "word$it" }
        val docs = listOf(
            PluginSearch.Document("long", "cat $filler"),
            PluginSearch.Document("short", "cat"),
        )
        val ranked = PluginSearch.rank("cat", docs)
        assertEquals("short", ranked.first().id)
    }

    @Test
    fun rareTermOutweighsCommonTermViaIdf() {
        val docs = listOf(
            PluginSearch.Document("d1", "alpha common"),
            PluginSearch.Document("d2", "beta common"),
            PluginSearch.Document("d3", "gamma common"),
        )
        val ranked = PluginSearch.rank("alpha common", docs)
        assertEquals("d1", ranked.first().id)
    }

    @Test
    fun unmatchedQueryReturnsNoResults() {
        val docs = listOf(PluginSearch.Document("a", "cat dog"))
        assertTrue(PluginSearch.rank("zebra", docs).isEmpty())
    }

    @Test
    fun chineseQueryMatchesChineseDescription() {
        val plugins = listOf(
            plugin("pdf-tools", "处理 PDF 文档的工具"),
            plugin("image-tools", "处理图片的工具"),
        )
        val ranked = PluginSearch.searchCatalog("文档", plugins)
        assertEquals("pdf-tools", ranked.first().entry.name)
        assertTrue(ranked.first().score > 0.0)
    }

    @Test
    fun searchCatalogRespectsCountAndClamps() {
        val plugins = (1..60).map { plugin("plugin$it", "search keyword") }
        assertEquals(3, PluginSearch.searchCatalog("keyword", plugins, count = 3).size)
        assertEquals(1, PluginSearch.searchCatalog("keyword", plugins, count = 0).size)
        assertEquals(PluginSearch.MAX_COUNT, PluginSearch.searchCatalog("keyword", plugins, count = 999).size)
    }

    @Test
    fun searchCatalogEmptyInputs() {
        assertTrue(PluginSearch.searchCatalog("x", emptyList()).isEmpty())
        assertTrue(PluginSearch.searchCatalog("", listOf(plugin("a", "b"))).isEmpty())
    }

    @Test
    fun nameMatchBoostsRanking() {
        val plugins = listOf(
            plugin("unrelated", "mentions pdf once in description"),
            plugin("pdf", "document utilities"),
        )
        val ranked = PluginSearch.searchCatalog("pdf", plugins)
        assertEquals("pdf", ranked.first().entry.name)
    }
}