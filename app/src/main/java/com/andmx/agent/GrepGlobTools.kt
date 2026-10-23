package com.andmx.agent

import android.content.Context
import com.andmx.workspace.WorkspaceAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

private const val TOOL_JSON_SCHEMA = "https://json-schema.org/draft/2020-12/schema"
private const val DEFAULT_GREP_HEAD_LIMIT = 250
private const val MAX_GLOB_RESULTS = 100
private const val MAX_GREP_MODEL_BYTES = 20_000
private const val MAX_GLOB_MODEL_BYTES = 100_000
private const val MAX_SEARCH_FILE_BYTES = 8L * 1024L * 1024L
private const val GREP_TIMEOUT_MS = 30_000L

private val VCS_DIRECTORIES = setOf(".git", ".svn", ".hg", ".bzr", ".jj", ".sl")

private val GREP_TOOL_DESCRIPTION = """
Content search built on ripgrep. Prefer this over `grep`/`rg` via Bash — results integrate with the permission UI and file links.

- Full regex syntax (e.g. "log.*Error", "function\s+\w+"). Ripgrep, not grep — escape literal braces (`interface\{\}`).
- Filter with `glob` (e.g. "**/*.tsx") or `type` (e.g. "js", "py", "rust").
- `output_mode`: "content" (matching lines), "files_with_matches" (paths only, default), or "count".
- `multiline: true` for patterns that span lines.
""".trimIndent()

private val GLOB_TOOL_DESCRIPTION =
    "Fast file pattern matching. Supports glob patterns like \"**/*.js\" or \"src/**/*.ts\". Returns matching file paths sorted by modification time."

private val TYPE_EXTENSIONS = mapOf(
    "c" to listOf(".c", ".h"),
    "cpp" to listOf(".cc", ".cpp", ".cxx", ".hpp", ".hh", ".hxx"),
    "csharp" to listOf(".cs"),
    "css" to listOf(".css"),
    "go" to listOf(".go"),
    "html" to listOf(".html", ".htm"),
    "java" to listOf(".java"),
    "js" to listOf(".js", ".jsx", ".mjs", ".cjs"),
    "json" to listOf(".json", ".jsonc"),
    "markdown" to listOf(".md", ".markdown"),
    "md" to listOf(".md", ".markdown"),
    "py" to listOf(".py"),
    "python" to listOf(".py"),
    "rs" to listOf(".rs"),
    "rust" to listOf(".rs"),
    "sh" to listOf(".sh", ".bash", ".zsh"),
    "ts" to listOf(".ts", ".tsx", ".mts", ".cts"),
    "tsx" to listOf(".tsx"),
    "txt" to listOf(".txt"),
    "yaml" to listOf(".yaml", ".yml"),
)

private val GREP_PARAMETERS = buildJsonObject {
    put("\$schema", TOOL_JSON_SCHEMA)
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        stringProp(
            "pattern",
            "The regular expression pattern to search for in file contents",
        )
        stringProp(
            "path",
            "File or directory to search in (rg PATH). Defaults to current working directory.",
        )
        stringProp(
            "glob",
            "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob",
        )
        putJsonObject("output_mode") {
            put("type", "string")
            putJsonArray("enum") {
                add("content")
                add("files_with_matches")
                add("count")
            }
            put(
                "description",
                "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), \"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\".",
            )
        }
        numberProp(
            "-B",
            "Number of lines to show before each match (rg -B). Requires output_mode: \"content\", ignored otherwise.",
        )
        numberProp(
            "-A",
            "Number of lines to show after each match (rg -A). Requires output_mode: \"content\", ignored otherwise.",
        )
        numberProp("-C", "Alias for context.")
        numberProp(
            "context",
            "Number of lines to show before and after each match (rg -C). Requires output_mode: \"content\", ignored otherwise.",
        )
        boolProp(
            "-n",
            "Show line numbers in output (rg -n). Requires output_mode: \"content\", ignored otherwise. Defaults to true.",
        )
        boolProp("-i", "Case insensitive search (rg -i)")
        boolProp(
            "-o",
            "Print only the matched (non-empty) parts of each matching line, one match per output line (rg -o / --only-matching). Requires output_mode: \"content\", ignored otherwise. Defaults to false.",
        )
        stringProp(
            "type",
            "File type to search (rg --type). Common types: js, py, rust, go, java, etc. More efficient than include for standard file types.",
        )
        numberProp(
            "head_limit",
            "Limit output to first N lines/entries, equivalent to \"| head -N\". Works across all output modes: content (limits output lines), files_with_matches (limits file paths), count (limits count entries). Defaults to 250 when unspecified. Pass 0 for unlimited (use sparingly — large result sets waste context).",
        )
        numberProp(
            "offset",
            "Skip first N lines/entries before applying head_limit, equivalent to \"| tail -n +N | head -N\". Works across all output modes. Defaults to 0.",
        )
        boolProp(
            "multiline",
            "Enable multiline mode where . matches newlines and patterns can span lines (rg -U --multiline-dotall). Default: false.",
        )
    }
    putJsonArray("required") { add("pattern") }
}

