package com.andmx.agent

import com.andmx.exec.policy.NetworkPolicy
import com.andmx.web.HtmlExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class BrowseTool(
    private val networkPolicy: NetworkPolicy = NetworkPolicy.PERMISSIVE,
    private val onBrowseUrl: (String) -> Unit = {},
    private val answerPrompt: (suspend (userMessage: String) -> String)? = null,
) : Tool {
    override val name = "browse"
    override val description =
        "Fetches a URL, converts the page to markdown, and answers `prompt` against it using a small fast model.\n\n" +
            "- Fails on authenticated/private URLs — use an authenticated MCP tool or `gh` for those instead.\n" +
            "- HTTP is upgraded to HTTPS. Cross-host redirects are returned to you rather than followed; call again with the redirect URL.\n" +
            "- Responses are cached for 15 minutes per URL."
    override val risk = ToolRisk.NETWORK
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("format", "uri")
                put("description", "The URL to fetch content from")
            }
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "The prompt to run on the fetched content")
            }
        }
        putJsonArray("required") {
            add("url")
            add("prompt")
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = args["url"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (raw.isBlank()) return@withContext ToolResult("缺少参数 url", isError = true)
        val prompt = args["prompt"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (prompt.isBlank()) return@withContext ToolResult("缺少参数 prompt", isError = true)

        val normalized = try {
            normalizeUrl(raw)
        } catch (t: Throwable) {
            return@withContext ToolResult("Invalid URL: ${t.message ?: raw}", isError = true)
        }

        val hostBlock = assertPublicEgress(normalized)
        if (hostBlock != null) {
            return@withContext ToolResult(hostBlock, isError = true)
        }

        val policyDecision = networkPolicy.checkUrl(normalized)
        if (policyDecision.isDenied) {
            return@withContext ToolResult(
                "网络策略已阻止访问: ${policyDecision.matchedRule?.host ?: normalized}\n原因: ${policyDecision.matchedRule?.justification ?: "域名不在允许列表中"}",
                isError = true,
            )
        }

        runCatching {
            val cacheKey = normalized
            val cached = cacheGet(cacheKey)
            val fetched = cached ?: fetch(normalized).also { result ->
                if (result is FetchOk) cachePut(cacheKey, result)
            }

            when (fetched) {
                is FetchRedirect -> formatRedirect(prompt, normalized, fetched)
                is FetchHttpError -> formatHttpError(fetched)
                is FetchOk -> {
                    onBrowseUrl(normalized)
                    val markdown = if (fetched.contentType.contains("markdown", ignoreCase = true)) {
                        fetched.body
                    } else {
                        htmlToMarkdown(fetched.body)
                    }
                    val clipped = clipForProcessing(markdown)
                    val userMessage = buildProcessingPrompt(clipped.content, prompt, preapproved = false)
                    val answered = if (answerPrompt != null) {
                        runCatching { answerPrompt.invoke(userMessage) }.getOrNull()
                    } else null
                    val result = answered?.trim()?.takeIf { it.isNotEmpty() }
                        ?: extractiveAnswer(clipped.content, prompt)
                    val out = result.ifBlank {
                        "WebFetch completed, but the extraction model returned no text."
                    }
                    out.take(MAX_OUTPUT_CHARS)
                }
            }
        }.map { ToolResult(it) }.getOrElse { ToolResult("抓取失败: ${it.message}", isError = true) }
    }

    private sealed interface FetchOutcome
    private data class FetchOk(
        val body: String,
        val finalUrl: String,
        val status: Int,
        val statusText: String,
        val contentType: String,
    ) : FetchOutcome
    private data class FetchRedirect(
        val originalUrl: String,
        val redirectUrl: String,
        val status: Int,
        val statusText: String,
    ) : FetchOutcome
    private data class FetchHttpError(
        val originalUrl: String,
        val finalUrl: String,
        val status: Int,
        val statusText: String,
        val retryAfter: String? = null,
    ) : FetchOutcome

    private fun fetch(url: String): FetchOutcome {
        var current = url
        val originHost = runCatching { URI(url).host?.lowercase() }.getOrNull()
        repeat(11) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AndMX/WebFetch)")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
            try {
                val code = conn.responseCode
                val statusText = conn.responseMessage.orEmpty()
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location").orEmpty()
                    if (loc.isBlank()) {
                        return FetchHttpError(url, current, code, statusText)
                    }
                    val next = runCatching { URL(URL(current), loc).toString() }.getOrElse {
                        return FetchHttpError(url, current, code, statusText)
                    }
                    val nextHost = runCatching { URI(next).host?.lowercase() }.getOrNull()
                    if (originHost != null && nextHost != null && nextHost != originHost) {
                        return FetchRedirect(url, next, code, statusText)
                    }
                    current = if (next.startsWith("http://")) {
                        "https://" + next.removePrefix("http://")
                    } else next
                    return@repeat
                }
                if (code !in 200..299) {
                    return FetchHttpError(
                        originalUrl = url,
                        finalUrl = current,
                        status = code,
                        statusText = statusText,
                        retryAfter = conn.getHeaderField("Retry-After"),
                    )
                }
                val ctype = conn.contentType.orEmpty()
                val body = (conn.inputStream ?: conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (body.isBlank()) {
                    return FetchHttpError(url, current, code, statusText)
                }
                return FetchOk(
                    body = body,
                    finalUrl = current,
                    status = code,
                    statusText = statusText,
                    contentType = ctype,
                )
            } finally {
                conn.disconnect()
            }
        }
        return FetchHttpError(url, current, 310, "Too many redirects")
    }

    private fun formatRedirect(prompt: String, originalUrl: String, r: FetchRedirect): String {
        val statusText = r.statusText.ifBlank { httpStatusText(r.status) }
        return buildString {
            appendLine("REDIRECT DETECTED: The URL redirects to a different host.")
            appendLine()
            appendLine("Original URL: $originalUrl")
            appendLine("Redirect URL: ${r.redirectUrl}")
            appendLine("Status: ${r.status} $statusText")
            appendLine()
            appendLine("To complete your request, I need to fetch content from the redirected URL. Please use WebFetch again with these parameters:")
            appendLine("- url: \"${r.redirectUrl}\"")
            append("- prompt: \"$prompt\"")
        }
    }

    private fun formatHttpError(e: FetchHttpError): String {
        val statusText = e.statusText.ifBlank { httpStatusText(e.status) }
        val retry = e.retryAfter?.let { "\nRetry-After: $it" }.orEmpty()
        return "The server returned HTTP ${e.status} $statusText.$retry\n\n" +
            "The response body was not retrieved. If this URL requires authentication, use an authenticated tool (e.g. `gh` for GitHub, or an MCP-provided fetch tool) instead of WebFetch."
    }

    private fun normalizeUrl(raw: String): String {
        val withScheme = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.removePrefix("http://").removePrefix("HTTP://")
            raw.contains("://") -> throw IllegalArgumentException("WebFetch only supports http and https URLs")
            else -> "https://$raw"
        }
        val uri = URI(withScheme)
        if (uri.scheme != "https" && uri.scheme != "http") {
            throw IllegalArgumentException("WebFetch only supports http and https URLs")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            throw IllegalArgumentException("WebFetch URLs must not include credentials")
        }
        if (uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("Invalid URL")
        }
        return if (uri.scheme == "http") {
            URI("https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        } else {
            uri.toString()
        }
    }

    private fun assertPublicEgress(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return "Invalid URL"
        if (host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" || host == "::1") {
            return "WebFetch cannot access private or local hostnames"
        }
        if (host.endsWith(".local") || host.endsWith(".internal")) {
            return "WebFetch cannot access private or local hostnames"
        }
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
            return "WebFetch cannot access private or local IP addresses"
        }
        return null
    }

    private fun htmlToMarkdown(html: String): String {
        var t = html
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<script\\b[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style\\b[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<noscript\\b[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), "")
        t = t
            .replace(Regex("<h1\\b[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE), "\n# $1\n")
            .replace(Regex("<h2\\b[^>]*>([\\s\\S]*?)</h2>", RegexOption.IGNORE_CASE), "\n## $1\n")
            .replace(Regex("<h3\\b[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE), "\n### $1\n")
            .replace(Regex("<h4\\b[^>]*>([\\s\\S]*?)</h4>", RegexOption.IGNORE_CASE), "\n#### $1\n")
            .replace(Regex("<h5\\b[^>]*>([\\s\\S]*?)</h5>", RegexOption.IGNORE_CASE), "\n##### $1\n")
            .replace(Regex("<h6\\b[^>]*>([\\s\\S]*?)</h6>", RegexOption.IGNORE_CASE), "\n###### $1\n")
            .replace(Regex("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE), "[$2]($1)")
            .replace(Regex("<li\\b[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE), "\n- $1")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(?:p|div|section|article|tr|table|ul|ol)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (t.isNotBlank()) return t
        val title = HtmlExtractor.title(html)
        val text = HtmlExtractor.toText(html)
        return buildString {
            if (title != null) {
                appendLine("# $title")
                appendLine()
            }
            append(text)
        }
    }

    private data class Clipped(val content: String, val truncated: Boolean)

    private fun clipForProcessing(content: String): Clipped {
        if (content.length <= MAX_PROCESS_CHARS) return Clipped(content, false)
        val note = "\n\n[WebFetch content truncated before prompt processing]"
        val keep = (MAX_PROCESS_CHARS - note.length).coerceAtLeast(0)
        return Clipped(content.take(keep) + note, true)
    }

    companion object {
        private const val MAX_PROCESS_CHARS = 100_000
        private const val MAX_OUTPUT_CHARS = 100_000
        private const val CACHE_TTL_MS = 15L * 60L * 1000L
        private const val CACHE_MAX_BYTES = 50L * 1024L * 1024L

        private data class CacheEntry(val value: FetchOk, val atMs: Long, val sizeBytes: Int)

        private val cache = ConcurrentHashMap<String, CacheEntry>()
        private var cacheBytes = 0L

        private fun cacheGet(url: String): FetchOk? {
            val e = cache[url] ?: return null
            if (System.currentTimeMillis() - e.atMs > CACHE_TTL_MS) {
                cache.remove(url)?.let { cacheBytes -= it.sizeBytes }
                return null
            }
            return e.value
        }

        private fun cachePut(url: String, value: FetchOk) {
            val size = value.body.length.coerceAtLeast(1)
            cache[url]?.let { cacheBytes -= it.sizeBytes }
            cache[url] = CacheEntry(value, System.currentTimeMillis(), size)
            cacheBytes += size
            pruneCache()
        }

        private fun pruneCache() {
            val now = System.currentTimeMillis()
            val it = cache.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value.atMs > CACHE_TTL_MS) {
                    cacheBytes -= e.value.sizeBytes
                    it.remove()
                }
            }
            if (cacheBytes <= CACHE_MAX_BYTES) return
            val ordered = cache.entries.sortedBy { it.value.atMs }
            for (e in ordered) {
                if (cacheBytes <= CACHE_MAX_BYTES) break
                cache.remove(e.key)
                cacheBytes -= e.value.sizeBytes
            }
        }

        fun buildProcessingPrompt(pageContent: String, prompt: String, preapproved: Boolean): String {
            val guidance = if (preapproved) {
                "Provide a concise response based on the content above. Include relevant details, code examples, and documentation excerpts as needed."
            } else {
                listOf(
                    "Provide a concise response based only on the content above. In your response:",
                    " - Enforce a strict 125-character maximum for quotes from any source document. Open Source Software is ok as long as we respect the license.",
                    " - Use quotation marks for exact language from articles; any language outside of the quotation should never be word-for-word the same.",
                    " - You are not a lawyer and never comment on the legality of your own prompts and responses.",
                    " - Never produce or reproduce exact song lyrics.",
                ).joinToString("\n")
            }
            return """
Web page content:
---
$pageContent
---

$prompt

$guidance
""".trimStart()
        }

        private fun extractiveAnswer(markdown: String, prompt: String): String {
            val terms = prompt
                .lowercase()
                .split(Regex("[^a-z0-9\u4e00-\u9fff]+"))
                .filter { it.length >= 2 }
                .distinct()
                .take(12)
            val lines = markdown.lines().filter { it.isNotBlank() }
            if (terms.isEmpty()) {
                return lines.take(40).joinToString("\n").ifBlank { markdown.take(2000) }
            }
            val scored = lines.map { line ->
                val lower = line.lowercase()
                val score = terms.count { lower.contains(it) }
                score to line
            }.filter { it.first > 0 }.sortedByDescending { it.first }
            if (scored.isEmpty()) {
                return lines.take(40).joinToString("\n").ifBlank { markdown.take(2000) }
            }
            return scored.take(30).joinToString("\n") { it.second }
        }

        private fun httpStatusText(code: Int): String = when (code) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "HTTP $code"
        }
    }
}
