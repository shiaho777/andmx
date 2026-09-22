package com.andmx.agent

import com.andmx.llm.ApiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ZCode ReadFileStateMap 对齐：记录本会话内读/写/编辑过的文件内容，
 * 压缩后把仍相关的文件内容以 system-reminder 形式回放进 history，
 * 避免模型在压缩后丢失文件上下文而重读或幻觉路径。
 */
class ReadFileState {

    data class Entry(
        val path: String,
        val content: String,
        val offset: Int? = null,
        val limit: Int? = null,
        val readAtMs: Long = System.currentTimeMillis(),
        val sourceTool: String = READ_TOOL,
        /** 读取时的文件 mtime（毫秒）——edit/write 新鲜度校验用。 */
        val mtimeMs: Long? = null,
        val sizeBytes: Long? = null,
        /** 上游 isPartialView：offset/limit 截取的局部视图。 */
        val isPartialView: Boolean = offset != null || limit != null,
    )

    private val map = LinkedHashMap<String, Entry>()
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun record(
        path: String,
        content: String,
        offset: Int? = null,
        limit: Int? = null,
        sourceTool: String = READ_TOOL,
        mtimeMs: Long? = null,
        sizeBytes: Long? = null,
    ) {
        val key = normalizePath(path)
        map.remove(key)
        map[key] = Entry(
            path, content, offset, limit, System.currentTimeMillis(), sourceTool,
            mtimeMs = mtimeMs, sizeBytes = sizeBytes,
        )
    }

    /** 上游写前 freshness 校验：无记录=未读；mtime 前进或大小变化=stale。 */
    @Synchronized
    fun staleness(path: String, currentMtimeMs: Long?, currentSizeBytes: Long?): String? {
        val entry = map[normalizePath(path)]
            ?: return "未读取"
        if (entry.mtimeMs == null || currentMtimeMs == null) return null
        if (currentMtimeMs > entry.mtimeMs) return "已修改"
        if (entry.sizeBytes != null && currentSizeBytes != null &&
            currentSizeBytes != entry.sizeBytes
        ) return "已修改"
        return null
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun entries(): List<Entry> = map.values.toList()

    /**
     * 上游 buildPostCompactReadStateReminderEntries 镜像：
     * 最近读取优先、跳过 .git、跳过保留段里已有的 Read 调用、
     * 单文件/总量超预算时退化为"文件太大请重读"引用条。
     * 返回 system-reminder 包裹的条目（调用方追加到压缩后 history 尾部）。
     */
    @Synchronized
    fun postCompactReminders(
        preservedPaths: Set<String> = emptySet(),
        maxFiles: Int = MAX_REPLAY_FILES,
        maxFileApproxTokens: Int = MAX_FILE_APPROX_TOKENS,
        maxTotalApproxTokens: Int = MAX_TOTAL_APPROX_TOKENS,
    ): List<String> {
        val candidates = map.values
            .filter { it.sourceTool == READ_TOOL }
            .filter { !normalizePath(it.path).contains("/.git/") }
            .filter { normalizePath(it.path) !in preservedPaths }
            .sortedByDescending { it.readAtMs }
        val out = mutableListOf<String>()
        var total = 0
        for (entry in candidates) {
            if (out.size >= maxFiles) break
            val approx = (entry.content.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
            if (approx > maxFileApproxTokens || total + approx > maxTotalApproxTokens) {
                out += wrap("Note: ${entry.path} was read before the last conversation was summarized, but the contents are too large to include. Use Read tool if you need to access it.")
                continue
            }
            total += approx
            out += wrap(formatProjection(entry))
        }
        return out
    }

    /**
     * 上游 hydrateReadFileStateFromSession 精简版：从已加载 history 的
     * read_file/Read 工具调用 + 对应 tool 结果重建状态（resume/seed 时调用）。
     * write_file 用 args.content 记录；edit 无法从结果重建，跳过。
     */
    @Synchronized
    fun hydrate(messages: List<ApiMessage>) {
        map.clear()
        val resultsByCallId = messages.filter { it.role == "tool" && it.toolCallId != null }
            .associateBy({ it.toolCallId!! }, { it.content.orEmpty() })
        for (msg in messages) {
            if (msg.role != "assistant") continue
            for (call in msg.toolCalls.orEmpty()) {
                val name = call.function.name
                val args = runCatching { json.parseToJsonElement(call.function.arguments).jsonObject }.getOrNull() ?: continue
                val path = args["path"]?.jsonPrimitive?.content
                    ?: args["file_path"]?.jsonPrimitive?.content ?: continue
                when (name) {
                    READ_TOOL, "Read" -> {
                        val content = resultsByCallId[call.id] ?: continue
                        val offset = args["offset"]?.jsonPrimitive?.content?.toIntOrNull()
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull()
                        record(path, content, offset, limit, READ_TOOL)
                    }
                    WRITE_TOOL, "Write" -> {
                        args["content"]?.jsonPrimitive?.content?.let { record(path, it, sourceTool = WRITE_TOOL) }
                    }
                }
            }
        }
    }

    private fun formatProjection(entry: Entry): String {
        val input = buildString {
            append("""{"file_path":"${entry.path}"""")
            entry.offset?.let { append(""","offset":$it""") }
            entry.limit?.let { append(""","limit":$it""") }
            append("}")
        }
        val startLine = entry.offset?.takeIf { it > 1 } ?: 1
        val numbered = entry.content.lines()
            .mapIndexed { i, line -> "${i + startLine}\t$line" }
            .joinToString("\n")
        return "Called the Read tool with the following input: $input\nResult of calling the Read tool:\n$numbered"
    }

    private fun wrap(body: String): String =
        SystemReminder.wrap(SystemReminder.Source.RESUME_REFERENCED_SESSION_CONTEXT, body).trimEnd()

    private fun normalizePath(path: String): String = path.replace('\\', '/')

    companion object {
        const val READ_TOOL = "read_file"
        const val WRITE_TOOL = "write_file"
        const val MAX_REPLAY_FILES = 5
        const val MAX_FILE_APPROX_TOKENS = 5_000
        const val MAX_TOTAL_APPROX_TOKENS = 50_000
        const val CHARS_PER_TOKEN = 3

        /** 压缩后保留段中已存在的 Read 调用路径（这些不需要回放）。 */
        fun collectReadPaths(messages: List<ApiMessage>, json: Json = Json { ignoreUnknownKeys = true }): Set<String> {
            val out = mutableSetOf<String>()
            for (msg in messages) {
                if (msg.role != "assistant") continue
                for (call in msg.toolCalls.orEmpty()) {
                    if (call.function.name != READ_TOOL && call.function.name != "Read") continue
                    runCatching { json.parseToJsonElement(call.function.arguments).jsonObject }
                        .getOrNull()
                        ?.let { it["path"]?.jsonPrimitive?.content ?: it["file_path"]?.jsonPrimitive?.content }
                        ?.let { out += it.replace('\\', '/') }
                }
            }
            return out
        }
    }
}
