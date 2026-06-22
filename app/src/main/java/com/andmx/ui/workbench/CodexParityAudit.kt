package com.andmx.ui.workbench

internal enum class ParityState(val label: String) {
    READY("已具备"),
    WATCH("注意"),
    GAP("缺口"),
}

internal data class CodexParityItem(
    val title: String,
    val state: ParityState,
    val detail: String,
    val command: String,
)

internal data class CodexParityAudit(
    val items: List<CodexParityItem>,
) {
    val readyCount: Int get() = items.count { it.state == ParityState.READY }
    val watchCount: Int get() = items.count { it.state == ParityState.WATCH }
    val gapCount: Int get() = items.count { it.state == ParityState.GAP }
    val title: String get() = when {
        gapCount > 0 -> "仍有 Codex 对标缺口"
        watchCount > 0 -> "接近 Codex 工作台闭环"
        else -> "Codex 对标能力已成形"
    }
}

internal fun buildCodexParityAudit(
    snapshot: AgentInspectorSnapshot,
    runtime: RuntimeEnvironmentSummary,
    policy: ToolPolicySummary,
    evidence: EvidenceLedger,
    checklist: SessionChecklistSummary,
    designSystem: CodexDesignSystemAudit? = null,
    screenshotExtraction: ScreenshotExtractionSummary? = null,
    interactionFlow: CodexInteractionFlow? = null,
    selfModel: CodexSelfModel? = null,
): CodexParityAudit {
    val baseItems = listOf(
        CodexParityItem(
            title = "三栏工作台",
            state = ParityState.READY,
            detail = "Sidebar、Chat、Inspector/Files/Terminal/Diff/Browser 已在同一任务上下文中组织。",
            command = "/status",
        ),
        CodexParityItem(
            title = "本地执行环境",
            state = when (runtime.level) {
                RuntimeEnvironmentLevel.READY -> ParityState.READY
                RuntimeEnvironmentLevel.WATCH -> ParityState.WATCH
                RuntimeEnvironmentLevel.LIMITED -> ParityState.GAP
            },
            detail = "${runtime.healthLabel} · ${runtime.executionSurface}",
            command = runtime.primaryCommand,
        ),
        CodexParityItem(
            title = "工具与审批策略",
            state = if (policy.rows.isNotEmpty() && policy.boundaryRows.isNotEmpty()) ParityState.READY else ParityState.GAP,
            detail = "自动 ${policy.autoCount} · 询问 ${policy.promptCount} · 阻止 ${policy.denyCount} · 安全边界 ${policy.boundaryRows.size}",
            command = "/policy",
        ),
        CodexParityItem(
            title = "上下文与交接",
            state = when {
                snapshot.contextPressure == "需要压缩" -> ParityState.GAP
                snapshot.contextPressure == "偏高" || snapshot.messageCount >= 24 -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = "~${snapshot.tokenEstimate} tokens · ${snapshot.contextPressure} · ${snapshot.messageCount} 条消息",
            command = if (snapshot.contextPressure == "轻量") "/context" else "/handoff",
        ),
        CodexParityItem(
            title = "截图/UI 参考",
            state = when {
                snapshot.uiReferences > 0 && snapshot.toolEvents == 0 && snapshot.changedFiles == 0 -> ParityState.WATCH
                snapshot.uiReferences > 0 -> ParityState.READY
                else -> ParityState.WATCH
            },
            detail = if (snapshot.uiReferences > 0) {
                "${snapshot.uiReferences} 个 UI 参考已进入任务链路"
            } else {
                "尚未添加截图；收到截图后会进入 /references 和证据账本"
            },
            command = "/references",
        ),
        CodexParityItem(
            title = "Codex UI 表面地图",
            state = when {
                snapshot.uiReferences == 0 -> ParityState.WATCH
                snapshot.changedFiles > 0 -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = "会话流、输入区、任务面板、Inspector、工作区、命令面板、审批和截图复刻流水线可通过 /surfaces 对照。",
            command = "/surfaces",
        ),
        CodexParityItem(
            title = "视觉验收清单",
            state = when {
                snapshot.uiReferences == 0 -> ParityState.WATCH
                snapshot.changedFiles > 0 && evidence.verificationCount == 0 -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = "布局、控件、状态、交互、移动端稳定性和验证证据可通过 /visual-check 核查。",
            command = "/visual-check",
        ),
        CodexParityItem(
            title = "Codex 设计系统",
            state = when {
                designSystem == null -> if (snapshot.uiReferences == 0) ParityState.WATCH else ParityState.READY
                designSystem.gapCount > 0 -> ParityState.GAP
                designSystem.watchCount > 0 -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = designSystem?.let {
                "${it.title} · 已对齐 ${it.readyCount} · 注意 ${it.watchCount} · 缺口 ${it.gapCount}"
            } ?: "密度、布局、图标化控制、卡片边界、文本稳定和色彩角色可通过 /design-system 审计。",
            command = "/design-system",
        ),
        CodexParityItem(
            title = "截图解析流水线",
            state = when {
                screenshotExtraction == null -> if (snapshot.uiReferences == 0) ParityState.WATCH else ParityState.READY
                screenshotExtraction.waitingCount > 0 -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = screenshotExtraction?.let {
                "${it.title} · 参考 ${it.referenceCount} · 待处理 ${it.waitingCount}"
            } ?: "截图会通过 /screenshot-extract 拆成布局、控件、状态、交互、设计 token 和验证项。",
            command = "/screenshot-extract",
        ),
    )
    val interactionFlowItem = interactionFlow?.let {
        CodexParityItem(
            title = "Codex 交互流程",
            state = when {
                it.blockedCount > 0 -> ParityState.GAP
                it.activeCount > 0 || it.watchCount > 0 -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = "${it.title} · 就绪 ${it.readyCount} · 进行中 ${it.activeCount} · 注意 ${it.watchCount} · 阻塞 ${it.blockedCount}",
            command = "/flow",
        )
    }
    val selfModelItem = selfModel?.let {
        CodexParityItem(
            title = "Codex 自我模型",
            state = when {
                it.gapCount > 0 -> ParityState.GAP
                it.watchCount > 0 -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = "${it.title} · 已建模 ${it.readyCount} · 关注 ${it.watchCount} · 缺口 ${it.gapCount}",
            command = "/self-model",
        )
    }
    val tailItems = listOf(
        CodexParityItem(
            title = "证据账本",
            state = if (evidence.items.isEmpty()) ParityState.WATCH else ParityState.READY,
            detail = "文件 ${evidence.fileCount} · 网页 ${evidence.webCount} · UI ${evidence.uiReferenceCount} · 验证 ${evidence.verificationCount}",
            command = "/evidence",
        ),
        CodexParityItem(
            title = "交付清单",
            state = when {
                checklist.missingCount > 0 -> ParityState.GAP
                checklist.watchCount > 0 -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = "缺口 ${checklist.missingCount} · 注意 ${checklist.watchCount} · 就绪 ${checklist.readyCount}",
            command = "/checklist",
        ),
        CodexParityItem(
            title = "系统架构蓝图",
            state = when {
                snapshot.totalTools <= 0 || !snapshot.apiConfigured -> ParityState.GAP
                runtime.level != RuntimeEnvironmentLevel.READY || evidence.items.isEmpty() -> ParityState.WATCH
                else -> ParityState.READY
            },
            detail = "目标、指令栈、工具路由、执行环境、证据和交付路径可通过 /architecture 串联审计。",
            command = "/architecture",
        ),
    )
    val items = baseItems + listOfNotNull(interactionFlowItem, selfModelItem) + tailItems
    return CodexParityAudit(items)
}

internal fun codexParityText(audit: CodexParityAudit): String = buildString {
    appendLine("## Codex 对标审计")
    appendLine("- 状态: **${audit.title}**")
    appendLine("- 已具备: ${audit.readyCount}")
    appendLine("- 注意: ${audit.watchCount}")
    appendLine("- 缺口: ${audit.gapCount}")
    appendLine()
    audit.items.forEach { item ->
        appendLine("- **${item.state.label}** · ${item.title}: ${item.detail}")
        appendLine("  - 入口: `${item.command}`")
    }
    appendLine()
    appendLine("### 下一步")
    val next = audit.items.firstOrNull { it.state == ParityState.GAP }
        ?: audit.items.firstOrNull { it.state == ParityState.WATCH }
    if (next == null) {
        appendLine("- 可以继续按截图和目标迭代, 或运行 `/handoff` 保留当前状态。")
    } else {
        appendLine("- 先处理 `${next.title}`: ${next.detail}")
        appendLine("- 可运行 `${next.command}` 查看详情")
    }
}