private val GLOB_PARAMETERS = buildJsonObject {
    put("\$schema", TOOL_JSON_SCHEMA)
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        stringProp("pattern", "The glob pattern to match files against")
        stringProp(
            "path",
            "The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for the default behavior. Must be a valid directory path if provided.",
        )
    }
    putJsonArray("required") { add("pattern") }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProp(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.numberProp(name: String, description: String) {
    putJsonObject(name) {
        put("type", "number")
        put("description", description)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.boolProp(name: String, description: String) {
    putJsonObject(name) {
        put("type", "boolean")
        put("description", description)
    }
}

class GrepTool(
    context: Context,
    private val cwdProvider: () -> String = { WorkspaceAccess(context).guestCwd() },
) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "Grep"
    override val description = GREP_TOOL_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs: Long = GREP_TIMEOUT_MS
    override val maxOutputBytes: Int = MAX_GREP_MODEL_BYTES
    override val permissionName: String = "read"
    override val sideEffectScope: String = "none"
    override val riskLevel: String = "low"
    override val parameters: JsonObject = GREP_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val pattern = args.text("pattern") ?: return@withContext ToolResult("pattern is required", isError = true)
        if (pattern.trim().isEmpty()) {
            return@withContext ToolResult("Grep pattern must not be empty", isError = true)
        }
        val mode = args.text("output_mode") ?: "files_with_matches"
        if (mode !in setOf("content", "files_with_matches", "count")) {
            return@withContext ToolResult("output_mode must be content, files_with_matches, or count", isError = true)
        }
        val headLimit = args.optionalNumber("head_limit")
            ?: if ("head_limit" !in args) args.optionalNumber("max_results") else null
        if (("head_limit" in args && headLimit == null) || (headLimit != null && headLimit < 0)) {
            return@withContext ToolResult("head_limit must be a nonnegative number", isError = true)
        }
        val offset = if ("offset" in args) {
            args.optionalNumber("offset") ?: return@withContext ToolResult("offset must be a nonnegative number", isError = true)
        } else {
            0
        }
        if (offset < 0) return@withContext ToolResult("offset must be a nonnegative number", isError = true)
        val ignoreCase = when {
            "-i" in args -> args.optionalBool("-i") ?: return@withContext ToolResult("-i must be a boolean", isError = true)
            "case_insensitive" in args -> args.optionalBool("case_insensitive") == true
            else -> false
        }
        val multiline = if ("multiline" in args) {
            args.optionalBool("multiline") ?: return@withContext ToolResult("multiline must be a boolean", isError = true)
        } else {
            false
        }
        val showLineNumbers = if ("-n" in args) {
            args.optionalBool("-n") ?: return@withContext ToolResult("-n must be a boolean", isError = true)
        } else {
            true
        }
        val onlyMatching = if ("-o" in args) {
            args.optionalBool("-o") ?: return@withContext ToolResult("-o must be a boolean", isError = true)
        } else {
            false
        }
        val contextLines = args.optionalNumber("context") ?: args.optionalNumber("-C") ?: 0
        val before = (args.optionalNumber("-B") ?: contextLines).coerceAtLeast(0)
        val after = (args.optionalNumber("-A") ?: contextLines).coerceAtLeast(0)
        if (("-B" in args && args.optionalNumber("-B") == null) ||
            ("-A" in args && args.optionalNumber("-A") == null) ||
            ("-C" in args && args.optionalNumber("-C") == null) ||
            ("context" in args && args.optionalNumber("context") == null)
        ) {
            return@withContext ToolResult("context lines must be a number", isError = true)
        }
        val regex = try {
            val options = buildSet {
                if (ignoreCase) add(RegexOption.IGNORE_CASE)
                if (multiline) add(RegexOption.DOT_MATCHES_ALL)
            }
            Regex(pattern.trim(), options)
        } catch (_: IllegalArgumentException) {
            return@withContext ToolResult("Invalid grep regular expression: ${pattern.trim()}", isError = true)
        }
        val rawPath = args.text("path")?.takeIf { it.isNotBlank() }
        val searchPath = access.resolvePath(rawPath ?: cwdProvider().ifBlank { access.guestCwd() })
        val glob = args.text("glob")?.takeIf { it.isNotBlank() }
        val type = (args.text("type") ?: args.text("file_type"))?.takeIf { it.isNotBlank() }
        val matcher = glob?.let {
            try {
                globMatcher(it)
            } catch (_: IllegalArgumentException) {
                return@withContext ToolResult("Invalid glob pattern: $it", isError = true)
            }
        }
        val rootFile = access.hostFile(searchPath)
        if (rootFile == null) {
            return@withContext grepRemote(
                access = access,
                cwd = cwdProvider().ifBlank { access.guestCwd() },
                searchPath = searchPath,
                pattern = pattern.trim(),
                glob = glob,
                type = type,
                mode = mode,
                ignoreCase = ignoreCase,
                multiline = multiline,
                showLineNumbers = showLineNumbers,
                before = if (mode == "content") before else 0,
                after = if (mode == "content") after else 0,
                context = if (mode == "content") contextLines.coerceAtLeast(0) else 0,
                headLimit = headLimit,
                offset = offset,
            )
        }
        if (!rootFile.exists()) {
            return@withContext ToolResult("File not found: $searchPath", isError = true)
        }
        if (!rootFile.isFile && !rootFile.isDirectory) {
            return@withContext ToolResult("Grep search path must be a file or directory: $searchPath", isError = true)
        }
        val globRoot = if (rootFile.isDirectory) searchPath else searchPath.substringBeforeLast('/').ifBlank { "/" }
        val walked = mutableListOf<Walked>()
        walkFiles(searchPath, rootFile, walked, mutableSetOf())
        walked.sortBy { it.guestPath }
        val hits = mutableListOf<FileHit>()
        for (item in walked) {
            currentCoroutineContext().ensureActive()
            val relative = relativeTo(globRoot, item.guestPath)
            val name = item.guestPath.substringAfterLast('/')
            if (matcher != null && !matcher(relative, name)) continue
            if (type != null && !matchesFileType(item.guestPath, type)) continue
            val text = readSearchText(item.file) ?: continue
            val (count, entries) = searchContent(
                guestPath = item.guestPath,
                content = text,
                regex = regex,
                mode = mode,
                before = if (mode == "content") before else 0,
                after = if (mode == "content") after else 0,
                onlyMatching = mode == "content" && onlyMatching,
                multiline = multiline,
            )
            if (count == 0) continue
            hits += FileHit(item.guestPath, item.file, count, entries)
        }
        val cwd = cwdProvider().ifBlank { access.guestCwd() }
        formatGrep(hits, mode, showLineNumbers, headLimit, offset, cwd)
    }
}

class GlobTool(
    context: Context,
    private val cwdProvider: () -> String = { WorkspaceAccess(context).guestCwd() },
) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "Glob"
    override val description = GLOB_TOOL_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs: Long = GREP_TIMEOUT_MS
    override val maxOutputBytes: Int = MAX_GLOB_MODEL_BYTES
    override val permissionName: String = "read"
    override val sideEffectScope: String = "none"
    override val riskLevel: String = "low"
    override val parameters: JsonObject = GLOB_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val pattern = args.text("pattern") ?: return@withContext ToolResult("pattern is required", isError = true)
        val matcher = try {
            globMatcher(pattern)
        } catch (_: IllegalArgumentException) {
            return@withContext ToolResult("Invalid glob pattern: $pattern", isError = true)
        }
        val rawPath = args.text("path")?.takeIf { it.isNotBlank() }
        val searchPath = access.resolvePath(rawPath ?: cwdProvider().ifBlank { access.guestCwd() })
        val rootFile = access.hostFile(searchPath)
        if (rootFile == null) {
            return@withContext globRemote(
                access = access,
                cwd = cwdProvider().ifBlank { access.guestCwd() },
                searchPath = searchPath,
                pattern = pattern,
            )
        }
        if (!rootFile.exists()) {
            return@withContext ToolResult("File not found: $searchPath", isError = true)
        }
        if (!rootFile.isDirectory) {
            return@withContext ToolResult("Glob search path must be a directory: $searchPath", isError = true)
        }
        val walked = mutableListOf<Walked>()
        walkFiles(searchPath, rootFile, walked, mutableSetOf())
        val matches = walked.mapNotNull { item ->
            currentCoroutineContext().ensureActive()
            val relative = relativeTo(searchPath, item.guestPath)
            val name = item.guestPath.substringAfterLast('/')
            if (!matcher(relative, name)) null else item
        }.sortedWith(compareByDescending<Walked> { it.file.lastModified() }.thenBy { it.guestPath })
        val truncated = matches.size > MAX_GLOB_RESULTS
        val cwd = cwdProvider().ifBlank { access.guestCwd() }
        val names = matches.take(MAX_GLOB_RESULTS).map { toDisplayPath(it.guestPath, cwd) }
        ToolResult(formatGlobModelContent(names, truncated))
    }
}

