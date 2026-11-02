package com.andmx.agent

import com.andmx.agent.permission.WebFetchPreapproved
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private const val TOOL_JSON_SCHEMA = "https://json-schema.org/draft/2020-12/schema"
private const val DEFAULT_WEBFETCH_TIMEOUT_MS = 60_000L
private const val MAX_WEBFETCH_MODEL_BYTES = 100_000
private const val MAX_WEBFETCH_RESPONSE_BYTES = 10 * 1024 * 1024
private const val MAX_MODEL_INPUT_CHARS = 100_000
private const val CACHE_TTL_MS = 15 * 60 * 1000L
private const val CACHE_MAX_BYTES = 50L * 1024L * 1024L
private const val MAX_REDIRECTS = 10
private const val WEBFETCH_USER_AGENT = "ZCode-WebFetch/0.1 (+https://zcode.ai; coding-agent-cli)"

private val WEBFETCH_DESCRIPTION = listOf(
    "Fetches a URL, converts the page to markdown, and answers `prompt` against it using a small fast model.",
    "",
    "- Fails on authenticated/private URLs — use an authenticated MCP tool or `gh` for those instead.",
    "- HTTP is upgraded to HTTPS. Cross-host redirects are returned to you rather than followed; call again with the redirect URL.",
    "- Responses are cached for 15 minutes per URL.",
).joinToString("\n")

private val WEBFETCH_PARAMETERS = buildJsonObject {
    put("\$schema", TOOL_JSON_SCHEMA)
    put("type", "object")
    put("additionalProperties", false)
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

private val RETRY_AFTER_DIGITS = Regex("^[0-9]{1,6}$")

private val HTTP_STATUS_TEXT = mapOf(
    100 to "Continue",
    101 to "Switching Protocols",
    102 to "Processing",
    103 to "Early Hints",
    200 to "OK",
    201 to "Created",
    202 to "Accepted",
    203 to "Non-Authoritative Information",
    204 to "No Content",
    205 to "Reset Content",
    206 to "Partial Content",
    207 to "Multi-Status",
    208 to "Already Reported",
    226 to "IM Used",
    300 to "Multiple Choices",
    301 to "Moved Permanently",
    302 to "Found",
    303 to "See Other",
    304 to "Not Modified",
    305 to "Use Proxy",
    307 to "Temporary Redirect",
    308 to "Permanent Redirect",
    400 to "Bad Request",
    401 to "Unauthorized",
    402 to "Payment Required",
    403 to "Forbidden",
    404 to "Not Found",
    405 to "Method Not Allowed",
    406 to "Not Acceptable",
    407 to "Proxy Authentication Required",
    408 to "Request Timeout",
    409 to "Conflict",
    410 to "Gone",
    411 to "Length Required",
    412 to "Precondition Failed",
    413 to "Payload Too Large",
    414 to "URI Too Long",
    415 to "Unsupported Media Type",
    416 to "Range Not Satisfiable",
    417 to "Expectation Failed",
    418 to "I'm a Teapot",
    421 to "Misdirected Request",
    422 to "Unprocessable Entity",
    423 to "Locked",
    424 to "Failed Dependency",
    425 to "Too Early",
    426 to "Upgrade Required",
    428 to "Precondition Required",
    429 to "Too Many Requests",
    431 to "Request Header Fields Too Large",
    451 to "Unavailable For Legal Reasons",
    500 to "Internal Server Error",
    501 to "Not Implemented",
    502 to "Bad Gateway",
    503 to "Service Unavailable",
    504 to "Gateway Timeout",
    505 to "HTTP Version Not Supported",
    506 to "Variant Also Negotiates",
    507 to "Insufficient Storage",
    508 to "Loop Detected",
    509 to "Bandwidth Limit Exceeded",
    510 to "Not Extended",
    511 to "Network Authentication Required",
)

class WebFetchHttpResponse(
    val status: Int,
    val statusText: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface WebFetchClient {
    fun get(url: String): WebFetchHttpResponse
}

class HttpWebFetchClient : WebFetchClient {
    override fun get(url: String): WebFetchHttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = DEFAULT_WEBFETCH_TIMEOUT_MS.toInt()
            conn.readTimeout = DEFAULT_WEBFETCH_TIMEOUT_MS.toInt()
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", WEBFETCH_USER_AGENT)
            conn.setRequestProperty("Accept", "text/markdown, text/html, */*")
            WebFetchPolicy.apply(conn)
            val status = conn.responseCode
            val statusText = conn.responseMessage.orEmpty()
            val headers = LinkedHashMap<String, String>()
            for ((key, values) in conn.headerFields) {
                if (key.isNullOrBlank() || values.isNullOrEmpty()) continue
                headers[key] = values.joinToString(", ")
            }
            val body = if (status in 200..299) {
                conn.inputStream?.use { readCapped(it) } ?: ByteArray(0)
            } else {
                ByteArray(0)
            }
            return WebFetchHttpResponse(status, statusText, headers, body)
        } finally {
            conn.disconnect()
        }
    }
}

