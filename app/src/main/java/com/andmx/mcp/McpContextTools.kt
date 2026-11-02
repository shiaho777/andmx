package com.andmx.mcp

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Read-only MCP context tools. Tools/resources/prompts are separate MCP
 * capabilities; exposing these lets the agent use a server's resources and
 * prompt templates without pretending they are callable tools.
 */
object McpContextTools {
    private const val MAX_OUTPUT = 20_000

    fun build(manager: McpManager): List<Tool> = listOf(
        ListResourcesTool(manager),
        ReadResourceTool(manager),
        ListPromptsTool(manager),
        GetPromptTool(manager),
    )

    private class ListResourcesTool(private val manager: McpManager) : Tool {
        override val name = "mcp_list_resources"
        override val description =
            "List resources exposed by connected MCP servers. Resources are readable context, not tools."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            if (manager.connected.isEmpty()) return ToolResult("没有已连接的 MCP 服务器。")
            val items = manager.listAllResourceItems()
            if (items.isEmpty()) return ToolResult("已连接的 MCP 服务器没有提供资源。")
            return ToolResult(
                items.take(200).joinToString("\n") { (server, resource) ->
                    buildString {
                        append("- [").append(server).append("] ")
                        append(resource.name.ifBlank { resource.uri })
                        append(" · ").append(resource.uri)
                        resource.mimeType?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        resource.description?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
                    }
                },
            )
        }
    }

    private class ReadResourceTool(private val manager: McpManager) : Tool {
        override val name = "mcp_read_resource"
        override val description =
            "Read one MCP resource by server name and URI. Arguments: server, uri."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("server") { put("type", "string") }
                putJsonObject("uri") { put("type", "string") }
            }
            putJsonArray("required") { add("server"); add("uri") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val server = args.string("server")
            val uri = args.string("uri")
            if (server.isBlank() || uri.isBlank()) {
                return ToolResult("server 与 uri 必填", isError = true)
            }
            val text = manager.readResource(server, uri)
                ?: return ToolResult("服务器 `$server` 未连接，或无法读取 `$uri`", isError = true)
            return ToolResult(text.take(MAX_OUTPUT))
        }
    }

    private class ListPromptsTool(private val manager: McpManager) : Tool {
        override val name = "mcp_list_prompts"
        override val description =
            "List prompt templates exposed by connected MCP servers, including their arguments."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            if (manager.connected.isEmpty()) return ToolResult("没有已连接的 MCP 服务器。")
            val items = manager.listAllPrompts()
            if (items.isEmpty()) return ToolResult("已连接的 MCP 服务器没有提供提示模板。")
            return ToolResult(
                items.take(200).joinToString("\n") { (server, prompt) ->
                    buildString {
                        append("- [").append(server).append("] ").append(prompt.name)
                        if (prompt.arguments.isNotEmpty()) {
                            append('(')
                            append(
                                prompt.arguments.joinToString(", ") {
                                    it.name + if (it.required) "" else "?"
                                },
                            )
                            append(')')
                        }
                        prompt.description?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
                    }
                },
            )
        }
    }

    private class GetPromptTool(private val manager: McpManager) : Tool {
        override val name = "mcp_get_prompt"
        override val description =
            "Render an MCP prompt template. Arguments: server, name, and optional arguments object (string values)."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("server") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
                putJsonObject("arguments") {
                    put("type", "object")
                    put("additionalProperties", JsonPrimitive(true))
                }
            }
            putJsonArray("required") { add("server"); add("name") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val server = args.string("server")
            val name = args.string("name")
            if (server.isBlank() || name.isBlank()) {
                return ToolResult("server 与 name 必填", isError = true)
            }
            val promptArgs = (args["arguments"] as? JsonObject).orEmpty()
                .mapNotNull { (key, value) ->
                    val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    key to text
                }
                .toMap()
            val messages = manager.getPrompt(server, name, promptArgs)
            if (messages.isEmpty()) {
                return ToolResult("没有拿到提示内容；请确认服务器已连接且提示名为 `$name`。", isError = true)
            }
            return ToolResult(
                messages.joinToString("\n\n") { "[${it.role}] ${it.content}" }.take(MAX_OUTPUT),
            )
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
}
