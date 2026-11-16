package com.andmx.agent

import com.andmx.exec.policy.NetworkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

private const val TOOL_JSON_SCHEMA = "https://json-schema.org/draft/2020-12/schema"
private const val DEFAULT_TIMEOUT_MS = 60_000L
private const val MAX_OUTPUT_BYTES = 20_000
private const val MAX_SOURCE_LINKS = 20
private const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/?q="

private val WEBSEARCH_PROVIDER_DESCRIPTION = listOf(
    "Search the web. Returns result blocks with titles and URLs. US-only.",
    "",
    "- The current month is (provided in the conversation below) — use this when searching for recent information.",
    "- `allowed_domains` / `blocked_domains` filter results.",
    "- After answering from results, end with a \"Sources:\" list of the URLs you used as markdown links.",
).joinToString("\n")

private val WEBSEARCH_PARAMETERS = buildJsonObject {
    put("\$schema", TOOL_JSON_SCHEMA)
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        putJsonObject("query") {
            put("type", "string")
            put("minLength", 2)
            put("description", "The search query to use")
        }
        putJsonObject("allowed_domains") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", "Only include search results from these domains")
        }
        putJsonObject("blocked_domains") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", "Never include search results from these domains")
        }
        putJsonObject("max_results") {
            put("type", "integer")
            put("description", "Maximum number of result links to return (default 20, max 20)")
        }
    }
    putJsonArray("required") { add("query") }
}

fun formatWebSearchModelContent(query: String, results: List<WebSearch.Result>, maxLinks: Int = MAX_SOURCE_LINKS): String {
    val cap = maxLinks.coerceIn(1, MAX_SOURCE_LINKS)
    val seen = HashSet<String>()
    val links = ArrayList<WebSearch.Result>(minOf(results.size, cap))
    for (result in results) {
        val key = result.url.lowercase()
        if (key.isEmpty() || !seen.add(key)) continue
        links += result
        if (links.size == cap) break
    }
    val lines = ArrayList<String>()
    lines += "Web search results for query: \"$query\""
    lines += ""
    if (links.isEmpty()) {
        lines += "Links:"
        lines += "- No links found."
    } else {
        lines += "Links:"
        for (source in links) {
            val title = source.title.ifBlank { source.url }
            lines += "- [$title](${source.url})"
        }
    }
    lines += ""
    lines += "REMINDER: You MUST include the sources above in your response to the user using markdown hyperlinks."
    return lines.joinToString("\n").trim()
}

fun interface WebSearchClient {
    fun fetch(url: String): String
}

data class WebSearchApiConfig(val provider: String = "", val apiKey: String = "")

object WebSearchConfig {
    @Volatile var apiProvider: String = ""
    @Volatile var apiKey: String = ""

    fun snapshot(): WebSearchApiConfig =
        WebSearchApiConfig(apiProvider.trim().lowercase(), apiKey.trim())
}

fun interface WebSearchApiClient {
    fun request(url: String, method: String, headers: Map<String, String>, body: String?): String
}

class HttpWebSearchApiClient : WebSearchApiClient {
    override fun request(url: String, method: String, headers: Map<String, String>, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AndMX")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            WebFetchPolicy.apply(this)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("search API HTTP $code: ${text.take(200)}")
        return text
    }
}

object WebSearchApis {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun endpointFor(provider: String, query: String, limit: Int): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return when (provider) {
            "tavily" -> "https://api.tavily.com/search"
            "brave" -> "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$limit"
            "bing" -> "https://api.bing.microsoft.com/v7.0/search?q=$encoded&count=$limit"
            else -> null
        }
    }

    fun requestFor(provider: String, config: WebSearchApiConfig, query: String, limit: Int): ApiRequest? {
        val url = endpointFor(provider, query, limit) ?: return null
        return when (provider) {
            "tavily" -> ApiRequest(
                url = url,
                method = "POST",
                headers = emptyMap(),
                body = buildJsonObject {
                    put("api_key", config.apiKey)
                    put("query", query)
                    put("max_results", limit)
                }.toString(),
            )
            "brave" -> ApiRequest(url, "GET", mapOf("X-Subscription-Token" to config.apiKey), null)
            "bing" -> ApiRequest(url, "GET", mapOf("Ocp-Apim-Subscription-Key" to config.apiKey), null)
            else -> null
        }
    }

    data class ApiRequest(val url: String, val method: String, val headers: Map<String, String>, val body: String?)

    fun parse(provider: String, payload: String): List<WebSearch.Result> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
        return when (provider) {
            "tavily" -> entries(root["results"], "title", "url", "content")
            "brave" -> entries((root["web"] as? JsonObject)?.get("results"), "title", "url", "description")
            "bing" -> entries((root["webPages"] as? JsonObject)?.get("value"), "name", "url", "snippet")
            else -> emptyList()
        }
    }

    private fun entries(node: kotlinx.serialization.json.JsonElement?, titleKey: String, urlKey: String, snippetKey: String): List<WebSearch.Result> {
        val array = node as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj[urlKey]?.jsonPrimitiveText().orEmpty()
            if (url.isBlank()) return@mapNotNull null
            WebSearch.Result(
                title = obj[titleKey]?.jsonPrimitiveText().orEmpty(),
                url = url,
                snippet = obj[snippetKey]?.jsonPrimitiveText().orEmpty(),
            )
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    fun normalize(results: List<WebSearch.Result>, limit: Int): List<WebSearch.Result> {
        val seen = HashSet<String>()
        val out = ArrayList<WebSearch.Result>()
        for (result in results) {
            val url = result.url.trim()
            if (url.isEmpty()) continue
            if (!seen.add(url.lowercase())) continue
            out += WebSearch.Result(result.title.trim(), url, result.snippet.trim())
            if (out.size >= limit) break
        }
        return out
    }
}