class CachedPage(
    val content: String,
    val contentType: String,
)

class WebFetchContentCache(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val page: CachedPage, val expiresAt: Long, val sizeBytes: Long)

    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(key: String): CachedPage? {
        val entry = map[key] ?: return null
        if (entry.expiresAt <= now()) {
            totalBytes -= entry.sizeBytes
            map.remove(key)
            return null
        }
        return entry.page
    }

    @Synchronized
    fun put(key: String, page: CachedPage) {
        val size = page.content.toByteArray(Charsets.UTF_8).size.toLong()
        if (size > CACHE_MAX_BYTES) return
        map.remove(key)?.let { totalBytes -= it.sizeBytes }
        map[key] = Entry(page, now() + CACHE_TTL_MS, size)
        totalBytes += size
        prune()
    }

    @Synchronized
    fun count(): Int = map.size

    @Synchronized
    fun clear(): Int {
        val removed = map.size
        map.clear()
        totalBytes = 0
        return removed
    }

    private fun prune() {
        val clock = now()
        val expired = map.filterValues { it.expiresAt <= clock }.keys.toList()
        for (key in expired) {
            map.remove(key)?.let { totalBytes -= it.sizeBytes }
        }
        while (totalBytes > CACHE_MAX_BYTES && map.isNotEmpty()) {
            val eldest = map.entries.first()
            totalBytes -= eldest.value.sizeBytes
            map.remove(eldest.key)
        }
    }

    companion object {
        val shared: WebFetchContentCache = WebFetchContentCache()
    }
}

