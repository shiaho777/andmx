package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSearchFilterTest {
    private fun fake(n: String): Tool = object : Tool {
        override val name = n
        override val description = "desc-$n"
        override val parameters: JsonObject = buildJsonObject {}
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("")
    }

    private val tools = listOf(fake("Read"), fake("Bash"), fake("Grep"), fake("Glob"), fake("Write"))

    @Test
    fun disabledKeepsSurfaceUnchanged() {
        assertSame(tools, filterEmbeddedSearch(tools, false))
    }

    @Test
    fun enabledRemovesGlobAndGrepOnly() {
        val filtered = filterEmbeddedSearch(tools, true)
        assertEquals(listOf("Read", "Bash", "Write"), filtered.map { it.name })
        assertFalse(filtered.any { it.name == "Glob" || it.name == "Grep" })
    }

    @Test
    fun enabledOnSurfaceWithoutSearchToolsIsNoOp() {
        val noSearch = listOf(fake("Read"), fake("Bash"))
        assertEquals(noSearch.map { it.name }, filterEmbeddedSearch(noSearch, true).map { it.name })
        assertTrue(filterEmbeddedSearch(emptyList(), true).isEmpty())
    }
}