class HttpWebSearchClient : WebSearchClient {
    override fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AndMX")
            WebFetchPolicy.apply(this)
        }
        return conn.inputStream.bufferedReader().use(BufferedReader::readText).also { conn.disconnect() }
    }
}

class WebSearchTool(
    private val networkPolicy: NetworkPolicy = NetworkPolicy.PERMISSIVE,
    private val client: WebSearchClient = HttpWebSearchClient(),
    private val nativeSearch: ProviderNativeSearch? = HttpProviderNativeSearch(),
    private val apiConfig: () -> WebSearchApiConfig = { WebSearchConfig.snapshot() },
    private val apiClient: WebSearchApiClient = HttpWebSearchApiClient(),
) : Tool {
    override val name = "WebSearch"
    override val shouldDefer = true
    override val description: String
        get() = WEBSEARCH_PROVIDER_DESCRIPTION
    override val parameters = WEBSEARCH_PARAMETERS
    override val risk = ToolRisk.NETWORK
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val sideEffectScope = "network"
    override val riskLevel = "low"
    override val permissionName = "websearch"
    override val timeoutMs = DEFAULT_TIMEOUT_MS
    override val maxOutputBytes = MAX_OUTPUT_BYTES

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            search(args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult(error.message ?: "WebSearch request failed", isError = true)
        }
    }

    private fun search(args: JsonObject): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult("query is required", isError = true)
        if (query.length < 2) {
            return ToolResult("query must be at least 2 characters", isError = true)
        }
        val allowed = args.domains("allowed_domains")
            ?: return ToolResult("allowed_domains must be an array of strings", isError = true)
        val blocked = args.domains("blocked_domains")
            ?: return ToolResult("blocked_domains must be an array of strings", isError = true)
        if (allowed.isNotEmpty() && blocked.isNotEmpty()) {
            return ToolResult(
                "allowed_domains and blocked_domains cannot both be specified",
                isError = true,
            )
        }
        val limit = ((args["max_results"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: MAX_SOURCE_LINKS)
            .coerceIn(1, MAX_SOURCE_LINKS)
        val config = runCatching { apiConfig() }.getOrDefault(WebSearchApiConfig())
        val apiProvider = WebSearchApis.endpointFor(config.provider, query, 1)?.let { config.provider }
        if (nativeSearch != null || apiProvider != null && config.apiKey.isNotBlank()) {
            val viaLayers = searchViaLayers(query, allowed, blocked, config, apiProvider, limit)
            if (viaLayers != null) return viaLayers
        }
        val submitted = buildString {
            append(query)
            allowed.forEach { append(" site:").append(it) }
            blocked.forEach { append(" -site:").append(it) }
        }
        val searchUrl = SEARCH_ENDPOINT + URLEncoder.encode(submitted, "UTF-8")
        if (networkPolicy.checkUrl(searchUrl).isDenied) {
            return ToolResult("Access to html.duckduckgo.com is denied by network policy", isError = true)
        }
        val html = try {
            client.fetch(searchUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "unknown"
            return ToolResult("WebSearch request failed: $message", isError = true)
        }
        val results = filterDomains(WebSearch.parse(html), allowed, blocked)
        return ToolResult(formatWebSearchModelContent(query, results, limit))
    }

    private fun searchViaLayers(
        query: String,
        allowed: List<String>,
        blocked: List<String>,
        config: WebSearchApiConfig,
        apiProvider: String?,
        limit: Int,
    ): ToolResult? {
        val native = nativeSearch
        if (native != null && runCatching { native.supports() }.getOrDefault(false)) {
            val results = runCatching { native.search(query, allowed, blocked) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    null
                }
            if (results != null) {
                return ToolResult(
                    formatWebSearchModelContent(query, filterDomains(WebSearchApis.normalize(results, limit), allowed, blocked), limit),
                )
            }
        }
        if (apiProvider != null && config.apiKey.isNotBlank()) {
            val request = WebSearchApis.requestFor(apiProvider, config, query, limit)
            if (request != null && !networkPolicy.checkUrl(request.url).isDenied) {
                val payload = runCatching {
                    apiClient.request(request.url, request.method, request.headers, request.body)
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    null
                }
                if (payload != null) {
                    val parsed = WebSearchApis.parse(apiProvider, payload)
                    return ToolResult(
                        formatWebSearchModelContent(query, filterDomains(WebSearchApis.normalize(parsed, limit), allowed, blocked), limit),
                    )
                }
            }
        }
        return null
    }

    private fun filterDomains(results: List<WebSearch.Result>, allowed: List<String>, blocked: List<String>): List<WebSearch.Result> =
        results.filter { result ->
            result.url.isNotBlank() &&
                (allowed.isEmpty() || allowed.any { domainMatches(result.url, it) }) &&
                (blocked.isEmpty() || blocked.none { domainMatches(result.url, it) })
        }
}

private fun JsonObject.domains(key: String): List<String>? {
    val node = this[key] ?: return emptyList()
    val array = node as? JsonArray ?: return null
    val items = ArrayList<String>()
    for (item in array) {
        val text = (item as? JsonPrimitive)?.contentOrNull ?: return null
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) items += trimmed
    }
    return items
}

private fun domainMatches(url: String, domain: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    val needle = domain.lowercase().trim().removePrefix("*.").trimStart('.')
    if (needle.isEmpty()) return false
    return host == needle || host.endsWith(".$needle")
}