class WebFetchTool(
    private val networkPolicy: NetworkPolicy = NetworkPolicy.PERMISSIVE,
    private val cache: WebFetchContentCache = WebFetchContentCache.shared,
    private val client: WebFetchClient = HttpWebFetchClient(),
    private val summarizer: (suspend (content: String, prompt: String) -> String?)? = null,
) : Tool {
    override val name = "WebFetch"
    override val shouldDefer = true
    override val description = WEBFETCH_DESCRIPTION
    override val parameters = WEBFETCH_PARAMETERS
    override val risk = ToolRisk.NETWORK
    override val timeoutMs = DEFAULT_WEBFETCH_TIMEOUT_MS
    override val concurrentSafe = true
    override val destructive = false
    override val readOnly = true
    override val needsApproval = true
    override val permissionName = "webfetch"
    override val sideEffectScope = "network"
    override val riskLevel = "medium"
    override val maxOutputBytes = MAX_WEBFETCH_MODEL_BYTES

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            fetch(args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult(error.message ?: "WebFetch request failed", isError = true)
        }
    }

    private suspend fun fetch(args: JsonObject): ToolResult {
        val url = args.text("url")
        val prompt = args.text("prompt")
        if (url == null || prompt == null) {
            return ToolResult("url and prompt are required", isError = true)
        }
        val normalized = try {
            WebFetchGuard.normalizeUrl(url)
        } catch (blocked: WebFetchGuard.Blocked) {
            return ToolResult(blocked.reason, isError = true)
        }
        if (WebFetchPreapproved.containsEncodedDotOrSlash(url) || WebFetchPreapproved.containsEncodedDotOrSlash(normalized.toString())) {
            return ToolResult("WebFetch rejects encoded dot or slash in the URL", isError = true)
        }
        val denied = networkPolicy.checkUrl(normalized.toString())
        if (denied.isDenied) {
            return ToolResult("Access to ${normalized.host ?: url} is denied by network policy", isError = true)
        }
        val cached = cache.get(url)
        val page = if (cached != null) {
            cached
        } else {
            when (val fetched = load(normalized)) {
                is FetchStop.Page -> fetched.page.also { cache.put(url, it) }
                is FetchStop.Redirect -> return ToolResult(formatRedirect(prompt, fetched))
                is FetchStop.HttpError -> return ToolResult(formatHttpError(fetched))
                is FetchStop.Failed -> return ToolResult(fetched.message, isError = true)
            }
        }
        return ToolResult(present(url, prompt, page))
    }

    private fun load(start: URI): FetchStop {
        var current = start
        var followed = 0
        while (true) {
            try {
                WebFetchGuard.assertLiteralEgress(current)
            } catch (blocked: WebFetchGuard.Blocked) {
                return FetchStop.Failed(blocked.reason)
            }
            if (WebFetchPreapproved.containsEncodedDotOrSlash(current.toString())) {
                return FetchStop.Failed("WebFetch rejects encoded dot or slash in the URL")
            }
            val decision = networkPolicy.checkUrl(current.toString())
            if (decision.isDenied) {
                return FetchStop.Failed("Access to ${current.host ?: current.toString()} is denied by network policy")
            }
            val response = try {
                client.get(current.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: "unknown"
                val text = if (message.startsWith("WebFetch")) message else "WebFetch request failed: $message"
                return FetchStop.Failed(text)
            }
            if (isRedirectStatus(response.status)) {
                val location = response.header("location")?.trim().orEmpty()
                if (location.isEmpty()) return httpError(response)
                val next = try {
                    WebFetchGuard.resolveRedirect(location, current)
                } catch (blocked: WebFetchGuard.Blocked) {
                    return FetchStop.Failed(blocked.reason)
                }
                if (!WebFetchGuard.isPermittedRedirect(current, next)) {
                    return FetchStop.Redirect(
                        originalUrl = current.toString(),
                        redirectUrl = displayUrl(next),
                        status = response.status,
                        statusText = response.statusText,
                    )
                }
                if (followed >= MAX_REDIRECTS) {
                    return FetchStop.Failed("WebFetch exceeded the safe redirect limit")
                }
                current = next
                followed += 1
                continue
            }
            if (response.status !in 200..299) return httpError(response)
            val contentType = response.header("content-type").orEmpty()
            val content = extractReadable(response.body, contentType)
                ?: return FetchStop.Failed(unsupportedContentType(contentType))
            return FetchStop.Page(CachedPage(content, contentType))
        }
    }

    private suspend fun present(inputUrl: String, prompt: String, page: CachedPage): String {
        val preapproved = WebFetchPreapproved.isPreapprovedUrl(inputUrl)
        if (
            preapproved &&
            page.contentType.lowercase().contains("text/markdown") &&
            page.content.length < MAX_MODEL_INPUT_CHARS
        ) {
            return page.content
        }
        val body = truncateForModel(page.content)
        val summarized = summarizer?.invoke(body, prompt)?.trim()
        if (!summarized.isNullOrEmpty()) return summarized
        return body
    }
}

private sealed class FetchStop {
    class Page(val page: CachedPage) : FetchStop()
    class Redirect(
        val originalUrl: String,
        val redirectUrl: String,
        val status: Int,
        val statusText: String,
    ) : FetchStop()
    class HttpError(val status: Int, val statusText: String, val retryAfter: String?) : FetchStop()
    class Failed(val message: String) : FetchStop()
}

private class WebFetchRequestException(message: String) : Exception(message)

private fun JsonObject.text(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.contentOrNull
}

private fun httpError(response: WebFetchHttpResponse): FetchStop.HttpError {
    val retry = response.header("retry-after")?.trim()?.takeIf { RETRY_AFTER_DIGITS.matches(it) }
    return FetchStop.HttpError(response.status, response.statusText, retry)
}

private fun formatRedirect(prompt: String, redirect: FetchStop.Redirect): String {
    val statusText = httpStatusText(redirect.status, redirect.statusText)
    return listOf(
        "REDIRECT DETECTED: The URL redirects to a different host.",
        "",
        "Original URL: ${redirect.originalUrl}",
        "Redirect URL: ${redirect.redirectUrl}",
        "Status: ${redirect.status} $statusText",
        "",
        "To complete your request, I need to fetch content from the redirected URL. Please use WebFetch again with these parameters:",
        "- url: \"${redirect.redirectUrl}\"",
        "- prompt: \"$prompt\"",
    ).joinToString("\n")
}

private fun formatHttpError(error: FetchStop.HttpError): String {
    val statusText = httpStatusText(error.status, error.statusText)
    val retry = error.retryAfter?.let { "\nRetry-After: $it" }.orEmpty()
    return listOf(
        "The server returned HTTP ${error.status} $statusText.$retry",
        "",
        "The response body was not retrieved. If this URL requires authentication, use an authenticated tool (e.g. `gh` for GitHub, or an MCP-provided fetch tool) instead of WebFetch.",
    ).joinToString("\n")
}

