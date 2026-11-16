package com.andmx.agent

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.wire.AdapterFactory
import com.andmx.llm.wire.WireAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

interface ProviderNativeSearch {
    fun supports(): Boolean
    fun search(query: String, allowed: List<String>, blocked: List<String>): List<WebSearch.Result>
}

object NativeSearchConfig {
    @Volatile var provider: ProviderDefinition? = null
    @Volatile var model: String = ""
}

class HttpProviderNativeSearch(
    private val source: () -> Pair<ProviderDefinition, String>? = {
        val def = NativeSearchConfig.provider
        val model = NativeSearchConfig.model
        if (def == null || model.isBlank()) null else def to model
    },
) : ProviderNativeSearch {

    override fun supports(): Boolean {
        val (def, model) = source() ?: return false
        if (!def.isUsable) return false
        if (def.kind == ProviderKind.OPENAI) return false
        return def.models[model]?.supportsNativeWebSearch == true
    }

    override fun search(query: String, allowed: List<String>, blocked: List<String>): List<WebSearch.Result> {
        val (def, model) = source() ?: error("no active provider for native web search")
        val kind = def.kind
        require(kind != ProviderKind.OPENAI) { "chat completions has no server-side web search" }
        val body = nativeRequestBody(def, model, kind, query, allowed, blocked)
        val adapter = AdapterFactory.forKind(kind)
        val text = NativeSearchTransport.post(adapter, def, body)
        val results = parseNativeResults(kind, text)
        if (results.isEmpty()) error("provider native web search returned no citations")
        return results
    }

    companion object {
        private const val SEARCH_PROMPT =
            "Use the web_search tool to research the query below, then reply with a Sources list of markdown links. Query: "

        fun nativeRequestBody(
            def: ProviderDefinition,
            model: String,
            kind: ProviderKind,
            query: String,
            allowed: List<String>,
            blocked: List<String>,
        ): String {
            val base = ChatRequest(
                model = model,
                messages = listOf(ApiMessage(role = "user", content = SEARCH_PROMPT + query)),
                stream = false,
            )
            val encoded = AdapterFactory.forKind(kind).encodeRequest(base, def)
            val root = Json.parseToJsonElement(encoded).jsonObject
            val merged = JsonObject(root + ("tools" to nativeSearchTool(kind, allowed, blocked)))
            return Json.encodeToString(JsonObject.serializer(), merged)
        }

        fun nativeSearchTool(kind: ProviderKind, allowed: List<String>, blocked: List<String>): JsonArray {
            val tool = buildJsonObject {
                if (kind == ProviderKind.ANTHROPIC) {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                    put("max_uses", 3)
                    if (allowed.isNotEmpty()) putJsonArray("allowed_domains") { allowed.forEach { add(it) } }
                    if (blocked.isNotEmpty()) putJsonArray("blocked_domains") { blocked.forEach { add(it) } }
                } else {
                    put("type", "web_search")
                    if (allowed.isNotEmpty()) {
                        putJsonObject("filters") {
                            putJsonArray("allowed_domains") { allowed.forEach { add(it) } }
                        }
                    }
                }
            }
            return JsonArray(listOf(tool))
        }

        fun parseNativeResults(kind: ProviderKind, payload: String): List<WebSearch.Result> {
            val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return emptyList()
            return if (kind == ProviderKind.ANTHROPIC) parseAnthropic(root) else parseResponses(root)
        }

        private fun parseResponses(root: JsonObject): List<WebSearch.Result> {
            val output = root["output"] as? JsonArray ?: return emptyList()
            val results = ArrayList<WebSearch.Result>()
            for (item in output) {
                val obj = item as? JsonObject ?: continue
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "message") continue
                val content = obj["content"] as? JsonArray ?: continue
                for (part in content) {
                    val block = part as? JsonObject ?: continue
                    if (block["type"]?.jsonPrimitive?.contentOrNull != "output_text") continue
                    val annotations = block["annotations"] as? JsonArray ?: continue
                    for (annotation in annotations) {
                        val ann = annotation as? JsonObject ?: continue
                        if (ann["type"]?.jsonPrimitive?.contentOrNull != "url_citation") continue
                        val url = ann["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.isBlank()) continue
                        results += WebSearch.Result(
                            title = ann["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            url = url,
                            snippet = "",
                        )
                    }
                }
            }
            return results
        }

        private fun parseAnthropic(root: JsonObject): List<WebSearch.Result> {
            val content = root["content"] as? JsonArray ?: return emptyList()
            val results = ArrayList<WebSearch.Result>()
            for (part in content) {
                val block = part as? JsonObject ?: continue
                if (block["type"]?.jsonPrimitive?.contentOrNull != "web_search_tool_result") continue
                val entries = block["content"] as? JsonArray ?: continue
                for (entry in entries) {
                    val item = entry as? JsonObject ?: continue
                    if (item["type"]?.jsonPrimitive?.contentOrNull != "web_search_result") continue
                    val url = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (url.isBlank()) continue
                    results += WebSearch.Result(
                        title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        url = url,
                        snippet = "",
                    )
                }
            }
            return results
        }
    }
}

internal object NativeSearchTransport {
    fun post(adapter: WireAdapter, def: ProviderDefinition, body: String): String {
        val conn = (java.net.URL(adapter.endpointUrl(def.baseUrl)).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            adapter.authHeader(def.apiKey)?.let { (k, v) -> setRequestProperty(k, v) }
            adapter.extraHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
            def.httpHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            WebFetchPolicy.apply(this)
            outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(java.io.BufferedReader::readText).orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("native web search HTTP $code: ${text.take(200)}")
        return text
    }
}