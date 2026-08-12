package com.andmx.agent

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    fun value(args: JsonObject, keyName: String): String =
        (args[keyName] as? JsonPrimitive)?.content.orEmpty()

    /** First non-blank value among [keys], in order. */
    fun firstValue(args: String, vararg keys: String): String =
        keys.firstNotNullOfOrNull { value(args, it).takeIf { v -> v.isNotBlank() } }.orEmpty()

    fun arraySize(args: String, keyName: String): Int =
        runCatching {
            ((json.parseToJsonElement(args).jsonObject[keyName]) as? JsonArray)?.size ?: 0
        }.getOrDefault(0)

    /**
     * Canonical tool id, independent of wire casing. Maps both the ZCode
     * PascalCase surface (Read/Write/Edit/Bash/…) and the legacy snake_case
     * surface (read_file/edit_file/run_shell/…) to one id so every UI pane can
     * reason about a tool without maintaining two parallel lookup tables.
     */
    fun canonical(toolName: String): String {
        val n = toolName.trim()
        return when (n) {
            "Read", "read_file" -> "read"
            "ListDir", "list_dir", "LS" -> "list"
            "Write", "write_file" -> "write"
            "Edit", "edit_file" -> "edit"
            "MultiEdit" -> "multiedit"
            "ApplyPatch", "apply_patch" -> "patch"
            "Bash", "run_shell", "Shell" -> "shell"
            "Git", "git" -> "git"
            "Grep", "grep" -> "grep"
            "Glob", "glob" -> "glob"
            "WebFetch", "browse", "Fetch" -> "webfetch"
            "WebSearch", "web_search", "Search" -> "search"
            "TodoWrite", "update_plan" -> "todo"
            "TodoRead" -> "todoread"
            "Skill" -> "skill"
            "Agent", "Task", "spawn_agent", "multi_agent" -> "agent"
            "TaskOutput" -> "taskoutput"
            "TaskStop" -> "taskstop"
            "EnterPlanMode" -> "enterplan"
            "ExitPlanMode" -> "exitplan"
            "AskUserQuestion" -> "ask"
            "ReadSessionContext" -> "sessionctx"
            "get_goal", "create_goal", "update_goal" -> "goal"
            else -> if (isMcpName(n)) "mcp" else n.lowercase()
        }
    }

    /** MCP / plugin tools expose compound names (server__tool or plugin_prefixed). */
    fun isMcpName(toolName: String): Boolean {
        val n = toolName.trim()
        return n.startsWith("mcp_", true) ||
            n.startsWith("plugin_", true) ||
            (n.contains("__") && n.count { it == '_' } >= 2)
    }

    fun preview(toolName: String, args: String, limit: Int = 120): String {
        val body = when (canonical(toolName)) {
            "shell", "git" -> firstValue(args, "command", "args", "description")
            "read", "list", "write", "edit", "multiedit", "patch" -> pathOf(args)
            "grep" -> firstValue(args, "pattern", "path", "glob")
            "glob" -> firstValue(args, "pattern", "path")
            "webfetch" -> firstValue(args, "url", "prompt")
            "search" -> value(args, "query")
            "todo" -> todoSummary(args)
            "todoread" -> "读取待办"
            "skill" -> firstValue(args, "skill", "name")
            "agent" -> firstValue(args, "subagent_type", "description", "prompt", "task")
            "taskoutput" -> firstValue(args, "task_id", "id")
            "taskstop" -> firstValue(args, "task_id", "id")
            "ask" -> "${arraySize(args, "questions")} 个问题"
            "sessionctx" -> firstValue(args, "query", "sessionId")
            "enterplan" -> firstValue(args, "reason").ifBlank { "进入计划模式" }
            "exitplan" -> "计划已就绪"
            "mcp" -> toolName.substringAfterLast('_').ifBlank { toolName }
            else -> ""
        }
        return body.ifBlank { args.take(limit) }.singleLine(limit)
    }

    fun filePath(toolName: String, args: String): String = when (canonical(toolName)) {
        "read", "list", "write", "edit", "multiedit", "patch", "grep", "glob" -> pathOf(args)
        else -> ""
    }

    fun editedPath(toolName: String, args: String): String = when (canonical(toolName)) {
        "write", "edit", "multiedit", "patch" -> pathOf(args)
        else -> ""
    }

    fun webUrl(toolName: String, args: String): String = when (canonical(toolName)) {
        "webfetch" -> value(args, "url")
        "search" -> value(args, "query").takeIf { it.isNotBlank() }?.let {
            "https://duckduckgo.com/?q=" + URLEncoder.encode(it, "UTF-8")
        }.orEmpty()
        else -> ""
    }

    fun shellCommand(toolName: String, args: String): String = when (canonical(toolName)) {
        "shell", "git" -> firstValue(args, "command", "args")
        else -> ""
    }

    fun pathOf(args: String): String = firstValue(args, "file_path", "path")

    fun isEditTool(toolName: String): Boolean = canonical(toolName) in EDIT_CANONICAL

    val EDIT_CANONICAL = setOf("write", "edit", "multiedit", "patch")

    private fun todoSummary(args: String): String {
        val n = arraySize(args, "todos").coerceAtLeast(arraySize(args, "steps"))
        return when {
            n > 0 -> "更新 $n 项待办"
            else -> "更新待办"
        }
    }

    private fun String.singleLine(limit: Int): String =
        replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(limit)

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