private fun httpStatusText(status: Int, statusText: String): String {
    val trimmed = statusText.trim()
    if (trimmed.isNotEmpty()) return trimmed
    return HTTP_STATUS_TEXT[status] ?: "Unknown Status"
}

private fun isRedirectStatus(status: Int): Boolean =
    status == 301 || status == 302 || status == 303 || status == 307 || status == 308

private fun displayUrl(uri: URI): String {
    if (uri.rawUserInfo.isNullOrEmpty()) return uri.toString()
    val host = uri.host ?: return uri.toString()
    val bracketed = if (host.contains(':')) "[$host]" else host
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    val path = uri.rawPath ?: ""
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    return "${uri.scheme}://$bracketed$port$path$query$fragment"
}

private fun unsupportedContentType(contentType: String): String {
    val mime = contentType.substringBefore(';').trim().lowercase()
    return "Unsupported WebFetch content type: ${mime.ifEmpty { "unknown" }}"
}

private fun extractReadable(body: ByteArray, contentType: String): String? {
    val mime = contentType.substringBefore(';').trim().lowercase()
    if (!isTextLike(mime)) return null
    val text = body.toString(Charsets.UTF_8)
    if (mime == "text/html" || mime == "application/xhtml+xml" || contentType.lowercase().contains("html")) {
        return htmlToMarkdown(text)
    }
    return text.trim()
}

private fun isTextLike(mime: String): Boolean {
    if (mime.isEmpty()) return true
    return mime.startsWith("text/") ||
        mime == "application/json" ||
        mime == "application/xml" ||
        mime == "application/xhtml+xml" ||
        mime == "application/javascript" ||
        mime == "application/x-javascript" ||
        mime.endsWith("+json") ||
        mime.endsWith("+xml")
}

private fun truncateForModel(content: String): String {
    if (content.length <= MAX_MODEL_INPUT_CHARS) return content
    val suffix = "\n\n[WebFetch content truncated before prompt processing]"
    val maxBody = (MAX_MODEL_INPUT_CHARS - suffix.length).coerceAtLeast(0)
    return content.take(maxBody) + suffix
}

private fun htmlToMarkdown(html: String): String {
    var content = html
    content = content.replace(Regex("<!--[\\s\\S]*?-->"), "")
    content = content.replace(Regex("<script\\b[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
    content = content.replace(Regex("<style\\b[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
    content = content.replace(Regex("<noscript\\b[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), "")
    content = content
        .replace(Regex("<h1\\b[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)) { "\n# ${it.groupValues[1]}\n" }
        .replace(Regex("<h2\\b[^>]*>([\\s\\S]*?)</h2>", RegexOption.IGNORE_CASE)) { "\n## ${it.groupValues[1]}\n" }
        .replace(Regex("<h3\\b[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE)) { "\n### ${it.groupValues[1]}\n" }
        .replace(Regex("<h4\\b[^>]*>([\\s\\S]*?)</h4>", RegexOption.IGNORE_CASE)) { "\n#### ${it.groupValues[1]}\n" }
        .replace(Regex("<h5\\b[^>]*>([\\s\\S]*?)</h5>", RegexOption.IGNORE_CASE)) { "\n##### ${it.groupValues[1]}\n" }
        .replace(Regex("<h6\\b[^>]*>([\\s\\S]*?)</h6>", RegexOption.IGNORE_CASE)) { "\n###### ${it.groupValues[1]}\n" }
        .replace(Regex("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)) {
            "[${it.groupValues[2]}](${it.groupValues[1]})"
        }
        .replace(Regex("<li\\b[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)) { "\n- ${it.groupValues[1]}" }
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(?:p|div|section|article|header|footer|tr|table|ul|ol)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
    val lines = decodeHtmlEntities(content).split('\n').map { it.replace(Regex("[ \\t]+"), " ").trim() }
    return lines.filterIndexed { index, line ->
        line.isNotEmpty() || index == 0 || lines[index - 1].isNotEmpty()
    }.joinToString("\n").trim()
}

private fun decodeHtmlEntities(value: String): String {
    return value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)) { match ->
            val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
            codePoint(code) ?: match.value
        }
        .replace(Regex("&#(\\d+);")) { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            codePoint(code) ?: match.value
        }
}

private fun codePoint(code: Int): String? {
    if (code !in 0..0x10FFFF) return null
    return String(Character.toChars(code))
}

private fun readCapped(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (n > MAX_WEBFETCH_RESPONSE_BYTES - total) {
            throw WebFetchRequestException("WebFetch response exceeded the size limit")
        }
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
