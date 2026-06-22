package com.andmx.config

/**
 * Lightweight TOML parser for AndMX config files.
 *
 * Supports the subset of TOML needed for [AndmxConfig]:
 * - [section] and [section.subsection] headers
 * - key = "string value"
 * - key = 123 (integer)
 * - key = true / false (boolean)
 * - key = ["a", "b"] (string array)
 * - # comments
 * - Blank lines
 *
 * Not supported: multi-line strings, tables of arrays, dates, floats.
 * This keeps the parser small (~150 lines) and avoids a TOML dependency.
 */
internal object TomlParser {

    fun parse(text: String): AndmxConfig {
        val sections = mutableMapOf<String, MutableMap<String, Any>>()
        var currentSection = ""

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            // Strip inline comments
            val commentIdx = line.indexOf('#')
            val content = if (commentIdx >= 0 && !isInString(line, commentIdx)) line.substring(0, commentIdx).trim() else line

            if (content.startsWith("[") && content.endsWith("]")) {
                currentSection = content.removeSurrounding("[", "]").trim()
                sections.getOrPut(currentSection) { mutableMapOf() }
                continue
            }

            val eqIdx = content.indexOf('=')
            if (eqIdx < 0) continue
            val key = content.substring(0, eqIdx).trim()
            val value = content.substring(eqIdx + 1).trim()
            val parsed = parseValue(value) ?: continue

            val sectionMap = sections.getOrPut(currentSection) { mutableMapOf() }
            sectionMap[key] = parsed
        }

        return buildConfig(sections)
    }

    private fun parseValue(s: String): Any? {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.removeSurrounding("\"").replace("\\\"", "\"").replace("\\n", "\n")
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            val inner = s.removeSurrounding("[", "]")
            if (inner.isBlank()) return emptyList<String>()
            return inner.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }
        }
        if (s == "true") return true
        if (s == "false") return false
        return s.toIntOrNull()
    }

    private fun isInString(line: String, idx: Int): Boolean {
        var inStr = false
        for (i in 0 until idx) {
            if (line[i] == '"') inStr = !inStr
        }
        return inStr
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildConfig(sections: Map<String, Map<String, Any>>): AndmxConfig {
        fun s(section: String, key: String, default: String = ""): String =
            (sections[section]?.get(key) as? String) ?: default
        fun i(section: String, key: String, default: Int = 0): Int =
            (sections[section]?.get(key) as? Int) ?: default
        fun b(section: String, key: String, default: Boolean = false): Boolean =
            (sections[section]?.get(key) as? Boolean) ?: default
        fun lst(section: String, key: String): List<String> =
            (sections[section]?.get(key) as? List<String>) ?: emptyList()

        // Parse projects
        val projects = mutableMapOf<String, ProjectTrust>()
        sections.filter { it.key.startsWith("projects.") }.forEach { (key, value) ->
            val path = key.removePrefix("projects.")
            val trust = (value["trust_level"] as? String) ?: "untrusted"
            projects[path] = ProjectTrust(trust)
        }

        // Parse MCP servers
        val mcpServers = mutableMapOf<String, McpServerConfig>()
        sections.filter { it.key.startsWith("mcp_servers.") }.forEach { (key, value) ->
            val name = key.removePrefix("mcp_servers.")
            val cmd = (value["command"] as? String) ?: return@forEach
            val args = (value["args"] as? List<String>) ?: emptyList()
            mcpServers[name] = McpServerConfig(command = cmd, args = args)
        }

        // Parse model providers
        val modelProviders = mutableMapOf<String, ModelProviderConfig>()
        sections.filter { it.key.startsWith("model_providers.") }.forEach { (key, value) ->
            val name = key.removePrefix("model_providers.")
            modelProviders[name] = ModelProviderConfig(
                name = name,
                baseUrl = (value["base_url"] as? String) ?: "",
                wireApi = (value["wire_api"] as? String) ?: "chat_completions",
                requiresOpenaiAuth = (value["requires_openai_auth"] as? Boolean) ?: true,
                envKey = value["env_key"] as? String,
            )
        }

        return AndmxConfig(
            model = s("", "model"),
            reviewModel = s("", "review_model"),
            modelProvider = s("", "model_provider"),
            modelContextWindow = i("", "model_context_window", 128_000),
            modelReasoningEffort = s("", "model_reasoning_effort", "off"),
            personality = s("", "personality", "务实"),
            modelReasoningSummary = b("", "model_reasoning_summary"),
            modelVerbosity = s("", "model_verbosity", "medium"),
            developerInstructions = s("", "developer_instructions"),
            instructions = s("", "instructions"),
            compactPrompt = s("", "compact_prompt"),
            disableResponseStorage = b("", "disable_response_storage"),
            sandboxMode = SandboxMode.from(s("", "sandbox_mode", "workspace-write")),
            approvalPolicy = ApprovalPolicy.from(s("", "default_permissions", "on-request")),
            networkAccess = s("", "network_access", "enabled"),
            includePermissionsInstructions = b("", "include_permissions_instructions", true),
            includeEnvironmentContext = b("", "include_environment_context", true),
            projectDocMaxBytes = i("", "project_doc_max_bytes", 32_768),
            toolOutputTokenLimit = i("", "tool_output_token_limit", 8_192),
            backgroundTerminalMaxTimeout = i("", "background_terminal_max_timeout", 30),
            generateMemories = b("memories", "generate_memories", true),
            useMemories = b("memories", "use_memories", true),
            maxRawMemoriesForConsolidation = i("memories", "max_raw_memories_for_consolidation", 20),
            maxUnusedDays = i("memories", "max_unused_days", 30),
            maxRolloutAgeDays = i("memories", "max_rollout_age_days", 14),
            maxRolloutsPerStartup = i("memories", "max_rollouts_per_startup", 3),
            minRolloutIdleHours = i("memories", "min_rollout_idle_hours", 1),
            minRateLimitRemainingPercent = i("memories", "min_rate_limit_remaining_percent", 10),
            extractModel = s("memories", "extract_model"),
            consolidationModel = s("memories", "consolidation_model"),
            disableOnExternalContext = b("memories", "disable_on_external_context"),
            goals = b("features", "goals", true),
            jsRepl = b("features", "js_repl"),
            hideAgentReasoning = b("", "hide_agent_reasoning"),
            showRawAgentReasoning = b("", "show_raw_agent_reasoning"),
            checkForUpdateOnStartup = b("", "check_for_update_on_startup"),
            disablePasteBurst = b("", "disable_paste_burst"),
            projects = projects,
            mcpServers = mcpServers,
            modelProviders = modelProviders,
            projectDocFallbackFilenames = lst("", "project_doc_fallback_filenames").ifEmpty { listOf("AGENTS.md", "CLAUDE.md", "CODEX.md") },
        )
    }
}

