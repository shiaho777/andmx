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
import java.net.URL

/**
 * browse: fetch a web page and return its readable text, so the agent can do
 * web research. Network call runs on the host (not the sandbox).
 *
 * If a [NetworkPolicy] is provided, the URL's host is checked against the
 * policy before fetching. Denied hosts return an error without making a
 * network request.
 */
class BrowseTool(
    private val networkPolicy: NetworkPolicy = NetworkPolicy.PERMISSIVE,
    /**
     * Called with the (resolved) URL right after a page is fetched successfully,
     * so the UI can mirror the agent's browsing in the in-app browser pane
     * (Codex parity: agent browses → user sees the same page preview). No-op by
     * default so the tool stays usable without a UI host.
     */
    private val onBrowseUrl: (String) -> Unit = {},
) : Tool {
    override val name = "browse"
    override val description = "打开一个 https 网址,返回页面的可读正文(已去除 HTML 标签),用于联网检索资料。用户会同时在内置浏览器里看到你正在浏览的页面。"
    override val risk = ToolRisk.NETWORK
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") { put("type", "string"); put("description", "要抓取的网址(https)") }
        }
        putJsonArray("required") { add("url") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = args["url"]?.jsonPrimitive?.content ?: return@withContext ToolResult("缺少参数 url", isError = true)
        val url = if (raw.startsWith("http")) raw else "https://$raw"

        // ── NetworkPolicy check (Codex parity) ──
        val policyDecision = networkPolicy.checkUrl(url)
        if (policyDecision.isDenied) {
            return@withContext ToolResult(
                "网络策略已阻止访问: ${policyDecision.matchedRule?.host ?: url}\n原因: ${policyDecision.matchedRule?.justification ?: "域名不在允许列表中"}",
                isError = true,
            )
        }

        runCatching {
            val html = fetch(url)
            val title = HtmlExtractor.title(html)
            val text = HtmlExtractor.toText(html)
            // Mirror the resolved URL to the in-app browser pane so the user can
            // preview the exact page the agent just read (Codex parity).
            onBrowseUrl(url)
            buildString {
                if (title != null) appendLine("# $title")
                appendLine(url)
                appendLine()
                append(text)
            }.take(12_000)
        }.map { ToolResult(it) }.getOrElse { ToolResult("抓取失败: ${it.message}", isError = true) }
    }

    private fun fetch(url: String): String {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AndMX")
            }
            when (conn.responseCode) {
                in 300..399 -> { current = conn.getHeaderField("Location") ?: error("重定向缺少 Location"); conn.disconnect() }
                in 200..299 -> return conn.inputStream.bufferedReader().use(BufferedReader::readText).also { conn.disconnect() }
                else -> { val c = conn.responseCode; conn.disconnect(); error("HTTP $c") }
            }
        }
        error("重定向过多")
    }
}
