package com.andmx.mcp

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP HTTP/SSE transport — mirrors Codex's MCP HTTP transport support.
 *
 * Connects to an MCP server over HTTP with Server-Sent Events (SSE) for
 * server-to-client communication. Alternative to stdio for remote servers.
 */
class McpHttpTransport(
    val serverName: String,
    private val endpointUrl: String,
    private val headers: Map<String, String> = emptyMap(),
    private val protocolVersion: String = McpProtocol.LATEST_VERSION,
    private val timeoutMs: Long = 60_000,
    private val elicitationHandler: (suspend (McpElicitationRequest) -> McpElicitationResponse)? = null,
) : AutoCloseable {
    companion object {
        private const val TAG = "McpHttpTransport"
        private const val TIMEOUT_MS = 60_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonRpc.Response>>()
    private val idGen = AtomicInteger(0)

    var negotiatedVersion: String? = null
        private set
    var serverCapabilities: JsonObject? = null
        private set

    private var connected = false

    suspend fun connect(): List<McpToolDesc> {
        scope.launch { listenSse() }
        delay(500)

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

        (initResult as? JsonObject)?.let { result ->
            negotiatedVersion = result["protocolVersion"]?.jsonPrimitive?.content
            serverCapabilities = result["capabilities"] as? JsonObject
        }

        notify(McpProtocol.INITIALIZED)
        connected = true

        val toolsResult = request(McpProtocol.TOOLS_LIST, null)
        val arr = (toolsResult?.jsonObject?.get("tools") as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
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
        val content = (res.jsonObject["content"] as? kotlinx.serialization.json.JsonArray)
        return content?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
            ?.joinToString("\n")?.ifBlank { res.toString() } ?: res.toString()
    }

    suspend fun listResources(): List<McpResource> {
        if (!supportsCapability("resources")) return emptyList()
        val res = request(McpProtocol.RESOURCES_LIST, null) ?: return emptyList()
        val arr = (res.jsonObject["resources"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
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
        val res = request(McpProtocol.RESOURCES_READ, buildJsonObject { put("uri", uri) })
            ?: return "(无响应)"
        val contents = (res.jsonObject["contents"] as? kotlinx.serialization.json.JsonArray)
            ?: return res.toString()
        return contents.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
            .joinToString("\n").ifBlank { res.toString() }
    }

    suspend fun subscribeResource(uri: String): Boolean =
        runCatching {
            request(McpProtocol.RESOURCES_SUBSCRIBE, buildJsonObject { put("uri", uri) })
            true
        }.getOrDefault(false)

    suspend fun unsubscribeResource(uri: String): Boolean =
        runCatching {
            request(McpProtocol.RESOURCES_UNSUBSCRIBE, buildJsonObject { put("uri", uri) })
            true
        }.getOrDefault(false)

    suspend fun listPrompts(): List<McpPrompt> {
        if (!supportsCapability("prompts")) return emptyList()
        val res = request(McpProtocol.PROMPTS_LIST, null) ?: return emptyList()
        val arr = (res.jsonObject["prompts"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val arguments = (o["arguments"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { ae ->
                val ao = ae as? JsonObject ?: return@mapNotNull null
                McpPromptArgument(
                    name = ao["name"]?.jsonPrimitive?.content ?: "",
                    description = ao["description"]?.jsonPrimitive?.content,
                    required = ao["required"]?.jsonPrimitive?.content == "true",
                )
            } ?: emptyList()
            McpPrompt(name, o["description"]?.jsonPrimitive?.content, arguments)
        }
    }

    suspend fun getPrompt(name: String, args: Map<String, String> = emptyMap()): List<McpPromptContent> {
        if (!supportsCapability("prompts")) return emptyList()
        val res = request(McpProtocol.PROMPTS_GET, buildJsonObject {
            put("name", name)
            if (args.isNotEmpty()) {
                put("arguments", buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
            }
        }) ?: return emptyList()
        val messages = (res.jsonObject["messages"] as? kotlinx.serialization.json.JsonArray)
            ?: return emptyList()
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

    fun supportsCapability(name: String): Boolean {
        val caps = serverCapabilities ?: return false
        return caps.containsKey(name)
    }

    private suspend fun request(method: String, params: JsonObject?): JsonElement? {
        val id = idGen.incrementAndGet()
        val deferred = CompletableDeferred<JsonRpc.Response>()
        pending[id] = deferred
        sendHttpPost(JsonRpc.request(id, method, params))
        val waitMs = timeoutMs.takeIf { it > 0L } ?: TIMEOUT_MS
        val resp = runCatching { withTimeout(waitMs) { deferred.await() } }.getOrNull()
        pending.remove(id)
        if (resp?.error != null) throw RuntimeException("MCP error: ${resp.error}")
        return resp?.result
    }

    private fun notify(method: String) = sendHttpPost(JsonRpc.notification(method))

    private fun sendHttpPost(body: String) {
        scope.launch {
            runCatching {
                val url = URL(endpointUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = (timeoutMs.takeIf { it > 0L } ?: 30_000L).coerceIn(1L, 120_000L).toInt()
                    readTimeout = (timeoutMs.takeIf { it > 0L } ?: 30_000L).coerceIn(1L, 120_000L).toInt()
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json, text/event-stream")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.disconnect()
            }.onFailure { Log.w(TAG, "HTTP send failed: ${it.message}") }
        }
    }

    private suspend fun listenSse() {
        runCatching {
            val url = URL(endpointUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 0
                setRequestProperty("Accept", "text/event-stream")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var data = StringBuilder()
            reader.forEachLine { line ->
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isBlank() -> {
                        if (data.isNotEmpty()) handleMessage(data.toString())
                        data = StringBuilder()
                    }
                }
            }
            conn.disconnect()
        }.onFailure { Log.w(TAG, "SSE listener ended: ${it.message}") }
    }

    private fun handleMessage(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val method = obj["method"]?.jsonPrimitive?.content
        val id = obj["id"]?.jsonPrimitive

        if (method != null && id != null && !obj.containsKey("result")) {
            scope.launch { handleServerRequest(id.content.toIntOrNull() ?: return@launch, method, obj["params"] as? JsonObject) }
            return
        }

        JsonRpc.parseResponse(raw)?.let { resp -> pending.remove(resp.id)?.complete(resp) }
    }

    private suspend fun handleServerRequest(id: Int, method: String, params: JsonObject?) {
        when (method) {
            McpProtocol.ELICITATION_CREATE -> {
                val handler = elicitationHandler ?: run {
                    sendResponse(id, result = buildJsonObject { put("action", "cancel") })
                    return
                }
                val msg = params?.get("message")?.jsonPrimitive?.content ?: ""
                val schema = params?.get("schema") as? JsonObject
                val response = handler(McpElicitationRequest(msg, schema))
                sendResponse(id, result = buildJsonObject {
                    put("action", response.action)
                    if (response.content != null) put("content", response.content)
                })
            }
            McpProtocol.SAMPLING_CREATE_MESSAGE -> sendResponse(
                id,
                result = buildJsonObject {
                    put("role", "assistant")
                    put("model", "andmx-mobile")
                    put("content", buildJsonObject {
                        put("type", "text")
                        put("text", "Sampling declined on AndMX mobile client.")
                    })
                    put("stopReason", "endTurn")
                },
            )
            McpProtocol.PING -> sendResponse(id, result = buildJsonObject {})
            else -> sendResponse(id, error = "unknown method: $method")
        }
    }

    private fun sendResponse(id: Int, result: JsonObject? = null, error: String? = null) {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (result != null) put("result", result)
            if (error != null) put("error", buildJsonObject { put("message", error) })
        }
        sendHttpPost(json.encodeToString(JsonObject.serializer(), obj))
    }

    fun isConnected() = connected

    override fun close() {
        connected = false
        scope.cancel()
    }
}
