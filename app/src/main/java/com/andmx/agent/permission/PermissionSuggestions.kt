package com.andmx.agent.permission

import com.andmx.agent.ApprovalRuleStore
import com.andmx.agent.ToolArgs

data class PermissionSuggestion(
    val toolCanonical: String,
    val ruleKey: String,
    val label: String,
) {
    val ruleContent: String? get() = ApprovalRuleStore.ruleContentOf(ApprovalRuleStore.Rule(toolCanonical, ruleKey))
    val wireToolName: String get() = ApprovalRuleStore.wireToolName(toolCanonical)
}

object PermissionSuggestions {
    private val dangerousWords = setOf(
        "rm", "rmdir", "sudo", "su", "doas", "dd", "mkfs", "shutdown", "reboot",
        "halt", "poweroff", "kill", "killall", "pkill", "chmod", "chown", "eval", "exec",
    )
    private val subcommandTools = setOf("npm", "pnpm", "yarn", "cargo", "go", "pip", "pip3", "docker", "kubectl", "brew", "apt", "apt-get")
    private val gitSafeSubcommands = setOf("status", "log", "diff", "show", "branch", "remote", "add", "commit", "stash", "fetch", "pull")
    private val wrapperWords = setOf("env", "command", "nohup", "time", "nice", "xargs")

    fun suggest(toolName: String, input: Any?): List<PermissionSuggestion> {
        val canonical = ToolArgs.canonical(toolName)
        val suggestions = when (canonical) {
            "shell", "git" -> commandSuggestions(canonical, commandOf(input))
            "webfetch" -> urlSuggestions(canonical, fieldOf(input, "url"))
            "read", "write", "edit", "multiedit", "patch", "list", "grep", "glob" ->
                fileSuggestions(canonical, pathOf(input))
            else -> emptyList()
        }
        return suggestions
            .filter { it.ruleKey.isNotBlank() && !tooBroad(it.ruleKey) }
            .distinctBy { it.toolCanonical to it.ruleKey }
            .take(3)
    }

    private fun commandSuggestions(canonical: String, command: String): List<PermissionSuggestion> {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.contains('\n') || trimmed.contains(';') || trimmed.contains("&&") ||
            trimmed.contains("||") || trimmed.contains('|') || trimmed.contains('`') ||
            trimmed.contains("$(") || trimmed.contains('>') || trimmed.contains('<')
        ) return emptyList()
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val words = tokens.dropWhile { it.contains('=') && !it.startsWith("-") }
        val head = words.firstOrNull()?.substringAfterLast('/')?.lowercase() ?: return emptyList()
        if (head in dangerousWords || head in wrapperWords || head.startsWith("-")) return emptyList()
        val prefixes = mutableListOf(head)
        val second = words.getOrNull(1)
        if (second != null && !second.startsWith("-") && !second.equals(head, ignoreCase = true)) {
            when {
                canonical == "git" || head == "git" ->
                    if (second.lowercase() in gitSafeSubcommands) prefixes += "$head $second"
                head in subcommandTools -> prefixes += "$head $second"
            }
        }
        return prefixes.distinct().map { prefix ->
            PermissionSuggestion(
                toolCanonical = canonical,
                ruleKey = "$canonical:prefix:$prefix",
                label = if (prefix.contains(' ')) "始终允许 `$prefix …` 子命令" else "始终允许 `$prefix …` 命令",
            )
        }
    }

    private fun fileSuggestions(canonical: String, path: String): List<PermissionSuggestion> {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isBlank()) return emptyList()
        val dir = trimmed.substringBeforeLast('/', "")
        if (dir.isBlank() || dir == "/" || dir == "." || dir == ".." || dir.endsWith(":") ||
            dir.split('/').any { it == ".." }
        ) return emptyList()
        val pattern = "$dir/*"
        return listOf(
            PermissionSuggestion(
                toolCanonical = canonical,
                ruleKey = "$canonical:file:$pattern",
                label = "始终允许对 $dir/ 下文件的类似操作",
            ),
        )
    }

    private fun urlSuggestions(canonical: String, url: String): List<PermissionSuggestion> {
        val subject = WebFetchPreapproved.domainSubject(url) ?: return emptyList()
        return listOf(
            PermissionSuggestion(
                toolCanonical = canonical,
                ruleKey = "$canonical:$subject",
                label = "始终允许访问 ${subject.removePrefix("domain:")} 域名",
            ),
        )
    }

    private fun tooBroad(ruleKey: String): Boolean {
        val content = ruleKey.substringAfter(':', "")
        if (content.isBlank() || content == "any" || content == "*") return true
        val body = content.removePrefix("prefix:").removePrefix("file:")
        return body == "*" || body == "/*" || body == "*" || body.isBlank()
    }

    private fun commandOf(input: Any?): String = when (input) {
        is String -> input
        else -> jsonString(input, "command") ?: jsonString(input, "args").orEmpty()
    }

    private fun pathOf(input: Any?): String = when (input) {
        is String -> input
        else -> jsonString(input, "file_path") ?: jsonString(input, "path").orEmpty()
    }

    private fun fieldOf(input: Any?, key: String): String = when (input) {
        is String -> input
        else -> jsonString(input, key).orEmpty()
    }
}