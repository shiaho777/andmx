package com.andmx.agent

import com.andmx.mcp.McpClient
import com.andmx.mcp.McpToolDesc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Exposes an MCP server's tool to the agent. Namespaced by server. */
class McpTool(
    private val client: McpClient,
    private val desc: McpToolDesc,
) : Tool {
    override val name = sanitize("${client.serverName}_${desc.name}")
    override val description = desc.description
    override val risk = ToolRisk.EXECUTE
    override val parameters: JsonObject =
        desc.inputSchema.ifEmptySchema()

    override suspend fun execute(args: JsonObject): ToolResult = runCatching {
        ToolResult(client.callTool(desc.name, args).take(16_000))
    }.getOrElse { ToolResult("MCP 调用失败: ${it.message}", isError = true) }

    private fun JsonObject.ifEmptySchema(): JsonObject =
        if (this.isEmpty()) buildJsonObject { put("type", "object") } else this

    private companion object {
        fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    }
}