private data class Walked(val guestPath: String, val file: File)
private data class FileHit(val guestPath: String, val file: File, val matchCount: Int, val entries: List<GrepEntry>)
private data class GrepEntry(val guestPath: String, val lineNumber: Int?, val text: String)

private data class Limited<T>(
    val items: List<T>,
    val appliedLimit: Int?,
    val appliedOffset: Int?,
)

private fun formatGrep(
    hits: List<FileHit>,
    mode: String,
    showLineNumbers: Boolean,
    headLimit: Int?,
    offset: Int,
    cwd: String,
): ToolResult {
    val totalMatches = hits.sumOf { it.matchCount }
    if (mode == "files_with_matches") {
        val sorted = hits.sortedWith(compareByDescending<FileHit> { it.file.lastModified() }.thenBy { it.guestPath })
        val limited = applyHeadLimit(sorted, headLimit, offset)
        val names = limited.items.map { toDisplayPath(it.guestPath, cwd) }
        return ToolResult(
            formatGrepModelContent(
                mode = mode,
                content = null,
                filenames = names,
                numFiles = names.size,
                numMatches = totalMatches,
                appliedLimit = limited.appliedLimit,
                appliedOffset = limited.appliedOffset,
            ),
        )
    }
    val ordered = hits.sortedBy { it.guestPath }
    val entries = ordered.flatMap { hit ->
        if (mode == "count") {
            listOf(GrepEntry(hit.guestPath, null, hit.matchCount.toString()))
        } else {
            hit.entries
        }
    }
    val limited = applyHeadLimit(entries, headLimit, offset)
    val content = if (mode == "count") {
        limited.items.joinToString("\n") { "${toDisplayPath(it.guestPath, cwd)}:${it.text}" }
    } else {
        limited.items.joinToString("\n") { formatContentEntry(it, cwd, showLineNumbers) }
    }
    return ToolResult(
        formatGrepModelContent(
            mode = mode,
            content = content,
            filenames = emptyList(),
            numFiles = ordered.size,
            numMatches = totalMatches,
            appliedLimit = limited.appliedLimit,
            appliedOffset = limited.appliedOffset,
        ),
    )
}

