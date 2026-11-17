package com.andmx.agent.plugins

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDiscoveryToolsTest {

    private val pdf = PluginMarketplace.CatalogPlugin(
        name = "pdf-tools",
        description = "PDF document workflows",
        marketplace = "andmx-plugins-official",
        version = "1.0.0",
        source = PluginMarketplace.CatalogSource(type = "builtin", id = "pdf-tools"),
    )
    private val image = PluginMarketplace.CatalogPlugin(
        name = "image-tools",
        description = "Image editing utilities",
        marketplace = "andmx-plugins-official",
    )
    private val catalog = listOf(pdf, image)

    private fun searchTool(
        installed: Set<String> = emptySet(),
        enabled: () -> Boolean = { false },
    ) = SearchPluginsTool(
        loadCatalog = { catalog },
        installedNames = { installed },
        autoRecommendEnabled = enabled,
    )

    private fun suggestTool(installed: Set<String> = emptySet()) = SuggestPluginInstallTool(
        loadCatalog = { catalog },
        installedNames = { installed },
    )

    @Test
    fun searchReturnsRankedStructure() = runBlocking {
        val res = searchTool(installed = setOf("PDF-Tools")).execute(
            buildJsonObject {
                put("query", "pdf document")
                put("count", "5")
            },
        )
        assertFalse(res.isError)
        val obj = Json.parseToJsonElement(res.output).jsonObject
        assertEquals("pdf document", obj["query"]!!.jsonPrimitive.content)
        assertEquals(2, obj["total"]!!.jsonPrimitive.int)
        assertFalse(obj["autoRecommend"]!!.jsonPrimitive.boolean)
        val results = obj["results"]!!.jsonArray
        assertEquals(1, results.size)
        val first = results[0].jsonObject
        assertEquals("pdf-tools", first["name"]!!.jsonPrimitive.content)
        assertEquals("PDF document workflows", first["description"]!!.jsonPrimitive.content)
        assertTrue(first["score"]!!.jsonPrimitive.double > 0.0)
        assertTrue(first["installed"]!!.jsonPrimitive.boolean)
        assertEquals("andmx-plugins-official", first["marketplace"]!!.jsonPrimitive.content)
    }

    @Test
    fun searchMarksUninstalledPlugins() = runBlocking {
        val res = searchTool().execute(buildJsonObject { put("query", "pdf") })
        val first = Json.parseToJsonElement(res.output).jsonObject["results"]!!.jsonArray[0].jsonObject
        assertFalse(first["installed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun searchHonorsCount() = runBlocking {
        val res = searchTool().execute(
            buildJsonObject {
                put("query", "tools")
                put("count", "1")
            },
        )
        assertEquals(1, Json.parseToJsonElement(res.output).jsonObject["results"]!!.jsonArray.size)
    }

    @Test
    fun searchBlankQueryIsError() = runBlocking {
        val res = searchTool().execute(buildJsonObject { put("query", "  ") })
        assertTrue(res.isError)
    }

    @Test
    fun searchEmptyQueryMatchesNothing() = runBlocking {
        val res = searchTool().execute(buildJsonObject { put("query", "zzz-not-there") })
        assertFalse(res.isError)
        assertEquals(0, Json.parseToJsonElement(res.output).jsonObject["results"]!!.jsonArray.size)
    }

    @Test
    fun autoRecommendFlagIsInjected() = runBlocking {
        val res = searchTool(enabled = { true }).execute(buildJsonObject { put("query", "pdf") })
        assertTrue(Json.parseToJsonElement(res.output).jsonObject["autoRecommend"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun autoRecommendDefaultsToOff() = runBlocking {
        val tool = SearchPluginsTool(loadCatalog = { catalog }, installedNames = { emptySet() })
        val res = tool.execute(buildJsonObject { put("query", "pdf") })
        assertFalse(Json.parseToJsonElement(res.output).jsonObject["autoRecommend"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun suggestReturnsCardWithoutInstalling() = runBlocking {
        var installedCalls = 0
        val tool = SuggestPluginInstallTool(
            loadCatalog = { catalog },
            installedNames = {
                installedCalls++
                emptySet()
            },
        )
        val res = tool.execute(buildJsonObject { put("pluginName", "pdf-tools") })
        assertFalse(res.isError)
        val obj = Json.parseToJsonElement(res.output).jsonObject
        assertEquals("plugin_install_suggestion", obj["type"]!!.jsonPrimitive.content)
        assertEquals("pdf-tools", obj["name"]!!.jsonPrimitive.content)
        assertEquals("PDF document workflows", obj["description"]!!.jsonPrimitive.content)
        assertEquals("builtin", obj["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("/plugin install pdf-tools@andmx-plugins-official", obj["installCommand"]!!.jsonPrimitive.content)
        assertEquals("PluginSystem.installFromMarketplace", obj["installPath"]!!.jsonPrimitive.content)
        assertTrue(obj["requiresUserConfirmation"]!!.jsonPrimitive.boolean)
        assertFalse(obj["installed"]!!.jsonPrimitive.boolean)
        assertEquals(1, installedCalls)
    }

    @Test
    fun suggestIsCaseInsensitive() = runBlocking {
        val res = suggestTool().execute(buildJsonObject { put("pluginName", "PDF-TOOLS") })
        assertFalse(res.isError)
        assertEquals("pdf-tools", Json.parseToJsonElement(res.output).jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun suggestAlreadyInstalledIsFlagged() = runBlocking {
        val res = suggestTool(installed = setOf("pdf-tools")).execute(buildJsonObject { put("pluginName", "pdf-tools") })
        val obj = Json.parseToJsonElement(res.output).jsonObject
        assertTrue(obj["installed"]!!.jsonPrimitive.boolean)
        assertTrue(obj["note"]!!.jsonPrimitive.content.contains("已安装"))
    }

    @Test
    fun suggestUnknownPluginIsError() = runBlocking {
        val res = suggestTool().execute(buildJsonObject { put("pluginName", "nope") })
        assertTrue(res.isError)
        assertTrue(res.output.contains("nope"))
    }

    @Test
    fun suggestBlankNameIsError() = runBlocking {
        val res = suggestTool().execute(buildJsonObject { put("pluginName", "") })
        assertTrue(res.isError)
    }
}