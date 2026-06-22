package com.andmx.ui.workbench

internal enum class SelfModelState(val label: String) {
    READY("已建模"),
    WATCH("需关注"),
    GAP("缺口"),
}

internal data class CodexSelfModelLayer(
    val title: String,
    val state: SelfModelState,
    val codexBehavior: String,
    val andmxImplementation: String,
    val command: String,
)

internal data class CodexSelfModel(
    val title: String,
    val layers: List<CodexSelfModelLayer>,
    val operatingLoop: List<String>,
    val environmentFacts: List<String>,
    val primaryCommand: String,
) {
    val readyCount: Int get() = layers.count { it.state == SelfModelState.READY }
    val watchCount: Int get() = layers.count { it.state == SelfModelState.WATCH }
    val gapCount: Int get() = layers.count { it.state == SelfModelState.GAP }
}

internal fun buildCodexSelfModel(
    snapshot: AgentInspectorSnapshot,
    architecture: AgentArchitectureBlueprint,
    surfaceMap: CodexSurfaceMap,
    designSystem: CodexDesignSystemAudit,
    screenshotExtraction: ScreenshotExtractionSummary? = null,
    interactionFlow: CodexInteractionFlow? = null,
    policy: ToolPolicySummary,
    evidence: EvidenceLedger,
    checklist: SessionChecklistSummary,
    runtime: RuntimeEnvironmentSummary,
    instructionSummary: InstructionStackSummary,
    environmentContract: CodexEnvironmentContract? = null,
    toolCapabilityMap: CodexToolCapabilityMap? = null,
): CodexSelfModel {
    val layers = listOf(
        CodexSelfModelLayer(
            title = "目标与线程",
            state = if (snapshot.goalText.isBlank()) SelfModelState.GAP else SelfModelState.READY,
            codexBehavior = "把用户目标锚定为当前线程状态, 通过计划、下一步和交接保持连续性。",
            andmxImplementation = "${snapshot.goalPhaseLabel} · ${snapshot.goalText.ifBlank { "等待目标" }}",
            command = "/plan",
        ),
        CodexSelfModelLayer(
            title = "指令栈",
            state = if (snapshot.apiConfigured) SelfModelState.READY else SelfModelState.GAP,
            codexBehavior = "系统、开发者、用户、项目和运行环境指令共同形成可见/不可见边界。",
            andmxImplementation = "${instructionSummary.apiStatus} · ${instructionSummary.mcpStatus} · ${instructionSummary.customInstructionStatus}",
            command = "/instructions",
        ),
        CodexSelfModelLayer(
            title = "工具与授权",
            state = if (policy.rows.any { it.toolCount > 0 }) SelfModelState.READY else SelfModelState.GAP,
            codexBehavior = "读取、写入、执行、网络、MCP 和 Live UI 行为按风险和授权模式路由。",
            andmxImplementation = "自动 ${policy.autoCount} · 询问 ${policy.promptCount} · 阻止 ${policy.denyCount} · 安全边界 ${policy.boundaryRows.size}",
            command = "/policy",
        ),
        CodexSelfModelLayer(
            title = "执行环境",
            state = when (runtime.level) {
                RuntimeEnvironmentLevel.READY -> SelfModelState.READY
                RuntimeEnvironmentLevel.WATCH -> SelfModelState.WATCH
                RuntimeEnvironmentLevel.LIMITED -> SelfModelState.GAP
            },
            codexBehavior = "把 shell、文件、浏览器和工具调用收束到可验证的本地执行面。",
            andmxImplementation = "${runtime.healthLabel} · ${runtime.executionSurface}",
            command = runtime.primaryCommand,
        ),
        CodexSelfModelLayer(
            title = "工作循环",
            state = when {
                interactionFlow != null && interactionFlow.blockedCount > 0 -> SelfModelState.GAP
                interactionFlow != null && interactionFlow.openCount > 0 -> SelfModelState.WATCH
                architecture.gapCount > 0 -> SelfModelState.GAP
                architecture.watchCount > 0 -> SelfModelState.WATCH
                else -> SelfModelState.READY
            },
            codexBehavior = "读事实、制定计划、做小改动、验证、报告风险, 并在长线程中交接恢复。",
            andmxImplementation = interactionFlow?.let {
                "${it.title} · 就绪 ${it.readyCount} · 待处理 ${it.openCount}"
            } ?: "${architecture.title} · 就绪 ${architecture.readyCount} · 注意 ${architecture.watchCount} · 缺口 ${architecture.gapCount}",
            command = interactionFlow?.primaryCommand ?: "/architecture",
        ),
        CodexSelfModelLayer(
            title = "UI 表面",
            state = when {
                surfaceMap.waitingCount > 0 -> SelfModelState.WATCH
                surfaceMap.partialCount > 0 -> SelfModelState.WATCH
                else -> SelfModelState.READY
            },
            codexBehavior = "项目/线程、composer、任务侧栏、Inspector、工作区、命令菜单、Git/Diff、Browser/Computer Use 和扩展层组成工作台。",
            andmxImplementation = "${surfaceMap.title} · 表面 ${surfaceMap.surfaces.size} · 已具备 ${surfaceMap.readyCount} · 待补齐 ${surfaceMap.partialCount}",
            command = "/surfaces",
        ),
        CodexSelfModelLayer(
            title = "设计与截图复刻",
            state = when {
                screenshotExtraction != null && screenshotExtraction.waitingCount > 0 -> SelfModelState.WATCH
                designSystem.gapCount > 0 -> SelfModelState.GAP
                designSystem.watchCount > 0 -> SelfModelState.WATCH
                else -> SelfModelState.READY
            },
            codexBehavior = "真实截图驱动密度、布局、控件、状态、文本稳定和视觉验收。",
            andmxImplementation = screenshotExtraction?.let {
                "${it.title} · 参考 ${it.referenceCount} · 待处理 ${it.waitingCount}"
            } ?: "${designSystem.title} · 参考 ${designSystem.referenceCount}",
            command = screenshotExtraction?.primaryCommand ?: "/design-system",
        ),
        CodexSelfModelLayer(
            title = "证据与交付",
            state = when {
                checklist.missingCount > 0 -> SelfModelState.GAP
                evidence.items.isEmpty() || checklist.watchCount > 0 -> SelfModelState.WATCH
                else -> SelfModelState.READY
            },
            codexBehavior = "所有事实、来源、变更、验证和剩余风险都要能被追溯并汇总。",
            andmxImplementation = "证据 ${evidence.items.size} · 验证 ${evidence.verificationCount} · 清单缺口 ${checklist.missingCount}",
            command = "/report",
        ),
    )

    val title = when {
        layers.any { it.state == SelfModelState.GAP } -> "Codex 自我模型仍有缺口"
        layers.any { it.state == SelfModelState.WATCH } -> "Codex 自我模型接近闭环"
        else -> "Codex 自我模型已可复用"
    }
    return CodexSelfModel(
        title = title,
        layers = layers,
        operatingLoop = listOf(
            "观察: 读取项目、截图、文件、工具输出和当前 UI 状态",
            "定向: 把用户目标拆成计划、下一步和可验证验收项",
            "执行: 使用文件、补丁、终端、网络、MCP 或 Live UI 工具推进",
            "审计: 将来源、变更、审批、验证和风险写入可查看的面板",
            "收束: 通过报告或 handoff/resume 保持线程可交付、可继续",
        ),
        environmentFacts = listOf(
            "当前移动端执行面: ${runtime.executionSurface}",
            "工具能力: ${toolCapabilityMap?.let { "${it.domainCount} 个能力域 · ${it.toolCount} 个工具" } ?: "内置风险矩阵 ${policy.rows.size} 类"} · MCP ${snapshot.mcpServers} 个",
            "可见指令层: ${instructionSummary.visibleLayers.joinToString(" / ")}",
            "环境契约: ${environmentContract?.title ?: instructionSummary.contractSummary}",
            "Codex app 表面: 线程/项目模式、命令菜单、任务侧栏、Git/Diff、集成终端、in-app browser、Computer Use、Skills/MCP/Automations",
            "Live UI 限制: 受保护宿主窗口无法由 Computer Use 直接操作时, 使用截图证据继续复刻",
        ),
        primaryCommand = layers.firstOrNull { it.state == SelfModelState.GAP }?.command
            ?: layers.firstOrNull { it.state == SelfModelState.WATCH }?.command
            ?: "/report",
    )
}

internal fun codexSelfModelText(model: CodexSelfModel): String = buildString {
    appendLine("## Codex 自我模型")
    appendLine("- 状态: **${model.title}**")
    appendLine("- 已建模: ${model.readyCount}")
    appendLine("- 需关注: ${model.watchCount}")
    appendLine("- 缺口: ${model.gapCount}")
    appendLine("- 建议入口: `${model.primaryCommand}`")
    appendLine()
    appendLine("### 工作循环")
    model.operatingLoop.forEachIndexed { index, item ->
        appendLine("${index + 1}. $item")
    }
    appendLine()
    appendLine("### 环境事实")
    model.environmentFacts.forEach { appendLine("- $it") }
    appendLine()
    appendLine("### 能力层")
    model.layers.forEach { layer ->
        appendLine("- **${layer.state.label}** · ${layer.title}")
        appendLine("  - Codex 行为: ${layer.codexBehavior}")
        appendLine("  - AndMX 落点: ${layer.andmxImplementation}")
        appendLine("  - 入口: `${layer.command}`")
    }
}
