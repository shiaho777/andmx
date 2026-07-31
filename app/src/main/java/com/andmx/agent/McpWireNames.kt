package com.andmx.agent

import kotlinx.serialization.json.JsonObject

object McpWireNames {
    fun sanitize(part: String): String =
        part.trim().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { "x" }

    fun serverTool(server: String, tool: String): String =
        "mcp__${sanitize(server)}__${sanitize(tool)}"

    fun pluginTool(plugin: String, server: String, tool: String): String =
        "mcp__plugin_${sanitize(plugin)}_${sanitize(server)}__${sanitize(tool)}"
}

class RenamedTool(
    private val inner: Tool,
    override val name: String,
    override val description: String = inner.description,
    override val parameters: JsonObject = inner.parameters,
    override val risk: ToolRisk = inner.risk,
) : Tool, ExecutionAwareTool {
    override suspend fun execute(args: JsonObject): ToolResult = execute("", args)
    override suspend fun execute(callId: String, args: JsonObject): ToolResult {
        return if (inner is ExecutionAwareTool && callId.isNotBlank()) {
            inner.execute(callId, args)
        } else {
            inner.execute(args)
        }
    }
}