private fun formatGrepModelContent(
    mode: String,
    content: String?,
    filenames: List<String>,
    numFiles: Int,
    numMatches: Int,
    appliedLimit: Int?,
    appliedOffset: Int?,
): String {
    val limitInfo = formatLimitInfo(appliedLimit, appliedOffset)
    if (mode == "content") {
        val body = content?.takeIf { it.isNotEmpty() } ?: "No matches found"
        return if (limitInfo.isEmpty()) body else "$body\n\n[Showing results with pagination = $limitInfo]"
    }
    if (mode == "count") {
        val body = content?.takeIf { it.isNotEmpty() } ?: "No matches found"
        val summary = "Found $numMatches total ${plural(numMatches, "occurrence")} across $numFiles ${plural(numFiles, "file")}."
        val page = if (limitInfo.isEmpty()) "" else " with pagination = $limitInfo"
        return "$body\n\n$summary$page"
    }
    if (numFiles == 0) return "No files found"
    val page = if (limitInfo.isEmpty()) "" else " $limitInfo"
    return "Found $numFiles ${plural(numFiles, "file")}$page\n${filenames.joinToString("\n")}"
}

private fun formatGlobModelContent(filenames: List<String>, truncated: Boolean): String {
    if (filenames.isEmpty()) return "No files found"
    val lines = filenames.toMutableList()
    if (truncated) {
        lines += "(Results are truncated. Consider using a more specific path or pattern.)"
    }
    return lines.joinToString("\n")
}

