package com.andmx.ui.workbench

internal enum class SelfImprovementState(val label: String) {
    READY("就绪"),
    WATCH("注意"),
    GAP("缺口"),
}

internal data class SelfImprovementItem(
    val title: String,
    val state: SelfImprovementState,
    val reason: String,
    val evidence: List<String>,
    val command: String,
)

internal data class CodexSelfImprovementPlan(
    val title: String,
    val items: List<SelfImprovementItem>,
    val operatingRules: List<String>,
    val primaryCommand: String,
) {
    val readyCount: Int get() = items.count { it.state == SelfImprovementState.READY }
    val watchCount: Int get() = items.count { it.state == SelfImprovementState.WATCH }
    val gapCount: Int get() = items.count { it.state == SelfImprovementState.GAP }
}

internal fun buildCodexSelfImprovementPlan(
    snapshot: AgentInspectorSnapshot,
    selfModel: CodexSelfModel,
    interactionFlow: CodexInteractionFlow,
    toolCapabilityMap: CodexToolCapabilityMap,
    visualAcceptance: VisualAcceptanceSummary,
    evidence: EvidenceLedger,
    checklist: SessionChecklistSummary,
    nextAction: NextActionDecision,
): CodexSelfImprovementPlan {
    val needsCodexVisualContext = visualAcceptance.referenceCount == 0 || evidence.uiReferenceCount == 0
    val items = listOf(
        SelfImprovementItem(
            title = "锚定目标",
            state = if (snapshot.goalText.isBlank()) SelfImprovementState.GAP else SelfImprovementState.READY,
            reason = if (snapshot.goalText.isBlank()) {
                "没有持久目标时, 后续计划、截图复刻、验证和交付都缺少收束点。"
            } else {
                "当前线程已有可追踪目标, 可继续把它拆成计划、变更和验证。"
            },
            evidence = listOf("目标: ${snapshot.goalText.ifBlank { "(未设置)" }}", "状态: ${snapshot.goalPhaseLabel}"),
            command = if (snapshot.goalText.isBlank()) "/goal" else "/plan",
        ),
        SelfImprovementItem(
            title = "补齐运行契约",
            state = when {
                !snapshot.apiConfigured -> SelfImprovementState.GAP
                selfModel.gapCount > 0 -> SelfImprovementState.WATCH
                else -> SelfImprovementState.READY
            },
            reason = "Codex 式工作台需要把模型、指令栈、授权、工具和环境边界显式化, 才能稳定自我完善。",
            evidence = listOf("自我模型缺口 ${selfModel.gapCount}", "需关注 ${selfModel.watchCount}", "API ${if (snapshot.apiConfigured) "已配置" else "未配置"}"),
            command = if (!snapshot.apiConfigured) "/model" else selfModel.primaryCommand,
        ),
        SelfImprovementItem(
            title = "打通执行循环",
            state = when {
                interactionFlow.blockedCount > 0 -> SelfImprovementState.GAP
                interactionFlow.openCount > 0 -> SelfImprovementState.WATCH
                else -> SelfImprovementState.READY
            },
            reason = "自我完善不是静态说明, 而是观察、计划、工具、审批、验证和交付的闭环。",
            evidence = listOf("阻塞 ${interactionFlow.blockedCount}", "进行中 ${interactionFlow.activeCount}", "注意 ${interactionFlow.watchCount}"),
            command = interactionFlow.primaryCommand,
        ),
        SelfImprovementItem(
            title = "扩展工具能力",
            state = when {
                toolCapabilityMap.emptyDomainCount > 0 -> SelfImprovementState.GAP
                toolCapabilityMap.mcpServerCount == 0 -> SelfImprovementState.WATCH
                else -> SelfImprovementState.READY
            },
            reason = "AndMX 需要知道自己能观察、编辑、执行、浏览、操作 GUI、接入 MCP 和交付什么。",
            evidence = listOf("能力域 ${toolCapabilityMap.domainCount}", "工具 ${toolCapabilityMap.toolCount}", "MCP ${toolCapabilityMap.mcpServerCount}"),
            command = toolCapabilityMap.primaryCommand,
        ),
        SelfImprovementItem(
            title = "采集真实 Codex 视觉上下文",
            state = if (needsCodexVisualContext) SelfImprovementState.GAP else SelfImprovementState.READY,
            reason = if (needsCodexVisualContext) {
                "Computer Use 不能自动化宿主 Codex, 需要用 Appshots、截图或附件把真实 Codex UI 转成可追踪参考。"
            } else {
                "当前线程已有 UI 参考证据, 可以继续做截图解析、实现追踪和视觉验收。"
            },
            evidence = listOf("截图参考 ${visualAcceptance.referenceCount}", "UI 证据 ${evidence.uiReferenceCount}", "本机 Codex 需经截图/Appshots进入线程"),
            command = if (needsCodexVisualContext) "/appshots" else "/screenshot-extract",
        ),
        SelfImprovementItem(
            title = "吸收截图复刻",
            state = when {
                visualAcceptance.referenceCount == 0 -> SelfImprovementState.WATCH
                visualAcceptance.waitingCount > 0 -> SelfImprovementState.WATCH
                else -> SelfImprovementState.READY
            },
            reason = "用户给的 Codex 截图要进入参考、提取、实现追踪和视觉验收, 才能真正对齐 UI。",
            evidence = listOf("截图参考 ${visualAcceptance.referenceCount}", "待处理 ${visualAcceptance.waitingCount}", visualAcceptance.title),
            command = visualAcceptance.primaryCommand,
        ),
        SelfImprovementItem(
            title = "积累证据链",
            state = when {
                evidence.items.isEmpty() -> SelfImprovementState.GAP
                evidence.verificationCount == 0 -> SelfImprovementState.WATCH
                else -> SelfImprovementState.READY
            },
            reason = "每次自我完善都要能追溯到文件、截图、工具输出、验证或变更证据。",
            evidence = listOf("证据 ${evidence.items.size}", "验证 ${evidence.verificationCount}", "变更 ${evidence.changeCount}"),
            command = if (evidence.items.isEmpty()) "/evidence" else "/verify",
        ),
        SelfImprovementItem(
            title = "收束交付判断",
            state = when {
                checklist.missingCount > 0 -> SelfImprovementState.GAP
                checklist.watchCount > 0 -> SelfImprovementState.WATCH
                else -> SelfImprovementState.READY
            },
            reason = "完成感不能靠印象, 要由清单、下一步、报告和 handoff 共同证明。",
            evidence = listOf("清单: ${checklist.title}", "缺口 ${checklist.missingCount}", "下一步: ${nextAction.title}"),
            command = nextAction.command.ifBlank { checklist.items.firstOrNull { it.state != ChecklistState.READY }?.command ?: "/report" },
        ),
    )

    val firstOpen = items.firstOrNull { it.state == SelfImprovementState.GAP }
        ?: items.firstOrNull { it.state == SelfImprovementState.WATCH }
    val title = when {
        items.any { it.state == SelfImprovementState.GAP } -> "AndMX 自我完善仍有缺口"
        items.any { it.state == SelfImprovementState.WATCH } -> "AndMX 自我完善正在收束"
        else -> "AndMX 自我完善闭环可用"
    }
    return CodexSelfImprovementPlan(
        title = title,
        items = items,
        operatingRules = listOf(
            "先用证据建立事实, 再决定实现路径。",
            "每个 UI 改动都要能回指截图、surface map 或验收项。",
            "每个工具动作都要继承授权模式、风险说明和可恢复日志。",
            "每轮结束前用 `/verify`、`/report` 或 `/handoff` 留下可审计状态。",
        ),
        primaryCommand = firstOpen?.command ?: "/report",
    )
}

internal fun codexSelfImprovementText(plan: CodexSelfImprovementPlan): String = buildString {
    appendLine("## 自我完善路线图")
    appendLine("- 状态: **${plan.title}**")
    appendLine("- 就绪: ${plan.readyCount}")
    appendLine("- 注意: ${plan.watchCount}")
    appendLine("- 缺口: ${plan.gapCount}")
    appendLine("- 建议入口: `${plan.primaryCommand}`")
    appendLine()
    appendLine("### 操作规则")
    plan.operatingRules.forEachIndexed { index, rule -> appendLine("${index + 1}. $rule") }
    appendLine()
    appendLine("### 队列")
    plan.items.forEach { item ->
        appendLine("- **${item.state.label}** · ${item.title}: ${item.reason}")
        appendLine("  - 入口: `${item.command}`")
        item.evidence.take(3).forEach { appendLine("  - 证据: $it") }
    }
}
