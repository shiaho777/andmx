package com.andmx.agent

import android.content.Context
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.workspace.ChangeTracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

/** read_file: return the contents of a file in the guest filesystem. */
class ReadFileTool(context: Context) : Tool {
    private val fs = GuestFs(ProotRuntime(context))
    override val name = "read_file"
    override val description = "读取 Linux 沙箱中某个文件的文本内容。大文件会被截断:用 offset/limit 分页读取。"
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "文件路径,如 /root/app.py") }
            putJsonObject("offset") { put("type", "integer"); put("description", "起始行号(从 0 开始),默认 0"); put("default", 0) }
            putJsonObject("limit") { put("type", "integer"); put("description", "最多返回的行数,默认 2000"); put("default", 2000) }
        }
        putJsonArray("required") { add("path") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.str("path") ?: return ToolResult("缺少参数 path", isError = true)
        val offset = (args["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_LINE_LIMIT).coerceIn(1, MAX_LINE_LIMIT)
        return runCatching {
            val text = fs.readText(path)
            val lines = text.split('\n')
            val total = lines.size
            if (total <= limit) {
                ToolResult(text)
            } else {
                val end = (offset + limit).coerceAtMost(total)
                val slice = lines.subList(offset, end).joinToString("\n")
                val note = buildString {
                    append("\n\n... (已截断: 显示第 ${offset + 1}-$end 行, 共 $total 行)")
                    if (end < total) append("; 用 offset=$end 继续读取后续内容")
                    if (offset > 0) append("; 用 offset=${(offset - limit).coerceAtLeast(0)} 读取前面内容")
                }
                ToolResult(slice + note)
            }
        }.getOrElse { ToolResult("读取失败: ${it.message}", isError = true) }
    }

    companion object {
        const val DEFAULT_LINE_LIMIT = 2000
        const val MAX_LINE_LIMIT = 5000
    }
}

/** write_file: create or overwrite a file; records a reviewable change. */
class WriteFileTool(context: Context) : Tool {
    private val fs = GuestFs(ProotRuntime(context))
    override val name = "write_file"
    override val description = "创建或覆盖 Linux 沙箱中的文件。会生成可在 diff 中审查的变更。"
    override val risk = ToolRisk.WRITE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "文件路径") }
            putJsonObject("content") { put("type", "string"); put("description", "文件完整内容") }
        }
        putJsonArray("required") { add("path"); add("content") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.str("path") ?: return ToolResult("缺少参数 path", isError = true)
        val content = args.str("content") ?: return ToolResult("缺少参数 content", isError = true)
        return runCatching {
            val existed = fs.exists(path)
            val old = if (existed) fs.readText(path) else ""
            fs.writeText(path, content)
            ChangeTracker.record(path, old, content, existedBefore = existed)
            ToolResult("已写入 $path (${content.length} 字符)")
        }.getOrElse { ToolResult("写入失败: ${it.message}", isError = true) }
    }
}

/** edit_file: replace an exact substring; records a reviewable change. */
class EditFileTool(context: Context) : Tool {
    private val fs = GuestFs(ProotRuntime(context))
    override val name = "edit_file"
    override val description =
        "对沙箱中已存在文件做精确替换:把 old_str 首次出现替换为 new_str。old_str 必须在文件中唯一且完整匹配。"
    override val risk = ToolRisk.WRITE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "文件路径") }
            putJsonObject("old_str") { put("type", "string"); put("description", "要被替换的原文(需精确匹配)") }
            putJsonObject("new_str") { put("type", "string"); put("description", "替换后的新文本") }
        }
        putJsonArray("required") { add("path"); add("old_str"); add("new_str") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.str("path") ?: return ToolResult("缺少参数 path", isError = true)
        val oldStr = args.str("old_str") ?: return ToolResult("缺少参数 old_str", isError = true)
        val newStr = args.str("new_str") ?: return ToolResult("缺少参数 new_str", isError = true)
        return runCatching {
            val original = fs.readText(path)
            val count = original.split(oldStr).size - 1
            when {
                count == 0 -> ToolResult("未找到匹配的 old_str", isError = true)
                count > 1 -> ToolResult("old_str 出现 $count 次,不唯一;请提供更长的上下文", isError = true)
                else -> {
                    val updated = original.replaceFirst(oldStr, newStr)
                    fs.writeText(path, updated)
                    ChangeTracker.record(path, original, updated, existedBefore = true)
                    ToolResult("已编辑 $path")
                }
            }
        }.getOrElse { ToolResult("编辑失败: ${it.message}", isError = true) }
    }
}
