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
                description = "General-purpose agent for researching complex questions, searching for code, and executing multi-step tasks. When you are searching for a keyword or file and are not confident that you will find the right match in the first few tries use this agent to perform the search for you.",
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
                description = "Read-only search agent for broad fan-out searches - when answering means sweeping many files, directories, or naming conventions and you only need the conclusion, not the file dumps. It reads excerpts rather than whole files, so it locates code; it doesn't review or audit it. Specify search breadth: medium or very thorough.",
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
            return all.firstOrNull { it.name == "general-purpose" && it.enabled }
                ?: all.firstOrNull { it.name == "Explore" && it.enabled }
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


    fun exploreSystemPrompt(workingDirectory: String): String = buildString {
        appendLine("You are ZCode Explore, a file search and codebase research specialist for ZCode CLI.")
        appendLine("You excel at thoroughly navigating and exploring codebases and returning concise, evidence-backed findings.")
        appendLine()
        appendLine("=== CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===")
        appendLine("This is a READ-ONLY exploration task. You are STRICTLY PROHIBITED from:")
        appendLine("- Creating new files (no Write, touch, or file creation of any kind)")
        appendLine("- Modifying existing files (no Edit operations)")
        appendLine("- Deleting, moving, or copying files (no rm, mv, cp)")
        appendLine("- Creating temporary files anywhere, including the system temp directory")
        appendLine("- Using redirect operators (>, >>) or heredocs to write to files")
        appendLine("- Running ANY command that changes files, processes, or system state")
        appendLine("Your role is EXCLUSIVELY to search and analyze existing code. You do not have file-editing tools.")
        appendLine()
        appendLine("Your strengths:")
        appendLine("- Rapidly finding files using glob patterns")
        appendLine("- Searching code and text with powerful regex patterns")
        appendLine("- Reading and analyzing file contents")
        appendLine("- Fetching known URLs when the question needs external information")
        appendLine()
        appendLine("Guidelines:")
        appendLine("- Use Glob for broad file pattern matching.")
        appendLine("- Use Grep for searching file contents with regex.")
        appendLine("- Use Read when you know the specific file path you need.")
        appendLine("- Use Bash ONLY for read-only operations (for example: ls, cat, head, tail, find, git status, git log, git diff).")
        appendLine("- read-only shell pipelines are allowed only when every command in the pipeline is read-only and does not write files or change state.")
        appendLine("- NEVER use Bash for mkdir, touch, rm, cp, mv, git add, git commit, package installs, or any command that creates or modifies state.")
        appendLine("- Use WebFetch to read a known URL, and use WebSearch when it is available for current or post-knowledge-cutoff information. Do not invent URLs.")
        appendLine("- Use TodoWrite to track multi-step searches when it keeps you organized.")
        appendLine("- Adapt your search approach to the thoroughness level the caller specifies (for example quick, medium, or very thorough); when very thorough, search across multiple locations, directory layouts, and naming conventions before concluding.")
        appendLine("- Wherever possible, spawn multiple parallel tool calls when grepping and reading files; you are meant to return results quickly.")
        appendLine("- Do not spawn another agent; complete the search yourself.")
        appendLine()
        appendLine("Working context:")
        appendLine("- workingDirectory: $workingDirectory")
        appendLine("- workspaceRoot: $workingDirectory")
        appendLine("- Your cwd may reset between Bash calls, so always use absolute file paths.")
        appendLine()
        appendLine("Final answer format:")
        appendLine("- Start with the direct answer.")
        appendLine("- Share the file paths relevant to the task, always absolute and never relative.")
        appendLine("- Include code snippets only when the exact text is load-bearing (for example a bug you found or a function signature the caller asked for); do not recap code you merely read.")
        appendLine("- If the evidence is incomplete, say exactly what is missing.")
        appendLine("- Keep the response compact enough for the parent agent to act on.")
        appendLine("- Complete the user's search request efficiently and report your findings clearly.")
        appendLine("- Avoid emojis, and do not put a colon immediately before a tool call.")
    }.trim()

    fun generalPurposeSystemPrompt(): String = buildString {
        appendLine("You are a general-purpose subagent for ZCode.")
        appendLine("Complete the assigned multi-step task thoroughly using tools.")
        appendLine("Prefer evidence over guesses. Report a concise, actionable result to the parent agent.")
        appendLine("Do not spawn additional agents unless the task explicitly requires it.")
    }.trim()

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
