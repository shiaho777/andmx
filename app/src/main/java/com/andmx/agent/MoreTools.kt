package com.andmx.agent

import android.content.Context
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
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
import java.net.URLEncoder

/** list_dir: list the entries of a directory in the sandbox. */
class ListDirTool(context: Context) : Tool {
    private val fs = GuestFs(ProotRuntime(context))
    override val name = "list_dir"
    override val description = "列出 Linux 沙箱中某个目录下的文件与子目录。"
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "目录路径,默认 /root") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content ?: "/root"
        return runCatching {
            val entries = fs.list(path)
            ToolResult(if (entries.isEmpty()) "(空目录)" else entries.joinToString("\n"))
        }.getOrElse { ToolResult("列目录失败: ${it.message}", isError = true) }
    }
}

/** web_search: query DuckDuckGo and return the top results (title/url/snippet).
 *
 * If a [NetworkPolicy] is provided, the search engine host is checked against
 * the policy before making a request.
 */
class WebSearchTool(
    private val networkPolicy: com.andmx.exec.policy.NetworkPolicy = com.andmx.exec.policy.NetworkPolicy.PERMISSIVE,
) : Tool {
    override val name = "web_search"
    override val description = "用 DuckDuckGo 联网搜索,返回前若干条结果(标题/链接/摘要)。"
    override val risk = ToolRisk.NETWORK
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string"); put("description", "搜索关键词") }
        }
        putJsonArray("required") { add("query") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = args["query"]?.jsonPrimitive?.content ?: return@withContext ToolResult("缺少参数 query", isError = true)

        // ── NetworkPolicy check (Codex parity) ──
        val searchUrl = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val policyDecision = networkPolicy.checkUrl(searchUrl)
        if (policyDecision.isDenied) {
            return@withContext ToolResult("网络策略已阻止搜索引擎访问", isError = true)
        }

        runCatching {
            val html = fetch(searchUrl)
            val results = WebSearch.parse(html)
            if (results.isEmpty()) ToolResult("(无结果)")
            else ToolResult(results.take(8).joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" })
        }.getOrElse { ToolResult("搜索失败: ${it.message}", isError = true) }
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AndMX")
        }
        return conn.inputStream.bufferedReader().use(BufferedReader::readText).also { conn.disconnect() }
    }
}

/** Pure parser for DuckDuckGo HTML results — unit-testable. */
object WebSearch {
    data class Result(val title: String, val url: String, val snippet: String)

    private val anchor = Regex("(?is)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
    private val snippet = Regex("(?is)<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>")

    fun parse(html: String): List<Result> {
        val titles = anchor.findAll(html).toList()
        val snippets = snippet.findAll(html).map { strip(it.groupValues[1]) }.toList()
        return titles.mapIndexed { i, m ->
            Result(
                title = strip(m.groupValues[2]),
                url = decodeDdg(m.groupValues[1]),
                snippet = snippets.getOrElse(i) { "" },
            )
        }.filter { it.title.isNotBlank() }
    }

    private fun strip(s: String) = HtmlExtractor.toText(s).replace("\n", " ").trim()

    private fun decodeDdg(href: String): String {
        // DDG wraps links like //duckduckgo.com/l/?uddg=<encoded>
        val key = "uddg="
        val i = href.indexOf(key)
        if (i < 0) return if (href.startsWith("//")) "https:$href" else href
        val enc = href.substring(i + key.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(enc, "UTF-8") }.getOrDefault(href)
    }
}
