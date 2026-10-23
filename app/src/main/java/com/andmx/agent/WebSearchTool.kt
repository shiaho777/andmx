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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

private const val TOOL_JSON_SCHEMA = "https://json-schema.org/draft/2020-12/schema"
private const val DEFAULT_TIMEOUT_MS = 60_000L
private const val MAX_OUTPUT_BYTES = 20_000
private const val MAX_SOURCE_LINKS = 20
private const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/?q="

private val WEBSEARCH_MONTHS = listOf(
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
)

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
    }
    putJsonArray("required") { add("query") }
}

private fun buildWebSearchProviderDescription(now: Calendar): String {
    val currentMonth = "${WEBSEARCH_MONTHS[now.get(Calendar.MONTH)]} ${now.get(Calendar.YEAR)}"
    return listOf(
        "Search the web. Returns result blocks with titles and URLs. US-only.",
        "",
        "- The current month is $currentMonth — use this when searching for recent information.",
        "- `allowed_domains` / `blocked_domains` filter results.",
        "- After answering from results, end with a \"Sources:\" list of the URLs you used as markdown links.",
    ).joinToString("\n")
}

fun formatWebSearchModelContent(query: String, results: List<WebSearch.Result>): String {
    val seen = HashSet<String>()
    val links = ArrayList<WebSearch.Result>(minOf(results.size, MAX_SOURCE_LINKS))
    for (result in results) {
        val key = result.url.lowercase()
        if (key.isEmpty() || !seen.add(key)) continue
        links += result
        if (links.size == MAX_SOURCE_LINKS) break
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
    private val now: () -> Calendar = { Calendar.getInstance() },
) : Tool {
    override val name = "WebSearch"
    override val description: String
        get() = buildWebSearchProviderDescription(now())
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
        val results = WebSearch.parse(html).filter { result ->
            result.url.isNotBlank() &&
                (allowed.isEmpty() || allowed.any { domainMatches(result.url, it) }) &&
                (blocked.isEmpty() || blocked.none { domainMatches(result.url, it) })
        }
        return ToolResult(formatWebSearchModelContent(query, results))
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
