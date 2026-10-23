package com.andmx.ui2.chat

import com.andmx.agent.ToolArgs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ZCodeToolGroup {
    enum class Class { EXPLORE, EXECUTE, CHANGES }

    private val json = Json { ignoreUnknownKeys = true }
    private val executeRead = Regex(
        """\b(rg|grep|find|ls|cat|head|tail|wc|stat|pwd|which|readlink|tree|sed\s+-n|get-childitem|gci|dir|get-content|gc|type|select-string|sls|get-location|test-path|resolve-path)\b|^git\s+(status|log|show|diff)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val executeWrite = Regex(
        """\b(sed\s+-i|perl\s+-pi|tee|mv|cp|rm|mkdir|rmdir|touch|truncate|chmod|chown|remove-item|del|erase|set-content|add-content|clear-content|out-file|new-item|move-item|copy-item|rename-item|set-item)\b|^git\s+(add|commit|rm|mv|checkout|switch|restore|reset|clean|revert|cherry-pick|merge|rebase)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val redirectWrite = Regex("""(^|[^\d<])>>?\s*\S|&>\s*\S""", RegexOption.IGNORE_CASE)
    private val shellLoop = Regex("""\b(for|while)\b""", RegexOption.IGNORE_CASE)
    private val shellLc = Regex("""^(?:/bin/)?(?:zsh|bash|sh)\s+-lc\s+([\s\S]+)$""", RegexOption.IGNORE_CASE)
    private val segmentSplit = Regex("""&&|\|\||;""")

    fun classify(tool: ToolCall): Class? {
        val family = familyOf(tool.name)
        val commands = if (family == "shell") commandsFromArgs(tool.args) else emptyList()
        if (family == "file-write") return Class.CHANGES
        if (isExplore(family, commands)) return Class.EXPLORE
        if (family == "shell" && commands.isNotEmpty()) return Class.EXECUTE
        return null
    }

    private fun familyOf(name: String): String = when (ToolArgs.canonical(name)) {
        "read" -> "file-read"
        "write", "edit", "multiedit", "patch" -> "file-write"
        "shell" -> "shell"
        "grep", "glob", "list", "webfetch", "search" -> "search"
        else -> "unknown"
    }

    private fun isExplore(family: String, commands: List<String>): Boolean {
        if (family == "file-write") return false
        if (family == "file-read" || family == "search") return true
        if (family != "shell") return false
        if (commands.isEmpty()) return false
        if (commands.any { executeWrite.containsMatchIn(it) }) return false
        if (commands.any { redirectWrite.containsMatchIn(it) }) return false
        if (commands.any { executeRead.containsMatchIn(it) }) return true
        return commands.any { shellLoop.containsMatchIn(it) && executeRead.containsMatchIn(it) }
    }

    private fun commandsFromArgs(args: String): List<String> {
        val obj = runCatching { json.parseToJsonElement(args) as? JsonObject }.getOrNull()
        val candidates = ArrayList<String>()
        if (obj != null) {
            for (key in listOf("command", "cmd", "script")) {
                val value = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (value.isNotEmpty()) candidates += value
            }
        } else {
            val raw = args.trim()
            if (raw.isNotEmpty() && !raw.startsWith("{")) candidates += raw
        }
        return candidates.flatMap { normalizeCommandCandidate(it) }.distinct()
    }

    private fun normalizeCommandCandidate(candidate: String): List<String> {
        val unwrapped = unwrapShellCommand(candidate)
        if (unwrapped.isEmpty()) return emptyList()
        return unwrapped.split(segmentSplit).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun unwrapShellCommand(command: String): String {
        val trimmed = command.trim()
        val match = shellLc.find(trimmed)
        val inner = match?.groupValues?.getOrNull(1)
        return if (inner != null) stripWrappingQuotes(inner) else trimmed
    }

    private fun stripWrappingQuotes(value: String): String {
        val normalized = value.trim()
        val quoted = (normalized.startsWith("\"") && normalized.endsWith("\"")) ||
            (normalized.startsWith("'") && normalized.endsWith("'"))
        return if (quoted && normalized.length >= 2) normalized.substring(1, normalized.length - 1).trim()
        else normalized
    }
}
