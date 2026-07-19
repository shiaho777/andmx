package com.andmx.agent.multi

import com.andmx.agent.Tool
import com.andmx.settings.CustomSubAgent
import com.andmx.settings.SubagentStateFile

object SubagentCatalog {
    val COLORS = listOf("red", "blue", "green", "yellow", "purple", "orange", "pink", "cyan")
    val PERMISSION_MODES = listOf(
        "default",
        "acceptEdits",
        "auto",
        "bypassPermissions",
        "dontAsk",
        "plan",
    )
    val CATALOG_TOOLS = listOf(
        "Read", "Grep", "Glob", "Bash", "Edit", "Write", "WebFetch", "WebSearch", "TodoWrite",
    )
    val BUILTIN_NAMES = setOf("general-purpose", "Explore")
    private val NAME_RE = Regex("^[a-zA-Z0-9-]+$")

    fun builtInId(name: String): String = "built-in:$name"

    fun createBuiltIns(overrides: Map<String, String> = emptyMap()): List<CustomSubAgent> {
        val gpModel = overrides["general-purpose"]?.trim().orEmpty()
        val exploreModel = overrides["Explore"]?.trim().orEmpty()
        return listOf(
            CustomSubAgent(
                id = builtInId("general-purpose"),
                name = "general-purpose",
                description = "General-purpose agent for researching complex questions, searching for code, and executing multi-step tasks.",
                systemPrompt = "",
                model = gpModel.ifBlank { "inherit" },
                color = "blue",
                tools = listOf("*"),
                path = "built-in:general-purpose",
                scope = "built-in",
                source = "built-in",
                enabled = true,
                readOnly = true,
            ),
            CustomSubAgent(
                id = builtInId("Explore"),
                name = "Explore",
                description = "Read-only search agent for broad fan-out searches.",
                systemPrompt = "",
                model = exploreModel.ifBlank { "inherit" },
                color = "cyan",
                tools = listOf("Bash", "Glob", "Grep", "Read", "WebFetch", "WebSearch", "TodoWrite"),
                path = "built-in:Explore",
                scope = "built-in",
                source = "built-in",
                enabled = true,
                readOnly = true,
            ),
        )
    }

    fun attachEnabledState(
        agents: List<CustomSubAgent>,
        state: SubagentStateFile,
    ): List<CustomSubAgent> {
        val disabled = state.disabledAgentIds.toSet()
        return agents.map { agent ->
            when (agent.scope) {
                "user" -> agent.copy(enabled = agent.id !in disabled && agent.enabled)
                else -> agent.copy(enabled = agent.id !in disabled)
            }
        }
    }

    fun listAll(
        userAgents: List<CustomSubAgent>,
        state: SubagentStateFile,
    ): List<CustomSubAgent> {
        val builtIns = createBuiltIns(state.builtInModelOverrides)
        val users = userAgents
            .filter { it.scope == "user" || it.scope.isBlank() }
            .map {
                it.copy(
                    scope = "user",
                    source = if (it.source.isBlank()) "user" else it.source,
                    readOnly = false,
                )
            }
            .sortedBy { it.name.lowercase() }
        return attachEnabledState(builtIns + users, state)
    }

    fun resolve(
        type: String?,
        userAgents: List<CustomSubAgent>,
        state: SubagentStateFile,
    ): CustomSubAgent? {
        val all = listAll(userAgents, state)
        val key = type?.trim().orEmpty()
        if (key.isBlank()) {
            return all.firstOrNull { it.name == "Explore" && it.enabled }
                ?: all.firstOrNull { it.name == "general-purpose" && it.enabled }
                ?: all.firstOrNull { it.enabled }
        }
        return all.firstOrNull { it.enabled && (it.name.equals(key, true) || it.id.equals(key, true)) }
            ?: all.firstOrNull { it.name.equals(key, true) || it.id.equals(key, true) }
    }

    fun validateUserAgent(agent: CustomSubAgent) {
        val name = agent.name.trim()
        if (name.length !in 3..50) error("Name must be between 3 and 50 characters")
        if (!NAME_RE.matches(name)) error("Name can only contain letters, numbers, and hyphens")
        if (name in BUILTIN_NAMES) error("Agent name \"$name\" is reserved by a built-in agent")
        if (agent.description.trim().isEmpty()) error("Description is required")
        if (agent.systemPrompt.trim().isEmpty()) error("System prompt is required")
        if (agent.color.isNotBlank() && agent.color !in COLORS) error("Invalid color")
        if (agent.permissionMode.isNotBlank() && agent.permissionMode !in PERMISSION_MODES) {
            error("Invalid permission mode")
        }
    }

    fun isAllTools(tools: List<String>?): Boolean =
        tools.isNullOrEmpty() || tools.any { it.trim() == "*" }

    fun filterTools(all: List<Tool>, agent: CustomSubAgent): List<Tool> {
        val allowed = agent.tools
        val disallowed = agent.disallowedTools.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val afterAllow = if (isAllTools(allowed)) {
            all
        } else {
            val aliases = allowed.flatMap { toolAliases(it) }.toSet()
            all.filter { it.name in aliases || it.name in allowed }
        }
        if (disallowed.isEmpty()) return afterAllow
        val ban = disallowed.flatMap { toolAliases(it) }.toSet() + disallowed
        return afterAllow.filter { it.name !in ban }
    }

