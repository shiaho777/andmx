package com.andmx.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Newline-delimited JSON-RPC 2.0 framing for the MCP stdio transport. Pure /
 * testable — no I/O. Each message is a single line of JSON.
 */
object JsonRpc {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun request(id: Int, method: String, params: JsonObject? = null): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun notification(method: String, params: JsonObject? = null): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    data class Response(val id: Int, val result: JsonElement?, val error: String?)

    /** Parse a line; returns a [Response] only if it carries an id (i.e. a reply). */
    fun parseResponse(line: String): Response? {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val id = runCatching { obj["id"]?.jsonPrimitive?.int }.getOrNull() ?: return null
        val error = (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.content
        return Response(id, obj["result"], error)
    }
}
