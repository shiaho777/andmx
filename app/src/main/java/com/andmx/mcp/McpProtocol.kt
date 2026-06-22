package com.andmx.mcp

/**
 * MCP protocol constants and capability definitions.
 * Mirrors Codex's rmcp-based implementation supporting protocol versions
 * from 2024-11-05 through 2025-11-25.
 */
object McpProtocol {
    /** Latest protocol version we support. */
    const val LATEST_VERSION = "2025-06-18"

    /** All known protocol versions, ordered newest first. */
    val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    /** Client capabilities advertised during initialize. */
    val CLIENT_CAPABILITIES = mapOf(
        "roots" to mapOf("listChanged" to true),
        "sampling" to emptyMap<String, Any>(),
        "elicitation" to emptyMap<String, Any>(),
    )

    // ── Method names ──
    const val INITIALIZE = "initialize"
    const val INITIALIZED = "notifications/initialized"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val TOOLS_LIST_CHANGED = "notifications/tools/list_changed"

    const val RESOURCES_LIST = "resources/list"
    const val RESOURCES_READ = "resources/read"
    const val RESOURCES_SUBSCRIBE = "resources/subscribe"
    const val RESOURCES_UNSUBSCRIBE = "resources/unsubscribe"
    const val RESOURCES_LIST_CHANGED = "notifications/resources/list_changed"
    const val RESOURCES_UPDATED = "notifications/resources/updated"
    const val RESOURCES_TEMPLATES_LIST = "resources/templates/list"

    const val PROMPTS_LIST = "prompts/list"
    const val PROMPTS_GET = "prompts/get"
    const val PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed"

    const val ELICITATION_CREATE = "elicitation/create"
    const val SAMPLING_CREATE_MESSAGE = "sampling/createMessage"

    const val TASKS_LIST = "tasks/list"
    const val TASKS_GET = "tasks/get"
    const val TASKS_CANCEL = "tasks/cancel"

    const val LOGGING_SET_LEVEL = "logging/setLevel"
    const val COMPLETION_COMPLETE = "completion/complete"
    const val PING = "ping"
}

/** A resource advertised by an MCP server. */
data class McpResource(
    val uri: String,
    val name: String,
    val description: String?,
    val mimeType: String?,
)

/** A resource template (URI template with variables). */
data class McpResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val description: String?,
    val mimeType: String?,
)

/** A prompt advertised by an MCP server. */
data class McpPrompt(
    val name: String,
    val description: String?,
    val arguments: List<McpPromptArgument>,
)

data class McpPromptArgument(
    val name: String,
    val description: String?,
    val required: Boolean,
)

/** The content returned by prompts/get. */
data class McpPromptContent(
    val role: String,
    val content: String,
)

/** Elicitation request from server to client. */
data class McpElicitationRequest(
    val message: String,
    val schema: kotlinx.serialization.json.JsonObject?,
)

/** Elicitation response from user. */
data class McpElicitationResponse(
    val action: String,           // "accept" or "decline"
    val content: String?,
)

/** Transport type for an MCP server connection. */
enum class McpTransport { STDIO, HTTP, WEBSOCKET }
