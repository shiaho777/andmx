package com.andmx.agent

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared helpers for interpreting tool-call arguments across workbench panes. */
object ToolArgs {
    private val json = Json { ignoreUnknownKeys = true }

    fun value(args: String, keyName: String): String =
        runCatching {
            json.parseToJsonElement(args).jsonObject[keyName]?.jsonPrimitive?.content.orEmpty()
        }.getOrElse {
            fallbackStringValue(args, keyName)
        }

    fun preview(toolName: String, args: String, limit: Int = 100): String = when (toolName) {
        "run_shell" -> value(args, "command")
        "read_file", "write_file", "edit_file", "apply_patch", "list_dir" -> value(args, "path")
        "grep" -> value(args, "pattern").ifBlank { value(args, "path") }
        "glob" -> value(args, "pattern").ifBlank { value(args, "path") }
        "browse" -> value(args, "url")
        "web_search" -> value(args, "query")
        "git" -> value(args, "command").ifBlank { value(args, "args") }
        "update_plan" -> "更新计划"
        else -> args.take(limit)
    }.ifBlank { args.take(limit) }

    fun filePath(toolName: String, args: String): String = when (toolName) {
        "read_file", "write_file", "edit_file", "apply_patch", "list_dir" -> value(args, "path")
        else -> ""
    }

    fun editedPath(toolName: String, args: String): String = when (toolName) {
        "write_file", "edit_file", "apply_patch" -> value(args, "path")
        else -> ""
    }

    fun webUrl(toolName: String, args: String): String = when (toolName) {
        "browse" -> value(args, "url")
        "web_search" -> value(args, "query").takeIf { it.isNotBlank() }?.let {
            "https://duckduckgo.com/?q=" + URLEncoder.encode(it, "UTF-8")
        }.orEmpty()
        else -> ""
    }

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
        return ""
    }
}
