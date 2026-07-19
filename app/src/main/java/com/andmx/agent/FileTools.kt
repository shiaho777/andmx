package com.andmx.agent

import android.content.Context
import com.andmx.workspace.ChangeTracker
import com.andmx.workspace.WorkspaceAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

class ReadFileTool(context: Context) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "read_file"
    override val description =
        "读取当前工作区中某个文件的文本内容。大文件会被截断:用 offset/limit 分页读取。"
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "文件路径，相对或绝对") }
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
            val text = access.readText(path)
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

class WriteFileTool(context: Context) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "write_file"
    override val description = "创建或覆盖当前工作区中的文件。会生成可在 diff 中审查的变更。"
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
            val resolved = access.resolvePath(path)
            val existed = access.exists(resolved)
            val old = if (existed) access.readText(resolved) else ""
            access.writeText(resolved, content)
            ChangeTracker.record(resolved, old, content, existedBefore = existed)
            ToolResult("已写入 $resolved (${content.length} 字符)")
        }.getOrElse { ToolResult("写入失败: ${it.message}", isError = true) }
    }
}

class EditFileTool(context: Context) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "edit_file"
    override val description =
        "对工作区中已存在文件做精确替换:把 old_str 首次出现替换为 new_str。old_str 必须在文件中唯一且完整匹配。"
    override val risk = ToolRisk.WRITE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "文件路径") }
            putJsonObject("old_str") { put("type", "string"); put("description", "要被替换的原文，必须唯一匹配") }
            putJsonObject("new_str") { put("type", "string"); put("description", "替换后的新文本") }
            putJsonObject("replace_all") { put("type", "boolean"); put("description", "是否替换全部匹配"); put("default", false) }
        }
        putJsonArray("required") { add("path"); add("old_str"); add("new_str") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.str("path") ?: return ToolResult("缺少参数 path", isError = true)
        val oldStr = args.str("old_str") ?: return ToolResult("缺少参数 old_str", isError = true)
        val newStr = args.str("new_str") ?: return ToolResult("缺少参数 new_str", isError = true)
        val replaceAll = args["replace_all"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        return runCatching {
            val resolved = access.resolvePath(path)
            if (!access.exists(resolved)) return@runCatching ToolResult("文件不存在: $resolved", isError = true)
            val original = access.readText(resolved)
            val count = original.split(oldStr).size - 1
            if (count == 0) return@runCatching ToolResult("未找到匹配的 old_str", isError = true)
            if (!replaceAll && count > 1) {
                return@runCatching ToolResult("old_str 出现 $count 次，请提供更唯一的匹配或设置 replace_all=true", isError = true)
            }
            val updated = if (replaceAll) original.replace(oldStr, newStr) else original.replaceFirst(oldStr, newStr)
            access.writeText(resolved, updated)
            ChangeTracker.record(resolved, original, updated, existedBefore = true)
            ToolResult("已编辑 $resolved" + if (replaceAll) " (替换 $count 处)" else "")
        }.getOrElse { ToolResult("编辑失败: ${it.message}", isError = true) }
    }
}