private fun formatLimitInfo(appliedLimit: Int?, appliedOffset: Int?): String {
    val parts = mutableListOf<String>()
    if (appliedLimit != null) parts += "limit: $appliedLimit"
    if (appliedOffset != null) parts += "offset: $appliedOffset"
    return parts.joinToString(", ")
}

private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"

private fun formatContentEntry(entry: GrepEntry, cwd: String, showLineNumbers: Boolean): String {
    val path = toDisplayPath(entry.guestPath, cwd)
    return if (showLineNumbers && entry.lineNumber != null) {
        "$path:${entry.lineNumber}:${entry.text}"
    } else {
        "$path:${entry.text}"
    }
}

private fun <T> applyHeadLimit(items: List<T>, headLimit: Int?, offset: Int): Limited<T> {
    val off = offset.coerceAtLeast(0)
    if (headLimit == 0) {
        return Limited(items.drop(off), null, if (off > 0) off else null)
    }
    val limit = headLimit ?: DEFAULT_GREP_HEAD_LIMIT
    val sliced = items.drop(off).take(limit)
    val truncated = items.size - off > limit
    return Limited(
        sliced,
        if (truncated) limit else null,
        if (off > 0) off else null,
    )
}

private fun searchContent(
    guestPath: String,
    content: String,
    regex: Regex,
    mode: String,
    before: Int,
    after: Int,
    onlyMatching: Boolean,
    multiline: Boolean,
): Pair<Int, List<GrepEntry>> {
    if (multiline) return searchMultiline(guestPath, content, regex, mode, onlyMatching)
    val lines = splitSearchLines(content)
    if (onlyMatching) return searchOnlyMatching(guestPath, lines, regex, before, after)
    val matching = mutableListOf<Int>()
    lines.forEachIndexed { index, line ->
        if (regex.containsMatchIn(line)) matching += index
    }
    if (matching.isEmpty()) return 0 to emptyList()
    if (mode != "content") return matching.size to emptyList()
    val indexes = linkedSetOf<Int>()
    for (index in matching) {
        val start = (index - before).coerceAtLeast(0)
        val end = (index + after).coerceAtMost(lines.lastIndex)
        for (cursor in start..end) indexes += cursor
    }
    return matching.size to indexes.map { GrepEntry(guestPath, it + 1, lines[it]) }
}

private fun searchMultiline(
    guestPath: String,
    content: String,
    regex: Regex,
    mode: String,
    onlyMatching: Boolean,
): Pair<Int, List<GrepEntry>> {
    val flags = buildSet {
        addAll(regex.options)
        add(RegexOption.DOT_MATCHES_ALL)
    }
    val global = Regex(regex.pattern, flags)
    val matches = global.findAll(content).toList()
    if (matches.isEmpty()) return 0 to emptyList()
    if (mode != "content") return matches.size to emptyList()
    val entries = matches.flatMap { match ->
        val lineNumber = lineNumberAt(content, match.range.first)
        if (onlyMatching) {
            match.value.split(Regex("\r?\n")).mapIndexedNotNull { index, line ->
                if (line.isEmpty()) null else GrepEntry(guestPath, lineNumber + index, line)
            }
        } else {
            listOf(GrepEntry(guestPath, lineNumber, match.value.substringBefore('\n').substringBefore('\r')))
        }
    }
    return matches.size to entries
}

private fun searchOnlyMatching(
    guestPath: String,
    lines: List<String>,
    regex: Regex,
    before: Int,
    after: Int,
): Pair<Int, List<GrepEntry>> {
    val byLine = linkedMapOf<Int, List<GrepEntry>>()
    lines.forEachIndexed { index, line ->
        val parts = regex.findAll(line).map { it.value }.filter { it.isNotEmpty() }.toList()
        if (parts.isNotEmpty()) {
            byLine[index] = parts.map { GrepEntry(guestPath, index + 1, it) }
        }
    }
    if (byLine.isEmpty()) return 0 to emptyList()
    val indexes = linkedSetOf<Int>()
    for (index in byLine.keys) {
        val start = (index - before).coerceAtLeast(0)
        val end = (index + after).coerceAtMost(lines.lastIndex)
        for (cursor in start..end) indexes += cursor
    }
    val entries = indexes.flatMap { index ->
        byLine[index] ?: listOf(GrepEntry(guestPath, index + 1, lines[index]))
    }
    return byLine.size to entries
}

