package com.andmx.agent.ext

import android.util.Log
import com.andmx.settings.CredentialStorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

typealias ExtensionMethodHandler = suspend (JsonObject) -> JsonElement

class ExtensionBridge(
    val pluginId: String,
    private val secrets: ExtensionSecrets,
    private val storage: ExtensionStorage,
    private val slots: SlotSink = SlotSink { ExtensionSlotRegistry.register(it) },
    private val onReady: () -> Unit = {},
    private val secretInvoker: SecretInvoker? = null,
) {
    fun interface SlotSink {
        fun register(declaration: SlotDeclaration): Boolean
    }

    fun interface SecretInvoker {
        suspend fun invoke(pluginId: String, handle: String, method: String): JsonElement
    }

    private val handlers: MutableMap<String, ExtensionMethodHandler> = mutableMapOf()
    private val lock = Any()

    init {
        register("ext.ready") {
            onReady()
            JsonPrimitive(true)
        }
        register("ext.log") { params ->
            val level = params["level"]?.jsonPrimitive?.contentOrNull ?: "info"
            val message = params["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            when (level) {
                "warn" -> Log.w(TAG, "[$pluginId] $message")
                "error" -> Log.e(TAG, "[$pluginId] $message")
                else -> Log.i(TAG, "[$pluginId] $message")
            }
            JsonPrimitive(true)
        }
        register("ext.getSecret") { params ->
            val key = secretKey(params)
            buildJsonObject {
                put("handle", secrets.handle(pluginId, key))
                put("exists", secrets.exists(pluginId, key))
            }
        }
        register("ext.setSecret") { params ->
            val key = secretKey(params)
            val value = params["value"]?.jsonPrimitive?.contentOrNull
                ?: throw BridgeFailure(INVALID_PARAMS, "value is required")
            if (value.toByteArray(Charsets.UTF_8).size > MAX_SECRET_BYTES) {
                throw BridgeFailure(INVALID_PARAMS, "secret value too large")
            }
            try {
                secrets.set(pluginId, key, value)
            } catch (e: CredentialStorageException) {
                throw BridgeFailure(INTERNAL_ERROR, "secure storage unavailable")
            }
            buildJsonObject {
                put("handle", secrets.handle(pluginId, key))
                put("exists", true)
            }
        }
        register("ext.callWithSecret") { params ->
            val handle = params["handle"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val method = params["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (handle.isBlank() || method.isBlank()) {
                throw BridgeFailure(INVALID_PARAMS, "handle and method are required")
            }
            if (!secrets.existsHandle(pluginId, handle)) {
                throw BridgeFailure(INVALID_PARAMS, "unknown secret handle")
            }
            val invoker = secretInvoker
                ?: throw BridgeFailure(METHOD_NOT_FOUND, "callWithSecret is reserved and not enabled yet")
            invoker.invoke(pluginId, handle, method)
        }
        register("ext.ui.registerSlot") { params ->
            val slotId = params["slotId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = params["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val htmlAsset = params["htmlAsset"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (slotId !in ExtensionSlotRegistry.KNOWN_SLOTS) {
                throw BridgeFailure(INVALID_PARAMS, "unknown slot: $slotId")
            }
            val declaration = SlotDeclaration(pluginId, slotId, title, htmlAsset)
            if (!slots.register(declaration)) {
                throw BridgeFailure(INVALID_PARAMS, "invalid slot declaration")
            }
            buildJsonObject {
                put("registered", true)
                put("slotId", slotId)
            }
        }
        register("ext.storage.get") { params ->
            val key = storageKey(params)
            storage.get(pluginId, key)?.let { JsonPrimitive(it) } ?: JsonNull
        }
        register("ext.storage.set") { params ->
            val key = storageKey(params)
            val value = params["value"]?.jsonPrimitive?.contentOrNull
                ?: throw BridgeFailure(INVALID_PARAMS, "value is required")
            if (value.toByteArray(Charsets.UTF_8).size > MAX_STORAGE_VALUE_BYTES) {
                throw BridgeFailure(INVALID_PARAMS, "storage value too large")
            }
            storage.set(pluginId, key, value)
            JsonPrimitive(true)
        }
    }

    fun register(method: String, handler: ExtensionMethodHandler) {
        synchronized(lock) { handlers[method] = handler }
    }

    fun registeredMethods(): Set<String> = synchronized(lock) { handlers.keys.toSet() }

    suspend fun handleMessage(raw: String): String? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
            Log.w(TAG, "[$pluginId] rejected oversized message (${raw.length} chars)")
            return errorResponse(JsonNull, INVALID_REQUEST, "message exceeds 1MB limit")
        }
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val obj = element as? JsonObject
            ?: return errorResponse(JsonNull, PARSE_ERROR, "invalid JSON-RPC request")
        val id = obj["id"] ?: JsonNull
        if ((obj["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
            return errorResponse(id, INVALID_REQUEST, "jsonrpc must be \"2.0\"")
        }
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        if (method.isNullOrBlank()) return errorResponse(id, INVALID_REQUEST, "method is required")
        val params = when (val p = obj["params"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> p
            else -> return errorResponse(id, INVALID_PARAMS, "params must be an object")
        }
        val denied = findDeniedArgument(scanTarget(method, params))
        if (denied != null) {
            Log.w(TAG, "[$pluginId] denied argument for $method: $denied")
            return errorResponse(id, PERMISSION_DENIED, "denied argument: $denied")
        }
        val handler = synchronized(lock) { handlers[method] }
            ?: return errorResponse(id, METHOD_NOT_FOUND, "method not registered: $method")
        val result = try {
            handler(params)
        } catch (e: BridgeFailure) {
            return errorResponse(id, e.code, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "[$pluginId] handler for $method failed: ${e.message}")
            return errorResponse(id, INTERNAL_ERROR, e.message ?: "handler failed")
        }
        if (id is JsonNull) return null
        return successResponse(id, result)
    }

    fun successResponse(id: JsonElement, result: JsonElement): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )

    fun errorResponse(id: JsonElement, code: Int, message: String): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                }
            },
        )

    private class BridgeFailure(val code: Int, override val message: String) : Exception(message)

    private fun secretKey(params: JsonObject): String {
        val key = params["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (key.isBlank() || key.length > MAX_KEY_LENGTH) {
            throw BridgeFailure(INVALID_PARAMS, "key is required (max $MAX_KEY_LENGTH chars)")
        }
        return key
    }

    private fun scanTarget(method: String, params: JsonObject): JsonElement = when (method) {
        "ext.log" -> JsonObject(params.filterKeys { it != "message" })
        "ext.setSecret" -> JsonObject(params.filterKeys { it != "value" })
        else -> params
    }

    private fun storageKey(params: JsonObject): String {
        val key = params["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (key.isBlank() || key.length > MAX_KEY_LENGTH) {
            throw BridgeFailure(INVALID_PARAMS, "key is required (max $MAX_KEY_LENGTH chars)")
        }
        return key
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 1024 * 1024
        const val MAX_STORAGE_VALUE_BYTES = 256 * 1024
        const val MAX_SECRET_BYTES = 16 * 1024
        const val MAX_KEY_LENGTH = 128
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val PERMISSION_DENIED = -32001

        private const val TAG = "ExtensionBridge"

        private val json = Json { ignoreUnknownKeys = true }

        private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
        private val ABSOLUTE_PATH = Regex("(^|[\\s\"'(=(])/[^/\\s]|^[A-Za-z]:[\\\\/]")

        fun findDeniedArgument(params: JsonElement): String? = when (params) {
            is JsonObject -> params.entries.firstNotNullOfOrNull { (k, v) -> findDeniedArgument(v) ?: deniedKey(k) }
            is kotlinx.serialization.json.JsonArray -> params.firstNotNullOfOrNull { findDeniedArgument(it) }
            is JsonPrimitive -> if (params.isString) deniedValue(params.content) else null
            else -> null
        }

        private fun deniedKey(key: String): String? =
            if (key.lowercase() in setOf("path", "filepath", "file", "url", "uri")) "field:$key" else null

        private fun deniedValue(value: String): String? {
            if (value.length > MAX_KEY_LENGTH * 32) return null
            if (URL_SCHEME.containsMatchIn(value)) return "url"
            if (ABSOLUTE_PATH.containsMatchIn(value)) return "path"
            if (value.split('/').contains("..")) return "path"
            if (value.startsWith("~")) return "path"
            return null
        }
    }
}
