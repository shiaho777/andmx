package com.andmx.llm.wire

import com.andmx.llm.ApiFunctionDef
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ChatRequest
import com.andmx.llm.provider.ProviderDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anthropic Messages wire adapter: `POST {base}/v1/messages`.
 *
 * Also spoken by Anthropic-compatible gateways (Z.ai, BigModel/GLM, …). This is
 * a structurally different protocol from OpenAI Chat Completions:
 *
 * - `system` is a top-level field, not a message in the array.
 * - Roles are only `user`/`assistant`; tool exchanges ride inside content blocks
 *   (`tool_use` on assistant, `tool_result` on the following user turn).
 * - Tools use `input_schema` instead of `parameters`, wrapped in `type:"custom"`.
 * - SSE emits typed events: `message_start`, `content_block_start`,
 *   `content_block_delta` (with `text_delta` / `input_json_delta`),
 *   `content_block_stop`, `message_delta`, `message_stop`.
 */
object AnthropicMessagesAdapter : WireAdapter {
    private val json = Json { ignoreUnknownKeys = true }

    /** Anthropic spec: thinking budget_tokens must be at least 1024. */
    private const val MIN_THINKING_BUDGET = 1024

    override fun endpointUrl(base: String): String = base.trimEnd('/') + "/v1/messages"

    override fun authHeader(apiKey: String): Pair<String, String>? =
        if (apiKey.isNotBlank()) "x-api-key" to apiKey else null

    override fun extraHeaders(): List<Pair<String, String>> =
        listOf("anthropic-version" to "2023-06-01")

    // ── Request encoding ──────────────────────────────────────────────────────

    override fun encodeRequest(req: ChatRequest, provider: ProviderDefinition): String {
        // Split off the leading system message(s); Anthropic wants them top-level.
        val systemText = req.messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content.orEmpty() }
            .ifBlank { null }

        // Translate the remaining OpenAI-style message flow into Anthropic
        // content blocks. tool messages become tool_result blocks under a user
        // turn; assistant tool_calls become tool_use blocks.
        val userAssistant = req.messages.filter { it.role != "system" }
        val anthropicMessages = buildJsonArray {
            userAssistant.forEach { m ->
                add(messageToAnthropic(m))
            }
        }