/** Serializes [AndmxConfig] back to TOML format. */
internal object TomlWriter {

    fun write(config: AndmxConfig): String = buildString {
        // Core settings
        ifT("model", config.model)
        ifT("review_model", config.reviewModel)
        ifT("model_provider", config.modelProvider)
        line("model_context_window", config.modelContextWindow)
        ifT("model_reasoning_effort", config.modelReasoningEffort)
        ifT("personality", config.personality)
        line("model_reasoning_summary", config.modelReasoningSummary)
        ifT("model_verbosity", config.modelVerbosity)
        line("disable_response_storage", config.disableResponseStorage)
        ifT("sandbox_mode", config.sandboxMode.wire)
        ifT("default_permissions", config.approvalPolicy.wire)
        ifT("network_access", config.networkAccess)
        ifT("instructions", config.instructions)
        ifT("developer_instructions", config.developerInstructions)
        line("include_permissions_instructions", config.includePermissionsInstructions)
        line("include_environment_context", config.includeEnvironmentContext)
        line("project_doc_max_bytes", config.projectDocMaxBytes)
        line("tool_output_token_limit", config.toolOutputTokenLimit)
        ifT("compact_prompt", config.compactPrompt)
        line("check_for_update_on_startup", config.checkForUpdateOnStartup)
        line("hide_agent_reasoning", config.hideAgentReasoning)

        // Features
        if (config.goals || config.jsRepl) {
            appendLine("\n[features]")
            line("goals", config.goals)
            line("js_repl", config.jsRepl)
        }

        // Memory
        appendLine("\n[memories]")
        line("generate_memories", config.generateMemories)
        line("use_memories", config.useMemories)
        line("max_raw_memories_for_consolidation", config.maxRawMemoriesForConsolidation)
        line("max_unused_days", config.maxUnusedDays)
        line("max_rollout_age_days", config.maxRolloutAgeDays)
        line("min_rate_limit_remaining_percent", config.minRateLimitRemainingPercent)
        ifT("extract_model", config.extractModel)
        ifT("consolidation_model", config.consolidationModel)
        line("disable_on_external_context", config.disableOnExternalContext)

        // Projects
        for ((path, trust) in config.projects) {
            appendLine("\n[projects.\"$path\"]")
            ifT("trust_level", trust.trustLevel)
        }

        // MCP Servers
        for ((name, server) in config.mcpServers) {
            appendLine("\n[mcp_servers.$name]")
            ifT("command", server.command)
            if (server.args.isNotEmpty()) {
                append("args = [")
                append(server.args.joinToString(", ") { "\"$it\"" })
                appendLine("]")
            }
            line("startup_timeout_sec", server.startupTimeoutSec)
        }

        // Model Providers
        for ((name, provider) in config.modelProviders) {
            appendLine("\n[model_providers.$name]")
            ifT("name", provider.name)
            ifT("base_url", provider.baseUrl)
            ifT("wire_api", provider.wireApi)
            line("requires_openai_auth", provider.requiresOpenaiAuth)
            provider.envKey?.let { ifT("env_key", it) }
        }
    }

    private fun StringBuilder.line(key: String, value: Any) {
        append(key).append(" = ").append(value.toString()).append('\n')
    }

    private fun StringBuilder.ifT(key: String, value: String) {
        if (value.isNotBlank()) append(key).append(" = \"").append(value).append("\"\n")
    }
}
