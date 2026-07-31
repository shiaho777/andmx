package com.andmx.agent

import com.andmx.mcp.McpClient
import com.andmx.mcp.McpToolDesc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpTool(
    private val client: McpClient,
    private val desc: McpToolDesc,
) : Tool {
    override val name = McpWireNames.serverTool(client.serverName, desc.name)
    override val description = desc.description
    override val risk = ToolRisk.EXECUTE
    override val parameters: JsonObject =
        desc.inputSchema.ifEmptySchema()

    override suspend fun execute(args: JsonObject): ToolResult = runCatching {
        ToolResult(client.callTool(desc.name, args))
    }.getOrElse { ToolResult("MCP 调用失败: ${it.message}", isError = true) }

    private fun JsonObject.ifEmptySchema(): JsonObject =
        if (this.isEmpty()) buildJsonObject { put("type", "object") } else this
}