        val root = buildJsonObject {
            put("model", req.model)
            // Anthropic requires max_tokens; default to a generous cap when unset.
            put("max_tokens", provider.models[req.model]?.maxOutputTokens?.takeIf { it > 0 } ?: 8_192)
            if (systemText != null) put("system", systemText)
            put("messages", anthropicMessages)
            req.stream.let { if (it) put("stream", true) }
            // Extended thinking — only when the model declares the THINKING style.
            // budget_tokens comes from the model's ReasoningConfig (default or a
            // user-supplied numeric value), clamped to ≥1024 and ≤ maxOutputTokens.
            val reasoning = provider.models[req.model]?.reasoning
            val wantsThinking = req.reasoningEffort?.let { it.isNotBlank() && it != "off" } == true &&
                reasoning?.style == com.andmx.llm.provider.ReasoningStyle.THINKING
            if (wantsThinking && reasoning != null) {
                val maxOut = provider.models[req.model]?.maxOutputTokens?.takeIf { it > 0 } ?: Int.MAX_VALUE
                val raw = req.reasoningEffort!!.toIntOrNull() ?: reasoning.defaultBudgetTokens
                val budget = raw.coerceIn(MIN_THINKING_BUDGET, maxOut)
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            }
            req.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") {
                    tools.forEach { t ->
                        addJsonObject {
                            put("name", t.function.name)
                            put("description", t.function.description)
                            putJsonObject("input_schema") {
                                // parameters is already a JSON Schema object.
                                put("type", "object")
                                put("properties", t.function.parameters["properties"] ?: JsonObject(emptyMap()))
                                (t.function.parameters["required"] as? JsonArray)?.let { put("required", it) }
                            }
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** Convert one OpenAI-style ApiMessage into an Anthropic message object. */
    private fun messageToAnthropic(m: ApiMessage): JsonObject = when (m.role) {
        "tool" -> {
            // tool result → a user turn with a tool_result block. When the tool
            // produced images (computer-use screenshots), Anthropic accepts an
            // array of content blocks (text + image) inside tool_result.
            buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", m.toolCallId ?: "")
                        val imgs = m.imageUrls
                        if (!imgs.isNullOrEmpty()) {
                            // Rich tool result: text + image content blocks.
                            putJsonArray("content") {
                                m.content?.let { addJsonObject { put("type", "text"); put("text", it) } }
                                imgs.forEach { url -> addJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") { dataUrlToSource(url) }
                                } }
                            }
                        } else {
                            m.content?.let { put("content", it) }
                        }
                    }
                }
            }
        }
        "assistant" -> {
            buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    m.content?.takeIf { it.isNotBlank() }?.let {
                        addJsonObject { put("type", "text"); put("text", it) }
                    }
                    m.toolCalls?.forEach { tc ->
                        addJsonObject {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.function.name)
                            // arguments is a JSON string → parse back to an object.
                            val input = runCatching { json.parseToJsonElement(tc.function.arguments) }.getOrNull()
                                ?: JsonObject(emptyMap())
                            put("input", input)
                        }
                    }
                }
            }
        }
        else -> {
            // user (or anything else) → text content block(s).
            buildJsonObject {
                put("role", m.role.ifBlank { "user" })
                putJsonArray("content") {
                    addJsonObject { put("type", "text"); put("text", m.content.orEmpty()) }
                }
            }
        }
    }

    // ── Response parsing (non-streaming) ──────────────────────────────────────

    override fun parseResponse(body: String): ApiMessage {
        val root = json.parseToJsonElement(body).jsonObject
        val blocks = root["content"]?.jsonArray ?: return ApiMessage(role = "assistant")
        return assembleFromBlocks(blocks)
    }

    override fun extractUsage(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject["usage"] as? JsonObject }.getOrNull()

    private fun assembleFromBlocks(blocks: JsonArray): ApiMessage {
        val text = StringBuilder()
        val toolCalls = mutableListOf<ApiToolCall>()
        blocks.forEach { b ->
            val o = b.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> o["text"]?.jsonPrimitive?.contentOrNull?.let { text.append(it) }
                "tool_use" -> {
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: "call_${toolCalls.size}"
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = o["input"]?.let { json.encodeToString(JsonObject.serializer(), it.jsonObject) } ?: "{}"
                    toolCalls += ApiToolCall(id = id, function = ApiFunctionCall(name, input))
                }
            }
        }
        return ApiMessage(
            role = "assistant",
            content = text.toString().takeIf { it.isNotEmpty() },
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
        )
    }

    // ── SSE stream parsing ────────────────────────────────────────────────────

    /**
     * Anthropic streams typed events: each SSE `data:` line carries
     * `{"type": "...", ...}`. We accumulate content blocks by index and emit
     * text deltas live via [onContent].
     */
    override suspend fun parseStream(
        lines: Sequence<String>,
        onContent: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        onToolCall: suspend (index: Int, id: String?, name: String?, argumentsDelta: String) -> Unit,
    ): ApiMessage {
        // index → (type, id/name for tool_use, accumulated input json string)
        val blocks = sortedMapOf<Int, BlockAcc>()
        val text = StringBuilder()

        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val ev = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_start" -> {
                    val idx = ev["index"]?.jsonPrimitive?.intOrNull ?: continue
                    val block = ev["content_block"]?.jsonObject ?: continue
                    val acc = blocks.getOrPut(idx) { BlockAcc() }
                    acc.type = block["type"]?.jsonPrimitive?.contentOrNull
                    acc.id = block["id"]?.jsonPrimitive?.contentOrNull
                    acc.name = block["name"]?.jsonPrimitive?.contentOrNull
                    if (acc.type == "tool_use") {
                        onToolCall(idx, acc.id, acc.name, "")
                    }
                }
                "content_block_delta" -> {
                    val idx = ev["index"]?.jsonPrimitive?.intOrNull ?: continue
                    val delta = ev["delta"]?.jsonObject ?: continue
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val piece = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (piece.isNotEmpty()) { text.append(piece); onContent(piece) }
                        }
                        "thinking_delta" -> {
                            val piece = delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (piece.isNotEmpty()) onReasoning(piece)
                        }
                        "input_json_delta" -> {
                            val piece = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val acc = blocks.getOrPut(idx) { BlockAcc() }
                            if (piece.isNotEmpty()) acc.input.append(piece)
                            if (acc.type == "tool_use" || acc.name != null) {
                                onToolCall(idx, acc.id, acc.name, piece)
                            }
                        }
                    }
                }
                "content_block_stop" -> { /* block complete; nothing to do */ }
                "message_delta" -> { /* final usage may arrive here */ }
                "message_stop" -> break
            }
        }

        val toolCalls = blocks.values
            .filter { it.type == "tool_use" && it.name != null }
            .mapIndexed { i, b -> ApiToolCall(id = b.id ?: "call_$i", function = ApiFunctionCall(b.name!!, b.input.toString().ifBlank { "{}" })) }
            .takeIf { it.isNotEmpty() }

        return ApiMessage(
            role = "assistant",
            content = text.toString().takeIf { it.isNotEmpty() },
            toolCalls = toolCalls,
        )
    }

    private class BlockAcc {
        var type: String? = null
        var id: String? = null
        var name: String? = null
        val input = StringBuilder()
    }

    /**
     * Split a `data:<mime>;base64,<payload>` url into the Anthropic image source
     * fields ({type:"base64", media_type, data}). Emits nothing on malformed input
     * so a bad data-url can't break serialization.
     */
    private fun JsonObjectBuilder.dataUrlToSource(url: String) {
        val semi = url.indexOf(';')
        val comma = url.indexOf(',')
        if (!url.startsWith("data:") || semi < 0 || comma < 0) return
        val mediaType = url.substring(5, semi)
        val data = url.substring(comma + 1)
        put("type", "base64")
        put("media_type", mediaType)
        put("data", data)
    }

    /**
     * `GET {base}/models` — Anthropic catalogue listing.
     *
     * Anthropic's models endpoint uses the same `{ "data": [{ "id": "..." }] }`
     * envelope as OpenAI but authenticates with `x-api-key` plus the
     * `anthropic-version` header (both supplied via [authHeader] /
     * [extraHeaders]). Returns an empty list on any failure.
     */
    override suspend fun listModels(def: ProviderDefinition): List<String> = withContext(Dispatchers.IO) {
        val url = URL(def.baseUrl.trimEnd('/') + "/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            authHeader(def.apiKey)?.let { (k, v) -> setRequestProperty(k, v) }
            extraHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
            def.httpHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: ${text.take(200)}")
            }
            OpenAiChatAdapter.parseModelCatalogue(text)
        } finally {
            conn.disconnect()
        }
    }
}
