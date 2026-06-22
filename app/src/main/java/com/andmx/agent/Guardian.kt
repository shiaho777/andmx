package com.andmx.agent

import com.andmx.config.SandboxMode
import com.andmx.exec.files.GuestFs

/**
 * Guardian risk assessment system — mirrors Codex's Guardian.
 *
 * Classifies each tool call into a risk level and makes an approval decision
 * based on the user's current authorization level and sandbox mode.
 *
 * Enhanced (v7) with:
 * - [ActionType] classification (execve, applyPatch, networkAccess, mcpToolCall)
 * - Managed filesystem permissions with glob scan
 * - Permission profile overlays for "always allow" session grants
 * - Read-only inspection before classifying destructive operations
 * - rm -rf risk refinement: small/empty targets downgraded to LOW/MEDIUM
 */
object Guardian {

    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    enum class UserAuthorization { NONE, LOW, MEDIUM, HIGH }

    enum class Decision { ALLOW, PROMPT, DENY }

    data class Assessment(
        val riskLevel: RiskLevel,
        val decision: Decision,
        val rationale: String,
        val userAuthorization: UserAuthorization,
        val actionType: ActionType,
    )

    /**
     * Assess a shell command's risk.
     * Returns the risk level and a recommended decision.
     */
    fun assessShell(command: String, mode: ApprovalMode): Assessment {
        val actionType = classifyAction(command)
        val risk = classifyShellRisk(command)
        val auth = mapModeToAuth(mode)
        val decision = decide(risk, auth)
        return Assessment(risk, decision, rationaleFor(risk, command), auth, actionType)
    }

    /**
     * Assess a shell command with read-only inspection of the target.
     * Mirrors Codex's guidance: "attempt a read-only inspection of the target path first."
     */
    suspend fun assessShellWithInspection(
        command: String,
        mode: ApprovalMode,
        fs: GuestFs? = null,
    ): Assessment {
        val baseAssessment = assessShell(command, mode)

        // Refine rm -rf risk by inspecting target
        val rmMatch = Regex("\\brm\\s+(-[a-z]*r[a-z]*f?|-[a-z]*f[a-z]*r?)\\b\\s+(\\S+)").find(command.lowercase())
        if (rmMatch != null && fs != null) {
            val target = rmMatch.groupValues[2].trim().removeSurrounding("'").removeSurrounding("\"")
            val refined = refineRmRfRisk(baseAssessment.riskLevel, target, fs)
            if (refined != baseAssessment.riskLevel) {
                val auth = mapModeToAuth(mode)
                val decision = decide(refined, auth)
                return Assessment(
                    refined, decision,
                    "rm -rf 目标检查后降级: ${rationaleFor(refined, target)}",
                    auth, ActionType.EXECVE,
                )
            }
        }
        return baseAssessment
    }

    /**
     * Assess a file write operation's risk.
     */
    fun assessFileWrite(path: String, mode: ApprovalMode): Assessment {
        val risk = classifyFileWriteRisk(path)
        val auth = mapModeToAuth(mode)
        val decision = decide(risk, auth)
        return Assessment(risk, decision, rationaleFor(risk, path), auth, ActionType.APPLY_PATCH)
    }

    /**
     * Assess a network operation's risk.
     */
    fun assessNetwork(url: String, mode: ApprovalMode): Assessment {
        val risk = if (url.startsWith("https://")) RiskLevel.MEDIUM else RiskLevel.HIGH
        val auth = mapModeToAuth(mode)
        val decision = decide(risk, auth)
        return Assessment(risk, decision, "网络访问: $url", auth, ActionType.NETWORK_ACCESS)
    }

    /**
     * Assess an MCP tool call's risk.
     */
    fun assessMcpTool(toolName: String, mode: ApprovalMode): Assessment {
        val risk = RiskLevel.HIGH // MCP tools are unknown → conservative
        val auth = mapModeToAuth(mode)
        val decision = decide(risk, auth)
        return Assessment(risk, decision, "MCP 工具调用: $toolName", auth, ActionType.MCP_TOOL_CALL)
    }

    /** Map sandbox mode to approval behavior. */
    fun assessForSandbox(action: ActionType, mode: SandboxMode): Decision = when (mode) {
        SandboxMode.DANGER_FULL_ACCESS -> Decision.ALLOW
        SandboxMode.WORKSPACE_WRITE -> when (action) {
            ActionType.EXECVE, ActionType.APPLY_PATCH -> Decision.PROMPT
            ActionType.NETWORK_ACCESS -> Decision.ALLOW
            ActionType.MCP_TOOL_CALL -> Decision.PROMPT
            ActionType.REQUEST_PERMISSIONS -> Decision.ALLOW
        }
        SandboxMode.READ_ONLY -> when (action) {
            ActionType.REQUEST_PERMISSIONS -> Decision.ALLOW
            else -> Decision.DENY
        }
    }

    private fun classifyAction(command: String): ActionType {
        val c = command.lowercase()
        return when {
            Regex("\\b(curl|wget|scp|rsync|ssh|ftp|nc)\\b").containsMatchIn(c) -> ActionType.NETWORK_ACCESS
            else -> ActionType.EXECVE
        }
    }

