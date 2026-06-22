package com.andmx.ui.workbench

internal enum class ArchitectureState(val label: String) {
    READY("闭环清晰"),
    WATCH("需要关注"),
    GAP("链路缺口"),
}

internal data class ArchitectureLayer(
    val title: String,
    val state: ArchitectureState,
    val detail: String,
    val command: String,
)

internal data class AgentArchitectureBlueprint(
    val state: ArchitectureState,
    val title: String,
    val layers: List<ArchitectureLayer>,
    val flow: List<String>,
    val invariants: List<String>,
    val primaryCommand: String,
) {
    val readyCount: Int get() = layers.count { it.state == ArchitectureState.READY }
    val watchCount: Int get() = layers.count { it.state == ArchitectureState.WATCH }
    val gapCount: Int get() = layers.count { it.state == ArchitectureState.GAP }
}

internal fun buildAgentArchitectureBlueprint(
    snapshot: AgentInspectorSnapshot,
    runtime: RuntimeEnvironmentSummary,
    evidence: EvidenceLedger,
    checklist: SessionChecklistSummary,
    parity: CodexParityAudit,
): AgentArchitectureBlueprint {
    val layers = listOf(
        ArchitectureLayer(
            title = "目标与会话状态",
            state = if (snapshot.goalText.isBlank()) ArchitectureState.GAP else ArchitectureState.READY,
            detail = if (snapshot.goalText.isBlank()) "还没有目标锚点" else "${snapshot.goalPhaseLabel} · ${snapshot.goalText}",
            command = "/plan",
        ),
        ArchitectureLayer(
            title = "指令栈与模型配置",
            state = if (snapshot.apiConfigured) ArchitectureState.READY else ArchitectureState.GAP,
            detail = "${snapshot.model} · ${snapshot.reasoningEffort} · ${snapshot.approvalModeLabel}",
            command = "/instructions",
        ),
        ArchitectureLayer(
            title = "工具路由与审批",
            state = when {
                snapshot.pendingApprovals > 0 -> ArchitectureState.WATCH
                snapshot.totalTools > 0 -> ArchitectureState.READY
                else -> ArchitectureState.GAP
            },
            detail = "内置 ${snapshot.builtInTools} · 总工具 ${snapshot.totalTools} · MCP ${snapshot.mcpServers} · 待授权 ${snapshot.pendingApprovals}",
            command = "/tools",
        ),
        ArchitectureLayer(
            title = "执行环境",
            state = when (runtime.level) {
                RuntimeEnvironmentLevel.READY -> ArchitectureState.READY
                RuntimeEnvironmentLevel.WATCH -> ArchitectureState.WATCH
                RuntimeEnvironmentLevel.LIMITED -> ArchitectureState.GAP
            },
            detail = "${runtime.healthLabel} · ${runtime.executionSurface}",
            command = runtime.primaryCommand,
        ),
        ArchitectureLayer(
            title = "工作区与变更面板",
            state = if (snapshot.changedFiles > 0) ArchitectureState.WATCH else ArchitectureState.READY,
            detail = "待审变更 ${snapshot.changedFiles} · 来源 ${snapshot.sourceLinks}",
            command = if (snapshot.changedFiles > 0) "/changes" else "/status",
        ),
        ArchitectureLayer(
            title = "截图/UI 参考",
            state = when {
                snapshot.uiReferences > 0 && snapshot.toolEvents == 0 && snapshot.changedFiles == 0 -> ArchitectureState.WATCH
                snapshot.uiReferences > 0 -> ArchitectureState.READY
                else -> ArchitectureState.WATCH
            },
            detail = if (snapshot.uiReferences > 0) "${snapshot.uiReferences} 个 UI 参考已进入任务链路" else "等待用户截图或附件参考",
            command = "/references",
        ),
        ArchitectureLayer(
            title = "证据账本",
            state = if (evidence.items.isEmpty()) ArchitectureState.WATCH else ArchitectureState.READY,
            detail = "文件 ${evidence.fileCount} · 网页 ${evidence.webCount} · UI ${evidence.uiReferenceCount} · 验证 ${evidence.verificationCount}",
            command = "/evidence",
        ),
        ArchitectureLayer(
            title = "验证与交付",
            state = when {
                checklist.missingCount > 0 || parity.gapCount > 0 -> ArchitectureState.GAP
                checklist.watchCount > 0 || parity.watchCount > 0 -> ArchitectureState.WATCH
                else -> ArchitectureState.READY
            },
            detail = "清单缺口 ${checklist.missingCount} · 注意 ${checklist.watchCount} · 对标缺口 ${parity.gapCount}",
            command = "/report",
        ),
    )

    val state = when {
        layers.any { it.state == ArchitectureState.GAP } -> ArchitectureState.GAP
        layers.any { it.state == ArchitectureState.WATCH } -> ArchitectureState.WATCH
        else -> ArchitectureState.READY
    }
    return AgentArchitectureBlueprint(
        state = state,
        title = when (state) {
            ArchitectureState.READY -> "Codex 式执行闭环已成形"
            ArchitectureState.WATCH -> "架构闭环接近可用"
            ArchitectureState.GAP -> "架构链路仍有缺口"
        },
        layers = layers,
        flow = listOf(
            "用户目标进入 Chat, 形成当前线程目标与任务计划",
            "指令栈、模型配置、授权模式和工具列表共同决定 agent 可做什么",
            "工具调用进入审批策略, 再落到文件、终端、网络、MCP 或 Android/proot 执行面",
            "工具输出、截图、网页、文件和变更汇入证据账本与 Diff/Files/Terminal 工作面板",
            "验证摘要、会话清单、Codex 对标和交付报告共同决定能否收束",
        ),
        invariants = listOf(
            "先建立事实再修改: 读取项目、上下文、截图和相关文件优先",
            "每次写入都应能被 diff、证据账本和交付报告追踪",
            "执行、网络、写入和 Live UI 行为必须受授权模式与安全边界约束",
            "长线程需要 handoff/resume 保留目标、计划、变更和验证证据",
            "UI 复刻任务必须把截图参考转成可实现、可验证的蓝图",
        ),
        primaryCommand = layers.firstOrNull { it.state == ArchitectureState.GAP }?.command
            ?: layers.firstOrNull { it.state == ArchitectureState.WATCH }?.command
            ?: "/report",
    )
}

internal fun agentArchitectureText(blueprint: AgentArchitectureBlueprint): String = buildString {
    appendLine("## 系统架构蓝图")
    appendLine("- 状态: **${blueprint.state.label}**")
    appendLine("- 结论: ${blueprint.title}")
    appendLine("- 已就绪: ${blueprint.readyCount}")
    appendLine("- 注意: ${blueprint.watchCount}")
    appendLine("- 缺口: ${blueprint.gapCount}")
    appendLine("- 建议入口: `${blueprint.primaryCommand}`")
    appendLine()
    appendLine("### 执行链路")
    blueprint.flow.forEachIndexed { index, item ->
        appendLine("${index + 1}. $item")
    }
    appendLine()
    appendLine("### 架构层")
    blueprint.layers.forEach { layer ->
        appendLine("- **${layer.state.label}** · ${layer.title}: ${layer.detail}")
        appendLine("  - 入口: `${layer.command}`")
    }
    appendLine()
    appendLine("### 不变量")
    blueprint.invariants.forEach { appendLine("- $it") }
}
