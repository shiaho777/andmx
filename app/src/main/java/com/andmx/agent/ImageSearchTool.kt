package com.andmx.agent

import com.andmx.exec.policy.NetworkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

private const val TOOL_JSON_SCHEMA = "https://json-schema.org/draft/2020-12/schema"
private const val DEFAULT_TIMEOUT_MS = 60_000L
private const val MAX_OUTPUT_BYTES = 20_000
private const val DDG_HOME_ENDPOINT = "https://duckduckgo.com/?iax=images&ia=images&q="
private const val DDG_JSON_ENDPOINT = "https://duckduckgo.com/i.js?l=us-en&o=json&f=,,,&q="

private val IMAGESEARCH_DESCRIPTION = listOf(
    "Search for images on the web. Returns JSON with title, imageUrl, thumbnailUrl, sourceUrl, width and height for each result.",
    "",
    "- Uses a configured image search API (Bing Image / Brave Images) when available, otherwise falls back to DuckDuckGo.",
    "- `count` defaults to 5, max 20. `safeSearch` is off/moderate/strict (default moderate).",
    "- When showing images to the user, reference imageUrl and credit sourceUrl.",
).joinToString("\n")

private val IMAGESEARCH_PARAMETERS = buildJsonObject {
    put("\$schema", TOOL_JSON_SCHEMA)
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        putJsonObject("query") {
            put("type", "string")
            put("minLength", 2)
            put("description", "The image search query to use")
        }
        putJsonObject("count") {
            put("type", "integer")
            put("description", "Number of image results to return (default 5, max 20)")
        }
        putJsonObject("safeSearch") {
            put("type", "string")
            putJsonArray("enum") { add("off"); add("moderate"); add("strict") }
            put("description", "Safe-search level (default moderate)")
        }
    }
    putJsonArray("required") { add("query") }
}

class ImageSearchTool(
    private val networkPolicy: NetworkPolicy = NetworkPolicy.PERMISSIVE,
    private val client: WebSearchClient = HttpWebSearchClient(),
    private val apiConfig: () -> ImageSearchApiConfig = { ImageSearchConfig.snapshot() },
    private val apiClient: WebSearchApiClient = HttpWebSearchApiClient(),
) : Tool {
    override val name = "ImageSearch"
    override val shouldDefer = true
    override val description: String = IMAGESEARCH_DESCRIPTION
    override val parameters = IMAGESEARCH_PARAMETERS
    override val risk = ToolRisk.NETWORK
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val sideEffectScope = "network"
    override val riskLevel = "low"
    override val permissionName = "imagesearch"
    override val timeoutMs = DEFAULT_TIMEOUT_MS
    override val maxOutputBytes = MAX_OUTPUT_BYTES

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            search(args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult(error.message ?: "ImageSearch request failed", isError = true)
        }
    }

    private fun search(args: JsonObject): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult("query is required", isError = true)
        if (query.length < 2) {
            return ToolResult("query must be at least 2 characters", isError = true)
        }
        val count = ImageSearchParsing.clampCount((args["count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull())
        val safeSearch = ImageSearchParsing.safeSearchParam((args["safeSearch"] as? JsonPrimitive)?.contentOrNull)

        val config = runCatching { apiConfig() }.getOrDefault(ImageSearchApiConfig())
        val apiResults = searchViaApi(config, query, count, safeSearch)
        if (apiResults != null) {
            return ToolResult(ImageSearchParsing.toJson(query, apiResults))
        }

        val ddgResults = searchViaDdg(query, count, safeSearch)
            ?: return ToolResult("ImageSearch request failed: no reachable image search endpoint", isError = true)
        return ToolResult(ImageSearchParsing.toJson(query, ddgResults))
    }

    private fun searchViaApi(
        config: ImageSearchApiConfig,
        query: String,
        count: Int,
        safeSearch: String,
    ): List<ImageSearchResult>? {
        val request = ImageSearchParsing.requestFor(config.provider, config, query, count, safeSearch) ?: return null
        if (networkPolicy.checkUrl(request.url).isDenied) return null
        val payload = runCatching {
            apiClient.request(request.url, request.method, request.headers, request.body)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        } ?: return null
        val parsed = ImageSearchParsing.normalize(ImageSearchParsing.parseApi(config.provider, payload), count)
        return parsed.takeIf { it.isNotEmpty() }
    }

    private fun searchViaDdg(query: String, count: Int, safeSearch: String): List<ImageSearchResult>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val homeUrl = DDG_HOME_ENDPOINT + encoded
        if (networkPolicy.checkUrl(homeUrl).isDenied) return null
        val html = fetchOrNull(homeUrl) ?: return null
        val vqd = ImageSearchParsing.extractVqd(html)
        if (vqd != null) {
            val safeFlag = if (safeSearch == "off") "-1" else "1"
            val jsonUrl = "$DDG_JSON_ENDPOINT$encoded&vqd=${URLEncoder.encode(vqd, "UTF-8")}&p=$safeFlag"
            if (!networkPolicy.checkUrl(jsonUrl).isDenied) {
                val payload = fetchOrNull(jsonUrl)
                if (payload != null) {
                    val parsed = ImageSearchParsing.normalize(ImageSearchParsing.parseDdgJson(payload), count)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
        return ImageSearchParsing.normalize(ImageSearchParsing.parseDdgHtml(html), count)
    }

    private fun fetchOrNull(url: String): String? = runCatching { client.fetch(url) }.getOrElse { error ->
        if (error is CancellationException) throw error
        null
    }
}
