package com.andmx.agent

/** Graduated autonomy, mirroring Codex's 完全访问 / 按需 / 只读. */
enum class ApprovalMode(val label: String) {
    FULL("完全访问"),
    ASK("按需"),
    READ_ONLY("只读");

    companion object {
        fun from(s: String): ApprovalMode = entries.firstOrNull { it.name.equals(s, true) } ?: ASK
        fun cycle(m: ApprovalMode): ApprovalMode = entries[(m.ordinal + 1) % entries.size]
    }
}

/** What to do with a given tool call under a mode. */
enum class Decision { AUTO, PROMPT, DENY }

object ApprovalPolicy {
    fun decide(mode: ApprovalMode, risk: ToolRisk): Decision = when (mode) {
        ApprovalMode.FULL -> Decision.AUTO
        ApprovalMode.READ_ONLY -> if (risk == ToolRisk.READ) Decision.AUTO else Decision.DENY
        ApprovalMode.ASK -> if (risk == ToolRisk.READ) Decision.AUTO else Decision.PROMPT
    }
}
