package com.andmx.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchTest {

    private class FakeTool(
        override val name: String,
        override val description: String = "",
        override val shouldDefer: Boolean = false,
    ) : Tool {
        override val parameters: JsonObject = buildJsonObject { put("type", "object") }
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult(name)
    }

    private class Fixture(var hidden: Set<String> = emptySet()) {
        val registry = ToolSearchRegistry()
        val read = FakeTool("Read", "read a file")
        val todo = FakeTool("TodoWrite", "update the todo list", shouldDefer = true)
        val cron = FakeTool("CronCreate", "schedule a recurring task", shouldDefer = true)
        val tools: List<Tool> = listOf(
            read,
            todo,
            cron,
            ToolSearchTool(registry),
            DeferredToolCallTool(registry),
        )

        init {
            registry.attach({ tools }, { hidden })
        }
    }

    @Test
    fun exposedKeepsResidentToolsAndControlsButHidesDeferredSchemas() {
        val f = Fixture()
        val names = f.registry.exposed(f.tools, emptySet()).map { it.name }

        assertTrue("Read" in names)
        assertTrue(ToolSearchRegistry.TOOL_SEARCH_NAME in names)
        assertTrue(ToolSearchRegistry.DEFERRED_TOOL_CALL_NAME in names)
        assertFalse("TodoWrite" in names)
        assertFalse("CronCreate" in names)
    }

    @Test
    fun withoutDeferredToolsControlsDisappearAndResidentToolsRemain() {
        val registry = ToolSearchRegistry()
        val read = FakeTool("Read")
        val tools = listOf(read, ToolSearchTool(registry), DeferredToolCallTool(registry))
        registry.attach({ tools }, { emptySet() })

        assertEquals(listOf("Read"), registry.exposed(tools, emptySet()).map { it.name })
    }

    @Test
    fun announcementListsOnlyDeferredNames() {
        val f = Fixture()
        val note = f.registry.announcement(f.tools, emptySet()).orEmpty()

        assertTrue(note.contains("<system-reminder>"))
        assertTrue(note.contains("TodoWrite"))
        assertTrue(note.contains("CronCreate"))
        assertTrue(note.contains("ToolSearch"))
        assertFalse("Read" in note)
    }

    @Test
    fun hiddenToolsAreNotSearchableOrCallable() {
        val f = Fixture(hidden = setOf("CronCreate"))

        assertNull(f.registry.resolveDeferred("CronCreate"))
        val names = ToolSearchQuery.search("select:CronCreate", f.registry.deferredTools(), f.registry.visibleTools(), 5)
        assertFalse("CronCreate" in names)
        assertFalse(f.registry.exposed(f.tools, setOf("CronCreate")).any { it.name == "CronCreate" })
    }

    @Test
    fun keywordSearchRanksNameBeforeDescription() {
        val f = Fixture()
        val names = ToolSearchQuery.search("schedule", f.registry.deferredTools(), f.registry.visibleTools(), 5)

        assertEquals(listOf("CronCreate"), names)
    }

    @Test
    fun requiredTermFiltersOutNonMatches() {
        val f = Fixture()

        assertTrue(ToolSearchQuery.search("+slack todo", f.registry.deferredTools(), f.registry.visibleTools(), 5).isEmpty())
        assertEquals(
            listOf("TodoWrite"),
            ToolSearchQuery.search("+todo update", f.registry.deferredTools(), f.registry.visibleTools(), 5),
        )
    }

    @Test
    fun selectIsCaseInsensitiveAndCanReachResidentTools() {
        val f = Fixture()

        assertEquals(
            listOf("TodoWrite", "Read"),
            ToolSearchQuery.search("select:todowrite,READ", f.registry.deferredTools(), f.registry.visibleTools(), 5),
        )
    }

    @Test
    fun toolSearchExpandsSchemaAndMarksToolDiscovered() = runTest {
        val f = Fixture()
        val tool = ToolSearchTool(f.registry)

        val result = tool.execute(buildJsonObject {
            put("query", "select:TodoWrite")
            put("max_results", 5)
        })

        assertFalse(result.isError)
        assertTrue(result.output.contains("<functions>"))
        assertTrue(result.output.contains("\"name\":\"TodoWrite\""))
        assertTrue(result.output.contains("\"parameters\""))
        assertTrue(f.registry.isDiscovered("TodoWrite"))
        assertFalse(f.registry.isDiscovered("CronCreate"))
    }

    @Test
    fun toolSearchReportsNoMatchWithoutMutatingState() = runTest {
        val f = Fixture()
        val tool = ToolSearchTool(f.registry)

        val result = tool.execute(buildJsonObject {
            put("query", "no-such-tool")
            put("max_results", 5)
        })

        assertEquals("No matching deferred tools found", result.output)
        assertTrue(f.registry.discoveredNames().isEmpty())
    }
}
