package com.andmx.ui.workbench

import com.andmx.agent.PlanItemStatus
import com.andmx.agent.TaskPlanSnapshot

internal enum class InteractionFlowState(val label: String) {
    READY("就绪"),
    ACTIVE("进行中"),
    WATCH("注意"),
    BLOCKED("阻塞"),
}

internal data class CodexInteractionStep(
    val title: String,
    val state: InteractionFlowState,
    val codexBehavior: String,
    val andmxSignal: String,
    val evidence: List<String>,
    val command: String,
)

internal data class CodexInteractionFlow(
    val title: String,
    val steps: List<CodexInteractionStep>,
    val currentAction: NextActionDecision,
    val primaryCommand: String,
) {
    val readyCount: Int get() = steps.count { it.state == InteractionFlowState.READY }
    val activeCount: Int get() = steps.count { it.state == InteractionFlowState.ACTIVE }
    val watchCount: Int get() = steps.count { it.state == InteractionFlowState.WATCH }
    val blockedCount: Int get() = steps.count { it.state == InteractionFlowState.BLOCKED }
    val openCount: Int get() = activeCount + watchCount + blockedCount
}

internal fun buildCodexInteractionFlow(
    snapshot: AgentInspectorSnapshot,
    plan: TaskPlanSnapshot,
    verifications: List<VerificationEntry>,
    evidence: EvidenceLedger,
    checklist: SessionChecklistSummary,
    nextAction: NextActionDecision,
    screenshotExtraction: ScreenshotExtractionSummary? = null,
): CodexInteractionFlow {
    val planActive = plan.items.count { it.status == PlanItemStatus.ACTIVE }
    val planBlocked = plan.items.count { it.status == PlanItemStatus.BLOCKED }
    val verificationFailed = verifications.count { it.state == VerificationState.FAILED }
    val verificationRunning = verifications.count { it.state == VerificationState.RUNNING }
    val verificationPassed = verifications.count { it.state == VerificationState.PASSED }
    val hasFacts = evidence.items.isNotEmpty() || snapshot.toolEvents > 0 || snapshot.uiReferences > 0
    val screenshotWaiting = screenshotExtraction?.waitingCount ?: if (snapshot.uiReferences == 0) 1 else 0

    val steps = listOf(
        CodexInteractionStep(
            title = "目标进入",
            state = when {
                snapshot.goalText.isBlank() -> InteractionFlowState.BLOCKED
                snapshot.busy -> InteractionFlowState.ACTIVE
                else -> InteractionFlowState.READY
            },
            codexBehavior = "把用户意图锚定成线程目标, 后续计划、工具、验证和交接都围绕它收束。",
            andmxSignal = snapshot.goalText.ifBlank { "等待用户输入目标" },
            evidence = listOf("阶段: ${snapshot.goalPhaseLabel}", "备注: ${snapshot.goalNote.ifBlank { "(无)" }}"),
            command = "/plan",
        ),
        CodexInteractionStep(
            title = "指令与上下文",
            state = when {
                !snapshot.apiConfigured -> InteractionFlowState.BLOCKED
                snapshot.contextPressure == "需要压缩" -> InteractionFlowState.WATCH
                snapshot.contextPressure == "偏高" -> InteractionFlowState.WATCH
                else -> InteractionFlowState.READY
            },
            codexBehavior = "系统、开发者、用户、设置和运行环境共同决定 agent 能做什么、何时需要保守。",
            andmxSignal = "${snapshot.model} · ${snapshot.contextPressure} · ~${snapshot.tokenEstimate} tokens",
            evidence = listOf("API: ${if (snapshot.apiConfigured) "已配置" else "未配置"}", "推理: ${snapshot.reasoningEffort}", "人格: ${snapshot.persona}"),
            command = if (snapshot.apiConfigured) "/context" else "/model",
        ),
        CodexInteractionStep(
            title = "观察事实",
            state = if (hasFacts) InteractionFlowState.READY else InteractionFlowState.WATCH,
            codexBehavior = "先读项目、截图、文件、工具输出和网页来源, 再判断实现路径。",
            andmxSignal = "证据 ${evidence.items.size} · 来源 ${snapshot.sourceLinks} · UI ${snapshot.uiReferences}",
            evidence = listOf("文件 ${evidence.fileCount}", "网页 ${evidence.webCount}", "UI 参考 ${evidence.uiReferenceCount}"),
            command = if (snapshot.uiReferences > 0) "/references" else "/evidence",
        ),
        CodexInteractionStep(
            title = "计划与下一步",
            state = when {
                planBlocked > 0 -> InteractionFlowState.BLOCKED
                plan.items.isEmpty() -> InteractionFlowState.BLOCKED
                planActive > 0 || nextAction.priority in setOf(
                    NextActionPriority.ACTIVE,
                    NextActionPriority.REVIEW,
                    NextActionPriority.VERIFY,
                ) -> InteractionFlowState.ACTIVE
                checklist.watchCount > 0 -> InteractionFlowState.WATCH
                else -> InteractionFlowState.READY
            },
            codexBehavior = "把目标拆成小步, 每轮都能解释为什么下一步该做这件事。",
            andmxSignal = "${plan.items.size} 个计划项 · ${nextAction.title}",
            evidence = listOf("计划进行中 $planActive", "计划阻塞 $planBlocked", "决策: ${nextAction.reason}"),
            command = nextAction.command.ifBlank { "/next" },
        ),
        CodexInteractionStep(
            title = "工具与审批",
            state = when {
                snapshot.pendingApprovals > 0 || snapshot.failedTools > 0 -> InteractionFlowState.BLOCKED
                snapshot.busy || snapshot.runningTools > 0 -> InteractionFlowState.ACTIVE
                snapshot.toolEvents > 0 -> InteractionFlowState.READY
                else -> InteractionFlowState.WATCH
            },
            codexBehavior = "读取、写入、执行、网络和 MCP 工具按风险进入自动、询问或阻止路径。",
            andmxSignal = "工具 ${snapshot.toolEvents} · 运行 ${snapshot.runningTools} · 授权 ${snapshot.pendingApprovals}",
            evidence = listOf("授权模式: ${snapshot.approvalModeLabel}", "失败工具 ${snapshot.failedTools}", "MCP ${snapshot.mcpServers}"),
            command = if (snapshot.pendingApprovals > 0 || snapshot.failedTools > 0) "/activity" else "/policy",
        ),
        CodexInteractionStep(
            title = "实现与变更",
            state = when {
                snapshot.changedFiles > 0 && verificationPassed == 0 -> InteractionFlowState.ACTIVE
                snapshot.changedFiles > 0 -> InteractionFlowState.WATCH
                snapshot.toolEvents > 0 -> InteractionFlowState.READY
                else -> InteractionFlowState.WATCH
            },
            codexBehavior = "小范围修改、保留现有代码风格, 让 Diff 和文件面板承担审查入口。",
            andmxSignal = "${snapshot.changedFiles} 个待审文件",
            evidence = listOf("工具事件 ${snapshot.toolEvents}", "来源链接 ${snapshot.sourceLinks}", "验证通过 $verificationPassed"),
            command = "/changes",
        ),
        CodexInteractionStep(
            title = "截图到界面",
            state = when {
                snapshot.uiReferences == 0 -> InteractionFlowState.WATCH
                screenshotWaiting > 0 -> InteractionFlowState.ACTIVE
                else -> InteractionFlowState.READY
            },
            codexBehavior = "把截图拆成布局、控件、状态、交互和设计 token, 再映射到 Compose surface。",
            andmxSignal = screenshotExtraction?.title ?: "等待截图进入解析流水线",
            evidence = listOf("截图参考 ${snapshot.uiReferences}", "待处理 $screenshotWaiting"),
            command = when {
                snapshot.uiReferences == 0 -> "/references"
                screenshotWaiting > 0 -> screenshotExtraction?.primaryCommand ?: "/screenshot-extract"
                else -> "/screenshot-extract"
            },
        ),
        CodexInteractionStep(
            title = "验证证据",
            state = when {
                verificationFailed > 0 -> InteractionFlowState.BLOCKED
                verificationRunning > 0 -> InteractionFlowState.ACTIVE
                verificationPassed > 0 -> InteractionFlowState.READY
                else -> InteractionFlowState.WATCH
            },
            codexBehavior = "用编译、单测、构建、诊断或视觉核查证明当前实现能交付。",
            andmxSignal = "通过 $verificationPassed · 运行 $verificationRunning · 失败 $verificationFailed",
            evidence = verifications.take(3).map { "${it.state.verificationStateLabel()} · ${it.command}" }
                .ifEmpty { listOf("暂无验证记录") },
            command = "/verify",
        ),
        CodexInteractionStep(
            title = "交付与交接",
            state = when {
                checklist.missingCount > 0 -> InteractionFlowState.BLOCKED
                checklist.watchCount > 0 || nextAction.priority == NextActionPriority.HANDOFF -> InteractionFlowState.WATCH
                else -> InteractionFlowState.READY
            },
            codexBehavior = "把目标、变更、证据、验证、风险和下一步收束到报告或 handoff/resume。",
            andmxSignal = checklist.title,
            evidence = listOf("缺口 ${checklist.missingCount}", "注意 ${checklist.watchCount}", "下一步: ${nextAction.title}"),
            command = if (nextAction.priority == NextActionPriority.HANDOFF) "/handoff" else "/report",
        ),
    )

    val firstOpen = steps.firstOrNull { it.state == InteractionFlowState.BLOCKED }
        ?: steps.firstOrNull { it.state == InteractionFlowState.ACTIVE }
        ?: steps.firstOrNull { it.state == InteractionFlowState.WATCH }
    val title = when {
        steps.any { it.state == InteractionFlowState.BLOCKED } -> "Codex 交互流程存在阻塞"
        steps.any { it.state == InteractionFlowState.ACTIVE } -> "Codex 交互流程正在推进"
        steps.any { it.state == InteractionFlowState.WATCH } -> "Codex 交互流程仍需关注"
        else -> "Codex 交互流程已闭环"
    }
    return CodexInteractionFlow(
        title = title,
        steps = steps,
        currentAction = nextAction,
        primaryCommand = firstOpen?.command ?: "/report",
    )
}

internal fun codexInteractionFlowText(flow: CodexInteractionFlow): String = buildString {
    appendLine("## Codex 交互流程")
    appendLine("- 状态: **${flow.title}**")
    appendLine("- 就绪: ${flow.readyCount}")
    appendLine("- 进行中: ${flow.activeCount}")
    appendLine("- 注意: ${flow.watchCount}")
    appendLine("- 阻塞: ${flow.blockedCount}")
    appendLine("- 建议入口: `${flow.primaryCommand}`")
    appendLine()
    appendLine("### 当前动作")
    appendLine("- ${flow.currentAction.title}: ${flow.currentAction.reason}")
    appendLine("- 入口: `${flow.currentAction.command}`")
    appendLine()
    appendLine("### 步骤")
    flow.steps.forEach { step ->
        appendLine("- **${step.state.label}** · ${step.title}")
        appendLine("  - Codex 行为: ${step.codexBehavior}")
        appendLine("  - AndMX 信号: ${step.andmxSignal}")
        appendLine("  - 入口: `${step.command}`")
        step.evidence.take(3).forEach { appendLine("  - 证据: $it") }
    }
}
