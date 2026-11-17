package com.andmx.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.net.URLEncoder

data class ImageSearchResult(
    val title: String,
    val imageUrl: String,
    val thumbnailUrl: String? = null,
    val sourceUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class ImageSearchApiConfig(val provider: String = "", val apiKey: String = "")

object ImageSearchConfig {
    @Volatile var apiProvider: String = ""
    @Volatile var apiKey: String = ""

    fun snapshot(): ImageSearchApiConfig =
        ImageSearchApiConfig(apiProvider.trim().lowercase(), apiKey.trim())
}

object ImageSearchParsing {
    const val DEFAULT_COUNT = 5
    const val MAX_COUNT = 20

    private val json = Json { ignoreUnknownKeys = true }
    private val vqdPatterns = listOf(
        Regex("""vqd=["']([\d-]+)["']"""),
        Regex("""vqd=([\d-]+)&"""),
        Regex("""vqd\\?":\\?"([\d-]+)"""),
    )
    private val iurlEntry = Regex("""(?s)"iurl"\s*:\s*"(.*?)"(?=\s*[,}])""")
    private val resultBlock = Regex("""(?s)\{[^{}]*"iurl"\s*:[^{}]*\}""")

    fun clampCount(raw: Int?): Int = (raw ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)

    fun safeSearchParam(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "off", "moderate", "strict" -> raw.trim().lowercase()
        else -> "moderate"
    }

    fun requestFor(provider: String, config: ImageSearchApiConfig, query: String, count: Int, safeSearch: String): ApiRequest? {
        if (config.apiKey.isBlank()) return null
        val encoded = URLEncoder.encode(query, "UTF-8")
        val safe = safeSearchParam(safeSearch)
        return when (provider) {
            "bing" -> ApiRequest(
                url = "https://api.bing.microsoft.com/v7.0/images/search?q=$encoded&count=$count&safeSearch=${safeSearchBing(safe)}",
                method = "GET",
                headers = mapOf("Ocp-Apim-Subscription-Key" to config.apiKey),
                body = null,
            )
            "brave" -> ApiRequest(
                url = "https://api.search.brave.com/res/v1/images/search?q=$encoded&count=$count&safeSearch=$safe",
                method = "GET",
                headers = mapOf("X-Subscription-Token" to config.apiKey),
                body = null,
            )
            else -> null
        }
    }

    data class ApiRequest(val url: String, val method: String, val headers: Map<String, String>, val body: String?)

    fun parseApi(provider: String, payload: String): List<ImageSearchResult> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
        return when (provider) {
            "bing" -> parseArray(root["value"]) { obj ->
                ImageSearchResult(
                    title = obj.text("name").orEmpty(),
                    imageUrl = obj.text("contentUrl").orEmpty(),
                    thumbnailUrl = obj.text("thumbnailUrl"),
                    sourceUrl = obj.text("hostPageUrl"),
                    width = obj.int("width"),
                    height = obj.int("height"),
                )
            }
            "brave" -> parseArray(root["results"]) { obj ->
                val thumbnail = (obj["thumbnail"] as? JsonObject)?.text("src") ?: obj.text("thumbnailUrl")
                ImageSearchResult(
                    title = obj.text("title").orEmpty(),
                    imageUrl = (obj["properties"] as? JsonObject)?.text("url") ?: obj.text("url").orEmpty(),
                    thumbnailUrl = thumbnail,
                    sourceUrl = obj.text("url"),
                    width = (obj["properties"] as? JsonObject)?.int("width") ?: obj.int("width"),
                    height = (obj["properties"] as? JsonObject)?.int("height") ?: obj.int("height"),
                )
            }
            else -> emptyList()
        }
    }

    fun parseDdgHtml(html: String): List<ImageSearchResult> {
        val fromJson = parseEmbeddedJson(html)
        if (fromJson.isNotEmpty()) return fromJson
        val tiles = Regex("""(?is)<a[^>]*class="[^"]*tile[^"]*"[^>]*>.*?</a>""").findAll(html)
        val out = ArrayList<ImageSearchResult>()
        for (tile in tiles) {
            val block = tile.value
            val href = Regex("""(?i)href="([^"]+)"""").find(block)?.groupValues?.get(1).orEmpty()
            val params = runCatching {
                href.substringAfter('?', "").split('&').mapNotNull { pair ->
                    val key = pair.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    key to runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                }.toMap()
            }.getOrDefault(emptyMap())
            val imageUrl = params["iurl"].orEmpty()
            if (imageUrl.isBlank()) continue
            out += ImageSearchResult(
                title = params["t"].orEmpty(),
                imageUrl = imageUrl,
                thumbnailUrl = params["purl"],
                sourceUrl = params["rurl"],
                width = params["iw"]?.toIntOrNull(),
                height = params["ih"]?.toIntOrNull(),
            )
        }
        return out
    }

    fun extractVqd(html: String): String? {
        for (pattern in vqdPatterns) {
            val match = pattern.find(html)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) return match
        }
        return null
    }

    fun parseDdgJson(payload: String): List<ImageSearchResult> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
        return parseArray(root["results"]) { obj ->
            ImageSearchResult(
                title = obj.text("title").orEmpty(),
                imageUrl = obj.text("image").orEmpty(),
                thumbnailUrl = obj.text("thumbnail"),
                sourceUrl = obj.text("url"),
                width = obj.int("width"),
                height = obj.int("height"),
            )
        }
    }

    fun normalize(results: List<ImageSearchResult>, limit: Int): List<ImageSearchResult> {
        val seen = HashSet<String>()
        val out = ArrayList<ImageSearchResult>()
        for (result in results) {
            val url = result.imageUrl.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            if (!seen.add(url.lowercase())) continue
            out += result.copy(
                title = result.title.trim(),
                imageUrl = url,
                thumbnailUrl = result.thumbnailUrl?.trim()?.takeIf { it.isNotEmpty() },
                sourceUrl = result.sourceUrl?.trim()?.takeIf { it.isNotEmpty() },
            )
            if (out.size >= limit) break
        }
        return out
    }

    fun toJson(query: String, results: List<ImageSearchResult>): String = buildJsonObject {
        put("query", query)
        put("count", results.size)
        putJsonArray("results") {
            for (result in results) {
                add(buildJsonObject {
                    put("title", result.title)
                    put("imageUrl", result.imageUrl)
                    putNullable("thumbnailUrl", result.thumbnailUrl)
                    putNullable("sourceUrl", result.sourceUrl)
                    put("width", result.width)
                    put("height", result.height)
                })
            }
        }
    }.toString()

    private fun safeSearchBing(safe: String): String = when (safe) {
        "off" -> "Off"
        "strict" -> "Strict"
        else -> "Moderate"
    }

    private fun parseEmbeddedJson(html: String): List<ImageSearchResult> {
        val candidates = ArrayList<String>()
        resultBlock.findAll(html).forEach { candidates += it.value }
        if (candidates.isEmpty()) {
            iurlEntry.findAll(html).forEach { candidates += "{\"iurl\":\"${it.groupValues[1]}\"}" }
        }
        val out = ArrayList<ImageSearchResult>()
        for (candidate in candidates) {
            val obj = runCatching { json.parseToJsonElement(unescapeJsonString(candidate)) as? JsonObject }.getOrNull()
                ?: continue
            val imageUrl = obj.text("iurl").orEmpty()
            if (imageUrl.isBlank()) continue
            out += ImageSearchResult(
                title = obj.text("t").orEmpty(),
                imageUrl = imageUrl,
                thumbnailUrl = obj.text("purl"),
                sourceUrl = obj.text("rurl"),
                width = obj.int("iw"),
                height = obj.int("ih"),
            )
        }
        return out
    }

    private fun unescapeJsonString(raw: String): String = raw
        .replace("\\/", "/")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")

    private fun parseArray(node: JsonElement?, mapper: (JsonObject) -> ImageSearchResult): List<ImageSearchResult> {
        val array = node as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val result = runCatching { mapper(obj) }.getOrNull() ?: return@mapNotNull null
            result.takeIf { it.imageUrl.isNotBlank() }
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
    }

}