private fun splitSearchLines(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    return content.replace(Regex("\r?\n$"), "").split(Regex("\r?\n"))
}

private fun lineNumberAt(content: String, index: Int): Int {
    var line = 1
    val end = index.coerceAtMost(content.length)
    for (cursor in 0 until end) {
        if (content[cursor] == '\n') line += 1
    }
    return line
}

private fun readSearchText(file: File): String? {
    if (!file.isFile || file.length() > MAX_SEARCH_FILE_BYTES) return null
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    if (text.indexOf('\u0000') >= 0) return null
    return text
}

private fun walkFiles(guestPath: String, file: File, into: MutableList<Walked>, seen: MutableSet<String>) {
    val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    if (!seen.add(key)) return
    if (file.isDirectory) {
        val children = file.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name in VCS_DIRECTORIES) continue
                walkFiles(joinGuest(guestPath, child.name), child, into, seen)
            } else if (child.isFile) {
                into += Walked(joinGuest(guestPath, child.name), child)
            }
        }
        return
    }
    if (file.isFile) into += Walked(guestPath, file)
}

private fun joinGuest(dir: String, name: String): String {
    val base = if (dir == "/") "" else dir.trimEnd('/')
    return "$base/$name"
}

private fun relativeTo(rootGuest: String, guestPath: String): String {
    val root = if (rootGuest == "/") "/" else rootGuest.trimEnd('/')
    return when {
        root == "/" -> guestPath.removePrefix("/")
        guestPath == root -> ""
        guestPath.startsWith("$root/") -> guestPath.removePrefix("$root/")
        else -> guestPath
    }
}

private fun toDisplayPath(filePath: String, workingDirectory: String): String {
    val base = workingDirectory.trimEnd('/').ifEmpty { "/" }
    val relative = when {
        filePath == base -> ""
        base == "/" && filePath.startsWith("/") -> filePath.removePrefix("/")
        filePath.startsWith("$base/") -> filePath.removePrefix("$base/")
        else -> return filePath
    }
    if (relative.isEmpty() || relative.startsWith("..") || relative.startsWith("/")) return filePath
    return relative
}

private fun matchesFileType(path: String, type: String): Boolean {
    val normalized = type.lowercase().removePrefix(".")
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    val extension = if (dot <= 0) "" else name.substring(dot).lowercase()
    val known = TYPE_EXTENSIONS[normalized]
    return if (known != null) extension in known else extension == ".$normalized"
}

private fun globMatcher(pattern: String): (String, String) -> Boolean {
    val normalized = pattern.replace('\\', '/').removePrefix("./")
    val regex = globPatternToRegex(normalized)
    val basename = if ('/' !in normalized) regex else null
    return { relative, name -> regex.matches(relative) || basename?.matches(name) == true }
}

private fun globPatternToRegex(pattern: String): Regex {
    val regex = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        val char = pattern[index]
        val next = pattern.getOrNull(index + 1)
        if (char == '*') {
            if (next == '*') {
                if (pattern.getOrNull(index + 2) == '/') {
                    regex.append("(?:.*/)?")
                    index += 3
                } else {
                    regex.append(".*")
                    index += 2
                }
            } else {
                regex.append("[^/]*")
                index += 1
            }
            continue
        }
        if (char == '?') {
            regex.append("[^/]")
            index += 1
            continue
        }
        if (char == '{') {
            val end = pattern.indexOf('}', index + 1)
            if (end > index) {
                val alternatives = pattern.substring(index + 1, end).split(',').joinToString("|") { Regex.escape(it) }
                regex.append("(?:").append(alternatives).append(")")
                index = end + 1
                continue
            }
        }
        regex.append(Regex.escape(char.toString()))
        index += 1
    }
    regex.append('$')
    return Regex(regex.toString())
}

