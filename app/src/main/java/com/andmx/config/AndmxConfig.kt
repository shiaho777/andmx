package com.andmx.config

/**
 * AndMX configuration model — mirrors Codex's ConfigToml structure.
 *
 * Fields are organized to match Codex's config.toml sections:
 * - Core model settings (model, provider, reasoning, verbosity)
 * - Sandbox & permissions (sandbox_mode, approval_policy, permissions)
 * - Context management (compact_prompt, project_doc, instructions)
 * - Memory configuration (generate, consolidation, extract_model)
 * - Subsystem toggles (hooks, plugins, skills, mcp_servers)
 * - Project trust levels
 * - Profiles
 */

/** Top-level configuration, loaded from TOML or DataStore fallback. */
data class AndmxConfig(
    // ── Model ──
    val model: String = "",
    val reviewModel: String = "",
    val modelProvider: String = "",
    val modelContextWindow: Int = 128_000,
    val modelAutoCompactTokenLimit: Int = 0,
    val modelReasoningEffort: String = "off",
    val planModeReasoningEffort: String = "medium",
    val modelReasoningSummary: Boolean = false,
    val modelVerbosity: String = "medium",
    val modelSupportsReasoningSummaries: Boolean = false,
    val personality: String = "务实",

    // ── Sandbox & Permissions ──
    val sandboxMode: SandboxMode = SandboxMode.WORKSPACE_WRITE,
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.ON_REQUEST,
    val permissions: List<String> = emptyList(),
    val shellEnvironmentPolicy: String = "",
    val allowLoginShells: Boolean = false,
    val networkAccess: String = "enabled",

    // ── Context Management ──
    val instructions: String = "",
    val developerInstructions: String = "",
    val includePermissionsInstructions: Boolean = true,
    val includeAppsInstructions: Boolean = false,
    val includeCollaborationModeInstructions: Boolean = false,
    val includeEnvironmentContext: Boolean = true,
    val compactPrompt: String = "",
    val projectDocMaxBytes: Int = 32_768,
    val projectDocFallbackFilenames: List<String> = listOf("AGENTS.md", "CLAUDE.md", "CODEX.md"),
    val toolOutputTokenLimit: Int = 8_192,
    val backgroundTerminalMaxTimeout: Int = 30,

    // ── Memory ──
    val generateMemories: Boolean = true,
    val useMemories: Boolean = true,
    val memoryDedicatedTools: Boolean = false,
    val maxRawMemoriesForConsolidation: Int = 20,
    val maxUnusedDays: Int = 30,
    val maxRolloutAgeDays: Int = 14,
    val maxRolloutsPerStartup: Int = 3,
    val minRolloutIdleHours: Int = 1,
    val minRateLimitRemainingPercent: Int = 10,
    val extractModel: String = "",
    val consolidationModel: String = "",
    val disableOnExternalContext: Boolean = false,

    // ── Features ──
    val goals: Boolean = true,
    val jsRepl: Boolean = false,
    val hideAgentReasoning: Boolean = false,
    val showRawAgentReasoning: Boolean = false,
    val checkForUpdateOnStartup: Boolean = false,
    val disablePasteBurst: Boolean = false,
    val disableResponseStorage: Boolean = false,

    // ── History & Logging ──
    val historySqliteHome: String = "",
    val logDir: String = "",
    val debug: Boolean = false,

    // ── Projects (trust levels) ──
    val projects: Map<String, ProjectTrust> = emptyMap(),

    // ── MCP Servers (name → command) ──
    val mcpServers: Map<String, McpServerConfig> = emptyMap(),

    // ── Model Providers (name → config) ──
    val modelProviders: Map<String, ModelProviderConfig> = emptyMap(),

    // ── Profile ──
    val profile: String = "",
    val profiles: Map<String, AndmxConfig> = emptyMap(),
)

enum class SandboxMode(val wire: String) {
    READ_ONLY("read-only"),
    WORKSPACE_WRITE("workspace-write"),
    DANGER_FULL_ACCESS("danger-full-access");

    companion object {
        fun from(s: String): SandboxMode = entries.firstOrNull { it.wire == s || it.name.equals(s, true) }
            ?: WORKSPACE_WRITE
    }
}

enum class ApprovalPolicy(val wire: String) {
    NEVER("never"),
    ON_FAILURE("on-failure"),
    ON_REQUEST("on-request"),
    ALWAYS("always");

    companion object {
        fun from(s: String): ApprovalPolicy = entries.firstOrNull { it.wire == s || it.name.equals(s, true) }
            ?: ON_REQUEST
    }
}

data class ProjectTrust(
    val trustLevel: String = "untrusted",
)

data class McpServerConfig(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val startupTimeoutSec: Int = 30,
)

data class ModelProviderConfig(
    val name: String,
    val baseUrl: String,
    val wireApi: String = "chat_completions",
    val requiresOpenaiAuth: Boolean = true,
    val envKey: String? = null,
    val requestMaxRetries: Int = 2,
    val streamMaxRetries: Int = 1,
    val streamIdleTimeoutMs: Long = 120_000,
    val supportsWebsockets: Boolean = false,
)
