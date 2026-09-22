package com.andmx.agent

import com.andmx.exec.policy.NetworkPolicy
import com.andmx.web.HtmlExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI

class BrowseTool(
    private val networkPolicy: NetworkPolicy = NetworkPolicy.PERMISSIVE,
    private val onBrowseUrl: (String) -> Unit = {},
    private val cache: WebFetchCache = WebFetchCache,
    private val summarizer: (suspend (content: String, prompt: String) -> String?)? = null,
) : Tool {
    override val name = "browse"
    override val description =
        "打开一个 https 网址,返回页面的可读正文(已去除 HTML 标签),用于联网检索资料。用户会同时在内置浏览器里看到你正在浏览的页面。" +
            " 带 prompt 时会先用小模型按指令提炼正文;同一 URL 15 分钟内走缓存;私网/本地地址会被拦截。"
    override val risk = ToolRisk.NETWORK
    override val timeoutMs: Long = 90_000
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") { put("type", "string"); put("description", "要抓取的网址(https)") }
            putJsonObject("prompt") { put("type", "string"); put("description", "针对页面内容回答的问题/指令") }
        }
        putJsonArray("required") { add("url") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = args["url"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult("缺少参数 url", isError = true)
        val prompt = args["prompt"]?.jsonPrimitive?.content?.trim().orEmpty()
        val url = try {
            WebFetchGuard.normalizeUrl(if (raw.contains("://")) raw else "https://$raw")
        } catch (b: WebFetchGuard.Blocked) {
            return@withContext ToolResult("已拦截: ${b.reason}", isError = true)
        }

        val policyDecision = networkPolicy.checkUrl(url.toString())
        if (policyDecision.isDenied) {
            return@withContext ToolResult(
                "网络策略已阻止访问: ${policyDecision.matchedRule?.host ?: url}\n原因: ${policyDecision.matchedRule?.justification ?: "域名不在允许列表中"}",
                isError = true,
            )
        }

        val cacheKey = url.toString()
        cache.get(cacheKey)?.let { return@withContext ToolResult(it) }

        runCatching {
            val fetched = fetch(url)
            val text = when (fetched) {
                is Fetched.Redirect -> return@runCatching redirectMessage(url, fetched, prompt)
                is Fetched.Page -> {
                    val title = HtmlExtractor.title(fetched.html)
                    val body = HtmlExtractor.toText(fetched.html)
                    onBrowseUrl(fetched.finalUrl.toString())
                    val summarized = if (prompt.isNotBlank() && summarizer != null) {
                        summarizer.invoke(body.take(MAX_MODEL_INPUT_CHARS), prompt)
                    } else null
                    buildString {
                        if (title != null) appendLine("# $title")
                        appendLine(fetched.finalUrl)
                        if (summarized != null) {
                            appendLine()
                            appendLine(summarized)
                        } else {
                            if (prompt.isNotBlank()) {
                                appendLine()
                                appendLine("Prompt: $prompt")
                                appendLine()
                                appendLine("Relevant excerpt for the prompt:")
                            } else {
                                appendLine()
                            }
                            append(body)
                        }
                    }.take(MAX_OUTPUT_CHARS)
                }
            }
            text.also { cache.put(cacheKey, it) }
        }.map { ToolResult(it) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            if (it is WebFetchGuard.Blocked) ToolResult("已拦截: ${it.reason}", isError = true)
            else ToolResult("抓取失败: ${it.message}", isError = true)
        }
    }

    private fun redirectMessage(original: URI, redirect: Fetched.Redirect, prompt: String): String =
        buildString {
            appendLine("REDIRECT DETECTED: The URL redirects to a different host.")
            appendLine()
            appendLine("Original URL: $original")
            appendLine("Redirect URL: ${redirect.location}")
            appendLine("Status: ${redirect.status}")
            appendLine()
            appendLine("To complete your request, I need to fetch content from the redirected URL. Please use WebFetch again with these parameters:")
            appendLine("- url: \"${redirect.location}\"")
            if (prompt.isNotBlank()) appendLine("- prompt: \"$prompt\"")
        }

    private sealed interface Fetched {
        data class Page(val finalUrl: URI, val html: String) : Fetched
        data class Redirect(val location: String, val status: Int) : Fetched
    }

    private fun fetch(url: URI): Fetched {
        var current = url
        repeat(WebFetchGuard.MAX_REDIRECTS) {
            WebFetchGuard.assertLiteralEgress(current)
            val conn = (current.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AndMX")
            }
            when (conn.responseCode) {
                in 300..399 -> {
                    val location = conn.getHeaderField("Location") ?: error("重定向缺少 Location")
                    val code = conn.responseCode
                    conn.disconnect()
                    val next = WebFetchGuard.resolveRedirect(location, current)
                    if (!WebFetchGuard.isPermittedRedirect(current, next)) {
                        return Fetched.Redirect(location = next.toString(), status = code)
                    }
                    current = next
                }
                in 200..299 -> {
                    val html = try {
                        conn.inputStream.bufferedReader().use(BufferedReader::readText)
                            .take(MAX_RESPONSE_CHARS)
                    } finally {
                        conn.disconnect()
                    }
                    return Fetched.Page(finalUrl = current, html = html)
                }
                else -> {
                    val c = conn.responseCode
                    conn.disconnect()
                    error("HTTP $c")
                }
            }
        }
        error("重定向过多")
    }

    companion object {
        const val MAX_OUTPUT_CHARS = 12_000
        const val MAX_MODEL_INPUT_CHARS = 100_000
        const val MAX_RESPONSE_CHARS = 512_000
    }
}
