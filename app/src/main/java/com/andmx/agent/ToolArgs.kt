package com.andmx.agent

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ToolArgs {
    private val json = Json { ignoreUnknownKeys = true }

    fun value(args: String, keyName: String): String =
        runCatching {
            json.parseToJsonElement(args).jsonObject[keyName]?.jsonPrimitive?.content.orEmpty()
        }.getOrElse {
            fallbackStringValue(args, keyName)
        }

    fun firstValue(args: String, vararg keys: String): String {
        for (key in keys) {
            val v = value(args, key)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    fun pathOf(args: String): String =
        firstValue(args, "file_path", "path", "target_path", "targetPath", "filename", "file")

    fun preview(toolName: String, args: String, limit: Int = 100): String = when (normalize(toolName)) {
        "Bash", "run_shell", "git" -> firstValue(args, "command", "cmd", "args")
        "Read", "read_file", "Write", "write_file", "Edit", "edit_file", "apply_patch", "list_dir" ->
            pathOf(args)
        "Grep", "grep" -> firstValue(args, "pattern").ifBlank { pathOf(args) }
        "Glob", "glob" -> firstValue(args, "pattern").ifBlank { pathOf(args) }
        "WebFetch", "browse" -> firstValue(args, "url")
        "WebSearch", "web_search" -> firstValue(args, "query")
        "TodoWrite", "update_plan" -> "更新计划"
        else -> args.take(limit)
    }.ifBlank { args.take(limit) }

    fun filePath(toolName: String, args: String): String = when (normalize(toolName)) {
        "Read", "read_file", "Write", "write_file", "Edit", "edit_file", "apply_patch", "list_dir" ->
            pathOf(args)
        else -> pathOf(args).takeIf { isFileTool(toolName) }.orEmpty()
    }

    fun editedPath(toolName: String, args: String): String = when (normalize(toolName)) {
        "Write", "write_file", "Edit", "edit_file", "apply_patch" -> pathOf(args)
        else -> ""
    }

    fun webUrl(toolName: String, args: String): String = when (normalize(toolName)) {
        "WebFetch", "browse" -> firstValue(args, "url")
        "WebSearch", "web_search" -> firstValue(args, "query").takeIf { it.isNotBlank() }?.let {
            "https://duckduckgo.com/?q=" + URLEncoder.encode(it, "UTF-8")
        }.orEmpty()
        else -> ""
    }

    fun isFileTool(toolName: String): Boolean = when (normalize(toolName)) {
        "Read", "read_file", "Write", "write_file", "Edit", "edit_file", "apply_patch", "list_dir" -> true
        else -> false
    }

    fun isWriteTool(toolName: String): Boolean = when (normalize(toolName)) {
        "Write", "write_file", "Edit", "edit_file", "apply_patch" -> true
        else -> false
    }

    fun normalize(toolName: String): String = toolName.trim()

    private fun fallbackStringValue(args: String, keyName: String): String {
        val key = "\"$keyName\""
        val i = args.indexOf(key)
        if (i < 0) return ""
        val colon = args.indexOf(':', i)
        val q1 = args.indexOf('"', colon + 1)
        if (colon < 0 || q1 < 0) return ""

        val out = StringBuilder()
        var escaped = false
        for (index in q1 + 1 until args.length) {
            val c = args[index]
            when {
                escaped -> {
                    out.append(
                        when (c) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> c
                        },
                    )
                    escaped = false
                }
                c == '\\' -> escaped = true
                c == '"' -> return out.toString()
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
