package com.andmx.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A tool advertised by an MCP server. */
data class McpToolDesc(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * MCP client over a child process's stdio (newline-delimited JSON-RPC).
 * Supports protocol versions 2024-11-05 through 2025-06-18, including
 * tools, resources, prompts, and elicitation.
 *
 * One long-lived process per server.
 */
class McpClient(
    val serverName: String,
    private val argv: List<String>,
    private val env: Map<String, String>,
    private val protocolVersion: String = McpProtocol.LATEST_VERSION,
    private val elicitationHandler: (suspend (McpElicitationRequest) -> McpElicitationResponse)? = null,
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonRpc.Response>>()
    private val idGen = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Negotiated protocol version after initialize. */
    var negotiatedVersion: String? = null
        private set

    /** Capabilities the server reported during initialize. */
    var serverCapabilities: JsonObject? = null
        private set

    suspend fun connect(): List<McpToolDesc> {
        val pb = ProcessBuilder(argv)
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)
        val p = pb.start().also { process = it }
        writer = p.outputStream.bufferedWriter()

        // reader loop: complete pending requests by id, handle server-initiated requests
        scope.launch {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    handleMessage(line)
                }
            }
        }

        // initialize handshake with latest protocol version
        val initResult = request(McpProtocol.INITIALIZE, buildJsonObject {
            put("protocolVersion", protocolVersion)
            put("capabilities", buildJsonObject {
                McpProtocol.CLIENT_CAPABILITIES.forEach { (k, v) ->
                    @Suppress("UNCHECKED_CAST")
                    put(k, v as JsonElement)
                }
            })
            put("clientInfo", buildJsonObject { put("name", "AndMX"); put("version", "0.2") })
        })

        // Record negotiated version and server capabilities
        (initResult as? JsonObject)?.let { result ->
            negotiatedVersion = result["protocolVersion"]?.jsonPrimitive?.content
            serverCapabilities = result["capabilities"] as? JsonObject
        }

        notify(McpProtocol.INITIALIZED)

        val toolsResult = request(McpProtocol.TOOLS_LIST, null)
        val arr = (toolsResult?.jsonObject?.get("tools") as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            McpToolDesc(
                name = name,
                description = o["description"]?.jsonPrimitive?.content ?: name,
                inputSchema = (o["inputSchema"] as? JsonObject) ?: JsonObject(emptyMap()),
            )
        }
    }

    suspend fun callTool(name: String, args: JsonObject): String {
        val res = request(McpProtocol.TOOLS_CALL, buildJsonObject {
            put("name", name)
            put("arguments", args)
        }) ?: return "(无响应)"
        val content = (res.jsonObject["content"] as? JsonArray)
        if (content != null) {
            return content.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
                .joinToString("\n").ifBlank { res.toString() }
        }
        return res.toString()
    }

    // ── Resources ──────────────────────────────────────

    suspend fun listResources(): List<McpResource> {
        if (!supportsCapability("resources")) return emptyList()
        val res = request(McpProtocol.RESOURCES_LIST, null) ?: return emptyList()
        val arr = (res.jsonObject["resources"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            McpResource(
                uri = o["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.content ?: "",
                description = o["description"]?.jsonPrimitive?.content,
                mimeType = o["mimeType"]?.jsonPrimitive?.content,
            )
        }
    }

    suspend fun readResource(uri: String): String {
        val res = request(McpProtocol.RESOURCES_READ, buildJsonObject {
            put("uri", uri)
        }) ?: return "(无响应)"
        val contents = (res.jsonObject["contents"] as? JsonArray) ?: return res.toString()
        return contents.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
            .joinToString("\n").ifBlank { res.toString() }
    }

    suspend fun listResourceTemplates(): List<McpResourceTemplate> {
        if (!supportsCapability("resources")) return emptyList()
        val res = request(McpProtocol.RESOURCES_TEMPLATES_LIST, null) ?: return emptyList()
        val arr = (res.jsonObject["resourceTemplates"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            McpResourceTemplate(
                uriTemplate = o["uriTemplate"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.content ?: "",
                description = o["description"]?.jsonPrimitive?.content,
                mimeType = o["mimeType"]?.jsonPrimitive?.content,
            )
        }
    }

    // ── Prompts ────────────────────────────────────────

    suspend fun listPrompts(): List<McpPrompt> {
        if (!supportsCapability("prompts")) return emptyList()
        val res = request(McpProtocol.PROMPTS_LIST, null) ?: return emptyList()
        val arr = (res.jsonObject["prompts"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val args = (o["arguments"] as? JsonArray)?.mapNotNull { ae ->
                val ao = ae as? JsonObject ?: return@mapNotNull null
                McpPromptArgument(
                    name = ao["name"]?.jsonPrimitive?.content ?: "",
                    description = ao["description"]?.jsonPrimitive?.content,
                    required = ao["required"]?.jsonPrimitive?.content == "true",
                )
            } ?: emptyList()
            McpPrompt(name, o["description"]?.jsonPrimitive?.content, args)
        }
    }

    suspend fun getPrompt(name: String, args: Map<String, String> = emptyMap()): List<McpPromptContent> {
        if (!supportsCapability("prompts")) return emptyList()
        val res = request(McpProtocol.PROMPTS_GET, buildJsonObject {
            put("name", name)
            if (args.isNotEmpty()) {
                val argsObj = buildJsonObject { args.forEach { (k, v) -> put(k, v) } }
                put("arguments", argsObj)
            }
        }) ?: return emptyList()
        val messages = (res.jsonObject["messages"] as? JsonArray) ?: return emptyList()
        return messages.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val role = o["role"]?.jsonPrimitive?.content ?: "assistant"
            val content = o["content"]
            val text = when {
                content is JsonObject -> content["text"]?.jsonPrimitive?.content ?: ""
                content != null -> content.toString()
                else -> ""
            }
            McpPromptContent(role, text)
        }
    }

    // ── Capability check ───────────────────────────────

    fun supportsCapability(name: String): Boolean {
        val caps = serverCapabilities ?: return false
        return caps.containsKey(name)
    }

    // ── Internal ───────────────────────────────────────

    private fun handleMessage(line: String) {
        val obj = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.parseToJsonElement(line).jsonObject
        }.getOrNull() ?: return

        // Check if this is a server-initiated request (has method + id but no result)
        val method = obj["method"]?.jsonPrimitive?.content
        val id = obj["id"]?.jsonPrimitive

        if (method != null && id != null && !obj.containsKey("result")) {
            // Server → Client request (e.g., elicitation/create, sampling/createMessage)
            handleServerRequest(id.content.toIntOrNull() ?: return, method, obj["params"] as? JsonObject)
            return
        }

        // Server notification (has method, no id) — handle silently
        if (method != null && id == null && !obj.containsKey("result")) {
            // Notifications like tools/list_changed, resources/updated etc.
            // These are silently acknowledged — tool list refresh would require
            // re-calling tools/list, which McpManager can trigger on demand.
            return
        }

        // Regular response to our request
        JsonRpc.parseResponse(line)?.let { resp ->
            pending.remove(resp.id)?.complete(resp)
        }
    }

    private fun handleServerRequest(id: Int, method: String, params: JsonObject?) {
        scope.launch {
            when (method) {
                McpProtocol.ELICITATION_CREATE -> {
                    val handler = elicitationHandler ?: run {
                        sendResponse(id, error = "elicitation not supported")
                        return@launch
                    }
                    val msg = params?.get("message")?.jsonPrimitive?.content ?: ""
                    val schema = params?.get("schema") as? JsonObject
                    val response = handler(McpElicitationRequest(msg, schema))
                    sendResponse(id, result = buildJsonObject {
                        put("action", response.action)
                        if (response.content != null) put("content", response.content)
                    })
                }
                McpProtocol.SAMPLING_CREATE_MESSAGE -> {
                    // Sampling not yet implemented — decline gracefully
                    sendResponse(id, error = "sampling not supported")
                }
                McpProtocol.PING -> {
                    sendResponse(id, result = buildJsonObject {})
                }
                else -> {
                    sendResponse(id, error = "unknown method: $method")
                }
            }
        }
    }

    private fun sendResponse(id: Int, result: JsonObject? = null, error: String? = null) {
        val w = writer ?: return
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (result != null) put("result", result)
            if (error != null) {
                put("error", buildJsonObject { put("message", error) })
            }
        }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        synchronized(w) { w.write(json.encodeToString(JsonObject.serializer(), obj)); w.newLine(); w.flush() }
    }

    private suspend fun request(method: String, params: JsonObject?): JsonElement? {
        val w = writer ?: return null
        val id = idGen.incrementAndGet()
        val deferred = CompletableDeferred<JsonRpc.Response>()
        pending[id] = deferred
        synchronized(w) { w.write(JsonRpc.request(id, method, params)); w.newLine(); w.flush() }
        val resp = runCatching { withTimeout(60_000) { deferred.await() } }.getOrNull()
        pending.remove(id)  // Always clean up, even on timeout
        if (resp?.error != null) throw RuntimeException("MCP error: ${resp.error}")
        return resp?.result
    }

    private fun notify(method: String) {
        val w = writer ?: return
        synchronized(w) { w.write(JsonRpc.notification(method)); w.newLine(); w.flush() }
    }

    fun close() {
        runCatching { process?.destroy() }
        scope.cancel()
    }
}
