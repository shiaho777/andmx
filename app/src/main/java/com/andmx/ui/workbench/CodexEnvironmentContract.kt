package com.andmx.ui.workbench

import com.andmx.agent.ApprovalMode

internal enum class EnvironmentContractState(val label: String) {
    READY("已具备"),
    WATCH("注意"),
    GAP("缺口"),
}

internal data class EnvironmentContractLayer(
    val title: String,
    val state: EnvironmentContractState,
    val principle: String,
    val andmxSurface: String,
    val command: String,
)

internal data class CodexEnvironmentContract(
    val title: String,
    val layers: List<EnvironmentContractLayer>,
    val invariants: List<String>,
    val primaryCommand: String,
) {
    val readyCount: Int get() = layers.count { it.state == EnvironmentContractState.READY }
    val watchCount: Int get() = layers.count { it.state == EnvironmentContractState.WATCH }
    val gapCount: Int get() = layers.count { it.state == EnvironmentContractState.GAP }
}

internal fun buildCodexEnvironmentContract(
    instructionSummary: InstructionStackSummary,
    runtime: RuntimeEnvironmentSummary,
    policy: ToolPolicySummary,
    snapshot: AgentInspectorSnapshot,
    evidence: EvidenceLedger,
): CodexEnvironmentContract {
    val layers = listOf(
        EnvironmentContractLayer(
            title = "指令优先级",
            state = if (snapshot.apiConfigured) EnvironmentContractState.READY else EnvironmentContractState.GAP,
            principle = "系统、开发者、用户、项目和运行环境指令共同约束 agent; 不把内部安全提示当作普通可输出文本。",
            andmxSurface = "${instructionSummary.apiStatus} · ${instructionSummary.customInstructionStatus}",
            command = if (snapshot.apiConfigured) "/instructions" else "/model",
        ),
        EnvironmentContractLayer(
            title = "运行沙箱",
            state = when (runtime.level) {
                RuntimeEnvironmentLevel.READY -> EnvironmentContractState.READY
                RuntimeEnvironmentLevel.WATCH -> EnvironmentContractState.WATCH
                RuntimeEnvironmentLevel.LIMITED -> EnvironmentContractState.GAP
            },
            principle = "文件、终端、Diff、Browser 和工具输出要落到同一可追溯执行面。",
            andmxSurface = "${runtime.healthLabel} · ${runtime.executionSurface}",
            command = runtime.primaryCommand,
        ),
        EnvironmentContractLayer(
            title = "工具授权",
            state = when {
                policy.rows.isEmpty() -> EnvironmentContractState.GAP
                policy.mode == ApprovalMode.READ_ONLY && policy.denyCount > 0 -> EnvironmentContractState.WATCH
                else -> EnvironmentContractState.READY
            },
            principle = "读取、写入、执行、网络、MCP 和 Live UI 行为按风险进入自动、询问、阻止或用户接管路径。",
            andmxSurface = "自动 ${policy.autoCount} · 询问 ${policy.promptCount} · 阻止 ${policy.denyCount} · 安全边界 ${policy.boundaryRows.size}",
            command = "/policy",
        ),
        EnvironmentContractLayer(
            title = "上下文与恢复",
            state = when (snapshot.contextPressure) {
                "需要压缩" -> EnvironmentContractState.GAP
                "偏高" -> EnvironmentContractState.WATCH
                else -> EnvironmentContractState.READY
            },
            principle = "长线程通过上下文预算、handoff/resume 和报告保持可继续, 不把当前轮输出当作唯一记忆。",
            andmxSurface = "~${snapshot.tokenEstimate} tokens · ${snapshot.contextPressure} · ${snapshot.messageCount} 条消息",
            command = if (snapshot.contextPressure == "轻量") "/context" else "/handoff",
        ),
        EnvironmentContractLayer(
            title = "证据与不可见边界",
            state = if (evidence.items.isEmpty()) EnvironmentContractState.WATCH else EnvironmentContractState.READY,
            principle = "不能直接检查受保护宿主或内部提示词原文时, 用截图、文件、工具输出和验证形成可审计替代证据。",
            andmxSurface = "证据 ${evidence.items.size} · UI ${evidence.uiReferenceCount} · 验证 ${evidence.verificationCount}",
            command = if (snapshot.uiReferences > 0) "/references" else "/evidence",
        ),
    )
    val firstOpen = layers.firstOrNull { it.state == EnvironmentContractState.GAP }
        ?: layers.firstOrNull { it.state == EnvironmentContractState.WATCH }
    val title = when {
        layers.any { it.state == EnvironmentContractState.GAP } -> "Codex 环境契约仍有缺口"
        layers.any { it.state == EnvironmentContractState.WATCH } -> "Codex 环境契约需关注"
        else -> "Codex 环境契约已可审计"
    }
    return CodexEnvironmentContract(
        title = title,
        layers = layers,
        invariants = listOf(
            "内部系统提示词和运行时安全策略不作为普通消息泄露; 只展示可见配置和行为边界。",
            "高风险工具即使在完全访问模式下也要尊重 Live UI 安全边界和用户接管要求。",
            "截图/UI 参考进入 /references、/screenshot-extract、/trace、/visual-check 和 /report。",
            "所有实现性结论都要有文件、工具、截图、验证或报告证据支撑。",
        ),
        primaryCommand = firstOpen?.command ?: "/report",
    )
}

internal fun codexEnvironmentContractText(contract: CodexEnvironmentContract): String = buildString {
    appendLine("## Codex 环境契约")
    appendLine("- 状态: **${contract.title}**")
    appendLine("- 已具备: ${contract.readyCount}")
    appendLine("- 注意: ${contract.watchCount}")
    appendLine("- 缺口: ${contract.gapCount}")
    appendLine("- 建议入口: `${contract.primaryCommand}`")
    appendLine()
    appendLine("### 不变量")
    contract.invariants.forEach { appendLine("- $it") }
    appendLine()
    appendLine("### 契约层")
    contract.layers.forEach { layer ->
        appendLine("- **${layer.state.label}** · ${layer.title}")
        appendLine("  - 原则: ${layer.principle}")
        appendLine("  - AndMX 落点: ${layer.andmxSurface}")
        appendLine("  - 入口: `${layer.command}`")
    }
}