    private fun decide(risk: RiskLevel, auth: UserAuthorization): Decision = when {
        risk == RiskLevel.CRITICAL -> Decision.DENY
        risk == RiskLevel.HIGH && auth < UserAuthorization.MEDIUM -> Decision.DENY
        risk == RiskLevel.HIGH -> Decision.PROMPT
        risk == RiskLevel.MEDIUM && auth < UserAuthorization.LOW -> Decision.PROMPT
        else -> Decision.ALLOW
    }

    private fun mapModeToAuth(mode: ApprovalMode): UserAuthorization = when (mode) {
        ApprovalMode.FULL -> UserAuthorization.HIGH
        ApprovalMode.ASK -> UserAuthorization.LOW
        ApprovalMode.READ_ONLY -> UserAuthorization.NONE
    }

    private fun classifyShellRisk(cmd: String): RiskLevel {
        val c = cmd.trim().lowercase()

        // CRITICAL: irreversible damage
        if (Regex("\\brm\\s+(-[a-z]*r[a-z]*f?|-[a-z]*f[a-z]*r?)\\b.*(/|\\*|~)").containsMatchIn(c)) return RiskLevel.CRITICAL
        if (Regex("\\b(mkfs|dd\\s+if=|shred|wipe)\\b").containsMatchIn(c)) return RiskLevel.CRITICAL
        if (Regex(":\\(\\)\\s*\\{.*\\}\\s*;:").containsMatchIn(c)) return RiskLevel.CRITICAL
        if (Regex("\\b(shutdown|reboot|halt|poweroff)\\b").containsMatchIn(c)) return RiskLevel.CRITICAL
        if (Regex("\\b(chmod|chown)\\s+(-R\\s+)?0?777\\b").containsMatchIn(c)) return RiskLevel.CRITICAL

        // HIGH: network or system modification
        if (Regex("\\b(curl|wget|scp|rsync|ssh)\\b").containsMatchIn(c)) return RiskLevel.HIGH
        if (Regex("\\bgit\\s+(push|pull|fetch|clone|reset --hard)\\b").containsMatchIn(c)) return RiskLevel.HIGH
        if (Regex("\\b(kill|killall|pkill)\\s+-9\\b").containsMatchIn(c)) return RiskLevel.HIGH
        if (Regex("\\b(apk|apt|pip|npm)\\s+(install|add|remove|del)\\b").containsMatchIn(c)) return RiskLevel.HIGH

        // MEDIUM: local file modifications
        if (Regex("\\b(rm|rmdir|mv|chmod|chown)\\b").containsMatchIn(c)) return RiskLevel.MEDIUM
        if (Regex("\\bgit\\s+(add|commit|merge|rebase|checkout|stash)\\b").containsMatchIn(c)) return RiskLevel.MEDIUM

        // LOW: read-only
        return RiskLevel.LOW
    }

    private fun classifyFileWriteRisk(path: String): RiskLevel {
        val p = path.lowercase()

        // CRITICAL: system/config files
        if (p.startsWith("/etc/") || p.startsWith("/proc/") || p.startsWith("/sys/")) return RiskLevel.CRITICAL
        if (p.contains("authorized_keys") || p.contains(".ssh/")) return RiskLevel.CRITICAL

        // HIGH: outside workspace
        if (!p.startsWith("/root/") && !p.startsWith("/home/") && !p.startsWith("./")) return RiskLevel.HIGH

        // MEDIUM: normal workspace files
        return RiskLevel.MEDIUM
    }

    /**
     * Refine rm -rf risk by inspecting the target path.
     * Mirrors Codex's guidance:
     * - "If a read-only check shows the target is missing, empty, or narrowly scoped,
     *   such as a single small file or empty directory, this is usually low or medium."
     */
    private suspend fun refineRmRfRisk(baseRisk: RiskLevel, target: String, fs: GuestFs): RiskLevel {
        if (baseRisk != RiskLevel.CRITICAL && baseRisk != RiskLevel.HIGH) return baseRisk

        // Check if target exists
        val exists = runCatching { fs.exists(target) }.getOrDefault(false)
        if (!exists) return RiskLevel.LOW // Missing target → low risk

        // Check if it's a directory by trying to list it
        val isDir = runCatching { fs.list(target) }.isSuccess
        if (!isDir) {
            // It's a file — check size via readText (best effort)
            val size = runCatching { fs.readText(target, limit = 102_400).length.toLong() }.getOrDefault(-1L)
            if (size >= 0) return RiskLevel.LOW // Small file (< 100KB) → low
            return RiskLevel.MEDIUM // Large file → medium
        }

        // Check if directory is empty
        val children = runCatching { fs.list(target) }.getOrDefault(emptyList())
        if (children.isEmpty()) return RiskLevel.LOW // Empty directory → low
        if (children.size <= 5) return RiskLevel.MEDIUM // Few items → medium

        return baseRisk // Non-empty directory → keep original risk
    }

    private fun rationaleFor(risk: RiskLevel, context: String): String = when (risk) {
        RiskLevel.CRITICAL -> "不可逆的危险操作"
        RiskLevel.HIGH -> "网络或系统修改操作"
        RiskLevel.MEDIUM -> "本地文件修改"
        RiskLevel.LOW -> "只读操作"
    }
}
