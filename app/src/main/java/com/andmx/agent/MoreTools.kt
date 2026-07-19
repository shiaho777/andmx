package com.andmx.agent

import android.content.Context
import com.andmx.workspace.WorkspaceAccess
import com.andmx.workspace.WorkspaceIndex
import com.andmx.web.HtmlExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    private val access = WorkspaceAccess(context)
    override val name = "list_dir"
    override val description = "列出当前工作区某个目录下的文件与子目录。"
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "目录路径,默认当前项目目录") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content ?: access.guestCwd()
        return runCatching {
            val entries = access.list(path)
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
        val query = args["query"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult("缺少参数 query", isError = true)

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
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AndMX")
        }
        return conn.inputStream.bufferedReader().use(BufferedReader::readText).also { conn.disconnect() }
    }
}

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
        val key = "uddg="
        val i = href.indexOf(key)
        if (i < 0) return if (href.startsWith("//")) "https:$href" else href
        val enc = href.substring(i + key.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(enc, "UTF-8") }.getOrDefault(href)
    }
}

class GrepTool(context: Context) : Tool {
    private val appContext = context.applicationContext
    private val access = WorkspaceAccess(context)
    override val name = "grep"
    override val description =
        "在工作区内用正则搜索文件内容。优先于 shell 里手写 grep/find。" +
            "返回匹配行(路径:行号:内容)。大结果会截断。"
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") { put("type", "string"); put("description", "正则或固定字符串") }
            putJsonObject("path") { put("type", "string"); put("description", "搜索起点，默认当前项目根") }
            putJsonObject("glob") { put("type", "string"); put("description", "可选文件过滤，如 *.kt") }
            putJsonObject("case_insensitive") { put("type", "boolean"); put("description", "忽略大小写"); put("default", false) }
            putJsonObject("max_results") { put("type", "integer"); put("description", "最多返回行数"); put("default", 80) }
        }
        putJsonArray("required") { add("pattern") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val pattern = args["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少参数 pattern", isError = true)
        val path = args["path"]?.jsonPrimitive?.content ?: access.guestCwd()
        val glob = args["glob"]?.jsonPrimitive?.content
        val fileType = args["file_type"]?.jsonPrimitive?.content
            ?: args["type"]?.jsonPrimitive?.content
        val ignoreCase = args["case_insensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
            || args["-i"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        val multiline = args["multiline"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        val outputMode = args["output_mode"]?.jsonPrimitive?.content ?: "content"
        val max = (args["max_results"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: args["head_limit"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: 80).let { if (it == 0) 400 else it.coerceIn(1, 400) }
        val offset = (args["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val before = args["before_context"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: args["-B"]?.jsonPrimitive?.content?.toIntOrNull()
        val after = args["after_context"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: args["-A"]?.jsonPrimitive?.content?.toIntOrNull()
        val context = args["context"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: args["-C"]?.jsonPrimitive?.content?.toIntOrNull()
        val showLine = args["-n"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() != false
        val flags = buildString {
            if (outputMode == "content" && showLine) append("n")
            append("H")
            if (ignoreCase) append("i")
            append("I")
        }
        val escaped = pattern.replace("'", "'\''")
        val globFlag = buildString {
            if (!glob.isNullOrBlank()) {
                val g = glob.replace("'", "'\''")
                append(" --glob '$g'")
            }
            if (!fileType.isNullOrBlank()) {
                val t = fileType.replace("'", "'\''")
                append(" --type '$t'")
            }
        }
        val ctxFlag = buildString {
            if (context != null && context > 0) append(" -C $context")
            else {
                if (before != null && before > 0) append(" -B $before")
                if (after != null && after > 0) append(" -A $after")
            }
            if (multiline) append(" -U --multiline-dotall")
        }
        val modeFlag = when (outputMode) {
            "files_with_matches" -> " -l"
            "count" -> " -c"
            else -> ""
        }
        val hostRoot = access.hostPath
        if (!access.isRemote && !hostRoot.isNullOrBlank()) {
            val resolvedGuest = access.resolvePath(path)
            val guestMount = "/root/project"
            val hostFilter = when {
                resolvedGuest == guestMount || resolvedGuest == hostRoot -> hostRoot
                resolvedGuest.startsWith("$guestMount/") ->
                    hostRoot.trimEnd('/') + "/" + resolvedGuest.removePrefix("$guestMount/")
                resolvedGuest.startsWith(hostRoot.trimEnd('/') + "/") || resolvedGuest == hostRoot ->
                    resolvedGuest
                else -> null
            }
            if (hostFilter != null) {
                val hits = WorkspaceIndex.get(appContext).grepIndexed(
                    root = hostRoot,
                    pattern = pattern,
                    pathFilter = hostFilter,
                    glob = glob ?: fileType?.let { "*.$it" },
                    ignoreCase = ignoreCase,
                    maxResults = max,
                    offset = offset,
                    outputMode = outputMode,
                )
                if (hits != null) {
                    val out = hits.joinToString("\n")
                    return if (out.isBlank()) ToolResult("(无匹配)")
                    else ToolResult(out.take(16_000))
                }
            }
        }

        val target = access.resolvePath(path).replace("'", "'\''")
        val headN = max + offset
        val cmd = "rg -$flags$modeFlag$globFlag$ctxFlag --max-count 20 -m $headN -- '$escaped' '$target' 2>/dev/null || " +
            "grep -R$flags --include='${glob ?: '*'}' -e '$escaped' '$target' 2>/dev/null | head -n $headN"
        val res = access.executeShell(cmd, cwd = access.guestCwd())
        if (res.error != null) return ToolResult("搜索失败: ${res.error}", isError = true)
        var lines = res.stdout.trim().lines().filter { it.isNotBlank() }
        if (offset > 0 && lines.isNotEmpty()) lines = lines.drop(offset)
        lines = lines.take(max)
        val out = lines.joinToString("\n")
        return if (out.isBlank()) ToolResult("(无匹配)")
        else ToolResult(out.take(16_000), isError = res.exitCode !in setOf(0, 1))
    }
}

class GlobTool(context: Context) : Tool {
    private val appContext = context.applicationContext
    private val access = WorkspaceAccess(context)
    override val name = "glob"
    override val description =
        "按 glob 模式列出工作区文件路径。例如 **/*.kt、src/**/Main*.java。"
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") { put("type", "string"); put("description", "glob 模式") }
            putJsonObject("path") { put("type", "string"); put("description", "搜索起点，默认当前项目根") }
            putJsonObject("max_results") { put("type", "integer"); put("description", "最多返回路径数"); put("default", 200) }
        }
        putJsonArray("required") { add("pattern") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val pattern = args["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少参数 pattern", isError = true)
        val path = args["path"]?.jsonPrimitive?.content ?: access.guestCwd()
        val max = (args["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 200).coerceIn(1, 1000)
        val hostRoot = access.hostPath
        if (!access.isRemote && !hostRoot.isNullOrBlank()) {
            val resolvedGuest = access.resolvePath(path)
            val guestMount = "/root/project"
            val searchRoot = when {
                resolvedGuest == guestMount || resolvedGuest == hostRoot -> hostRoot
                resolvedGuest.startsWith("$guestMount/") ->
                    hostRoot.trimEnd('/') + "/" + resolvedGuest.removePrefix("$guestMount/")
                resolvedGuest.startsWith(hostRoot.trimEnd('/') + "/") || resolvedGuest == hostRoot ->
                    resolvedGuest
                else -> null
            }
            if (searchRoot != null) {
                val hits = WorkspaceIndex.get(appContext).globIndexed(
                    root = hostRoot,
                    pattern = if (searchRoot == hostRoot) pattern else {
                        val rel = searchRoot.removePrefix(hostRoot.trimEnd('/') + "/").trim('/')
                        if (rel.isEmpty()) pattern else {
                            val p = pattern.trimStart('/')
                            if (p.startsWith("**/") || p.startsWith("*")) p else "$rel/**/$p"
                        }
                    },
                    max = max,
                )
                if (hits != null) {
                    val filtered = if (searchRoot == hostRoot) hits else {
                        val relRoot = searchRoot.removePrefix(hostRoot.trimEnd('/') + "/").trim('/')
                        hits.filter { it == relRoot || it.startsWith("$relRoot/") }
                    }
                    val out = filtered.take(max).joinToString("\n")
                    return if (out.isBlank()) ToolResult("(无匹配文件)")
                    else ToolResult(out.take(16_000))
                }
            }
        }

        val root = access.resolvePath(path).replace("'", "'\''")
        val pat = pattern.replace("'", "'\''")
        val cmd = "rg --files -g '$pat' '$root' 2>/dev/null | head -n $max || " +
            "find '$root' -type f -name '$pat' 2>/dev/null | head -n $max"
        val res = access.executeShell(cmd, cwd = access.guestCwd())
        if (res.error != null) return ToolResult("glob 失败: ${res.error}", isError = true)
        val out = res.stdout.trim()
        return if (out.isBlank()) ToolResult("(无匹配文件)")
        else ToolResult(out.take(16_000))
    }
}
