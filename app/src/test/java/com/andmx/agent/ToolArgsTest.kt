package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolArgsTest {
    @Test
    fun readsJsonStringValue() {
        val args = """{"path":"/root/app/src/Main.kt","content":"hello"}"""

        assertEquals("/root/app/src/Main.kt", ToolArgs.filePath("read_file", args))
    }

    @Test
    fun fallbackParserHandlesEscapedQuotes() {
        val args = """"path":"/root/quoted-\"name\".kt""""

        assertEquals("/root/quoted-\"name\".kt", ToolArgs.value(args, "path"))
    }

    @Test
    fun webSearchBuildsDuckDuckGoUrl() {
        val args = """{"query":"codex android ui"}"""

        assertEquals("https://duckduckgo.com/?q=codex+android+ui", ToolArgs.webUrl("web_search", args))
    }

    @Test
    fun workflowLegacyNameCanonicalizesToWorkflowGroup() {
        assertEquals("workflow", ToolArgs.canonical("Workflow"))
        assertEquals("workflow", ToolArgs.canonical("CreateWorkflow"))
        assertEquals(ToolArgs.canonical("CreateWorkflow"), ToolArgs.canonical("Workflow"))
    }

    @Test
    fun workflowAliasIsGatedWithDynamicWorkflowTools() {
        val names = com.andmx.agent.zcode.DYNAMIC_WORKFLOW_TOOL_NAMES
        org.junit.Assert.assertTrue("Workflow" in names)
        val gated = com.andmx.agent.zcode.applyDynamicWorkflowGate(
            listOf(FakeNamedTool("Workflow"), FakeNamedTool("CreateWorkflow"), FakeNamedTool("Read")),
            enabled = false,
        )
        assertEquals(listOf("Read"), gated.map { it.name })
    }

    private class FakeNamedTool(override val name: String) : Tool {
        override val description = ""
        override val parameters = kotlinx.serialization.json.buildJsonObject { }
        override suspend fun execute(args: kotlinx.serialization.json.JsonObject) = ToolResult("ok")
    }
}