private suspend fun grepRemote(
    access: WorkspaceAccess,
    cwd: String,
    searchPath: String,
    pattern: String,
    glob: String?,
    type: String?,
    mode: String,
    ignoreCase: Boolean,
    multiline: Boolean,
    showLineNumbers: Boolean,
    before: Int,
    after: Int,
    context: Int,
    headLimit: Int?,
    offset: Int,
): ToolResult {
    val flags = buildString {
        if (mode == "content" && showLineNumbers) append('n')
        append('H')
        if (ignoreCase) append('i')
        append('I')
    }
    val modeFlag = when (mode) {
        "files_with_matches" -> " -l"
        "count" -> " -c"
        else -> ""
    }
    val globFlag = buildString {
        if (!glob.isNullOrBlank()) append(" --glob '${shellQuote(glob)}'")
        if (!type.isNullOrBlank()) append(" --type '${shellQuote(type)}'")
    }
    val ctxFlag = buildString {
        if (mode == "content") {
            if (context > 0) append(" -C $context")
            else {
                if (before > 0) append(" -B $before")
                if (after > 0) append(" -A $after")
            }
        }
        if (multiline) append(" -U --multiline-dotall")
    }
    val target = shellQuote(searchPath)
    val escaped = shellQuote(pattern)
    val cmd = "rg -$flags$modeFlag$globFlag$ctxFlag -- '$escaped' '$target'"
    val res = access.executeShell(cmd, cwd = access.guestCwd())
    if (res.exitCode !in setOf(0, 1) && res.stdout.isBlank()) {
        return ToolResult("Grep failed: ${res.error ?: "exit ${res.exitCode}"}", isError = true)
    }
    val lines = res.stdout.trim().lines().filter { it.isNotBlank() }
    if (mode == "files_with_matches") {
        val hits = lines.map { FileHit(it, File(it), 1, emptyList()) }
        return formatGrep(hits, mode, showLineNumbers, headLimit, offset, cwd)
    }
    if (mode == "count") {
        val hits = lines.mapNotNull { line ->
            val cut = line.lastIndexOf(':')
            if (cut <= 0) return@mapNotNull null
            val count = line.substring(cut + 1).toIntOrNull() ?: return@mapNotNull null
            if (count <= 0) return@mapNotNull null
            FileHit(line.substring(0, cut), File(line.substring(0, cut)), count, emptyList())
        }
        return formatGrep(hits, mode, showLineNumbers, headLimit, offset, cwd)
    }
    val limited = applyHeadLimit(lines, headLimit, offset)
    val content = limited.items.joinToString("\n")
    return ToolResult(
        formatGrepModelContent(
            mode = mode,
            content = content,
            filenames = emptyList(),
            numFiles = lines.map { it.substringBefore(':') }.distinct().size,
            numMatches = lines.size,
            appliedLimit = limited.appliedLimit,
            appliedOffset = limited.appliedOffset,
        ),
    )
}

private suspend fun globRemote(
    access: WorkspaceAccess,
    cwd: String,
    searchPath: String,
    pattern: String,
): ToolResult {
    val cmd = "rg --files -g '${shellQuote(pattern)}' '${shellQuote(searchPath)}'"
    val res = access.executeShell(cmd, cwd = access.guestCwd())
    if (res.exitCode !in setOf(0, 1) && res.stdout.isBlank()) {
        return ToolResult("Glob failed: ${res.error ?: "exit ${res.exitCode}"}", isError = true)
    }
    val names = res.stdout.trim().lines().filter { it.isNotBlank() }.map { toDisplayPath(it, cwd) }
    val truncated = names.size > MAX_GLOB_RESULTS
    return ToolResult(formatGlobModelContent(names.take(MAX_GLOB_RESULTS), truncated))
}

private fun shellQuote(value: String): String = value.replace("'", "'\\''")

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.optionalNumber(key: String): Int? {
    val prim = this[key] as? JsonPrimitive ?: return null
    prim.intOrNull?.let { return it }
    prim.longOrNull?.let { return it.toInt() }
    return prim.content.toDoubleOrNull()?.toInt()
}

private fun JsonObject.optionalBool(key: String): Boolean? {
    val prim = this[key] as? JsonPrimitive ?: return null
    prim.booleanOrNull?.let { return it }
    prim.longOrNull?.let { return it != 0L }
    return when (prim.content.trim().lowercase()) {
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> null
    }
}