    fun toolAliases(name: String): Set<String> = when (name) {
        "Bash" -> setOf("Bash", "run_shell")
        "Read" -> setOf("Read", "read_file")
        "Write" -> setOf("Write", "write_file")
        "Edit" -> setOf("Edit", "edit_file", "apply_patch")
        "Grep" -> setOf("Grep", "grep")
        "Glob" -> setOf("Glob", "glob")
        "WebSearch" -> setOf("WebSearch", "web_search")
        "WebFetch" -> setOf("WebFetch", "browse")
        "TodoWrite" -> setOf("TodoWrite")
        "TodoRead" -> setOf("TodoRead")
        else -> setOf(name)
    }

    fun serializeMarkdown(agent: CustomSubAgent): String {
        val lines = mutableListOf(
            "name: \"${escapeYaml(agent.name)}\"",
            "description: \"${escapeYaml(agent.description)}\"",
        )
        if (agent.color.isNotBlank()) lines += "color: ${agent.color}"
        if (agent.model.isNotBlank() && agent.model != "inherit") lines += "model: ${agent.model}"
        if (!isAllTools(agent.tools)) {
            lines += "tools: [${agent.tools.joinToString(", ") { "\"$it\"" }}]"
        }
        if (agent.disallowedTools.isNotEmpty()) {
            lines += "disallowedTools: [${agent.disallowedTools.joinToString(", ") { "\"$it\"" }}]"
        }
        if (agent.skills.isNotEmpty()) {
            lines += "skills: [${agent.skills.joinToString(", ") { "\"$it\"" }}]"
        }
        if (agent.permissionMode.isNotBlank() && agent.permissionMode != "default") {
            lines += "permissionMode: ${agent.permissionMode}"
        }
        agent.maxTurns?.let { lines += "maxTurns: $it" }
        if (agent.background) lines += "background: true"
        if (agent.mcpServers.isNotEmpty()) {
            lines += "mcpServers: [${agent.mcpServers.joinToString(", ") { "\"$it\"" }}]"
        }
        return "---\n${lines.joinToString("\n")}\n---\n${agent.systemPrompt.trim()}\n"
    }

    fun parseMarkdown(content: String, path: String, scope: String): CustomSubAgent? {
        val normalized = content.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return null
        val lines = normalized.split("\n")
        val end = lines.indexOfFirst { it != lines.first() && it.trim() == "---" }
        if (end <= 0) return null
        val fm = lines.subList(1, end).joinToString("\n")
        val body = lines.drop(end + 1).joinToString("\n").trim()
        val map = parseFrontmatter(fm)
        val name = map["name"]?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        val description = map["description"]?.trim()?.trim('"')?.replace("\\n", "\n")?.takeIf { it.isNotEmpty() }
            ?: return null
        val color = map["color"]?.trim()?.takeIf { it in COLORS }
        val model = map["model"]?.trim().orEmpty().ifBlank { "inherit" }
        val permissionMode = map["permissionMode"]?.trim()?.takeIf { it in PERMISSION_MODES } ?: "default"
        val tools = parseStringList(map["tools"]) ?: listOf("*")
        val disallowed = parseStringList(map["disallowedTools"]).orEmpty()
        val skills = parseStringList(map["skills"]).orEmpty()
        val maxTurns = map["maxTurns"]?.trim()?.toIntOrNull()
        val background = map["background"]?.trim()?.lowercase() == "true"
        val mcpServers = parseStringList(map["mcpServers"]).orEmpty()
        val source = if (scope == "built-in") "built-in" else "user"
        return CustomSubAgent(
            id = if (scope == "built-in") builtInId(name) else "user:$name",
            name = name,
            description = description,
            systemPrompt = body,
            model = model,
            permissionMode = permissionMode,
            color = color ?: "blue",
            background = background,
            enabled = true,
            tools = tools,
            disallowedTools = disallowed,
            skills = skills,
            maxTurns = maxTurns,
            mcpServers = mcpServers,
            scope = scope,
            source = source,
            path = path,
            readOnly = scope != "user",
        )
    }

    private fun parseFrontmatter(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (line in raw.lines()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun parseStringList(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        if (t == "*") return listOf("*")
        val body = t.removePrefix("[").removeSuffix("]").trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').map { it.trim().trim('"').trim('\'') }.filter { it.isNotEmpty() }
    }

    private fun escapeYaml(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    fun permissionSystemHint(mode: String): String = when (mode) {
        "acceptEdits" -> "Permission mode: acceptEdits. Prefer applying code edits without extra confirmation prompts."
        "auto" -> "Permission mode: auto. Execute tools autonomously when safe."
        "bypassPermissions" -> "Permission mode: bypassPermissions. Proceed without permission checks."
        "dontAsk" -> "Permission mode: dontAsk. Do not ask the user for permission."
        "plan" -> "Permission mode: plan. Prefer read-only exploration and planning; avoid destructive edits unless required."
        else -> ""
    }

    fun agentSystemBlock(agent: CustomSubAgent): String = buildString {
        appendLine("You are the \"${agent.name}\" subagent.")
        if (agent.description.isNotBlank()) {
            appendLine(agent.description.trim())
        }
        if (agent.systemPrompt.isNotBlank()) {
            appendLine()
            appendLine(agent.systemPrompt.trim())
        }
        val perm = permissionSystemHint(agent.permissionMode)
        if (perm.isNotBlank()) {
            appendLine()
            appendLine(perm)
        }
        if (!isAllTools(agent.tools)) {
            appendLine()
            appendLine("Allowed tools: ${agent.tools.joinToString(", ")}")
        }
        if (agent.disallowedTools.isNotEmpty()) {
            appendLine("Disallowed tools: ${agent.disallowedTools.joinToString(", ")}")
        }
    }.trim()
}
