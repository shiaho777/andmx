package com.andmx.ui.workbench

import com.andmx.agent.ChangeSummaryItem

internal enum class DeliveryReportState(val label: String) {
    READY("可交付"),
    NEEDS_REVIEW("需审查"),
    NEEDS_VERIFICATION("需验证"),
    BLOCKED("阻塞"),
}

internal data class DeliveryReport(
    val state: DeliveryReportState,
    val title: String,
    val goal: String,
    val changedFiles: List<ChangeSummaryItem>,
    val verifications: List<VerificationEntry>,
    val evidence: EvidenceLedger,
    val checklist: SessionChecklistSummary,
    val nextAction: NextActionDecision,
    val parity: CodexParityAudit,
    val blueprint: UiReplicaBlueprint,
    val visualAcceptance: VisualAcceptanceSummary,
    val referenceBoard: UiReferenceBoard? = null,
    val screenshotTrace: ScreenshotImplementationTrace? = null,
    val designSystem: CodexDesignSystemAudit? = null,
    val screenshotExtraction: ScreenshotExtractionSummary? = null,
    val interactionFlow: CodexInteractionFlow? = null,
    val selfModel: CodexSelfModel? = null,
    val methodologySummary: List<String> = emptyList(),
    val environmentContractSummary: List<String> = emptyList(),
    val toolCapabilitySummary: List<String> = emptyList(),
)

internal fun buildDeliveryReport(
    snapshot: AgentInspectorSnapshot,
    changes: List<ChangeSummaryItem>,
    verifications: List<VerificationEntry>,
    evidence: EvidenceLedger,
    checklist: SessionChecklistSummary,
    nextAction: NextActionDecision,
    parity: CodexParityAudit,
    blueprint: UiReplicaBlueprint,
    visualAcceptance: VisualAcceptanceSummary,
    referenceBoard: UiReferenceBoard? = null,
    screenshotTrace: ScreenshotImplementationTrace? = null,
    designSystem: CodexDesignSystemAudit? = null,
    screenshotExtraction: ScreenshotExtractionSummary? = null,
    interactionFlow: CodexInteractionFlow? = null,
    selfModel: CodexSelfModel? = null,
    methodologySummary: List<String> = emptyList(),
    environmentContractSummary: List<String> = emptyList(),
    toolCapabilitySummary: List<String> = emptyList(),
): DeliveryReport {
    val failedVerifications = verifications.any { it.state == VerificationState.FAILED }
    val passedVerifications = verifications.any { it.state == VerificationState.PASSED }
    val state = when {
        checklist.missingCount > 0 || failedVerifications || parity.gapCount > 0 ||
            (designSystem?.gapCount ?: 0) > 0 ||
            (interactionFlow?.blockedCount ?: 0) > 0 ||
            (selfModel?.gapCount ?: 0) > 0 -> DeliveryReportState.BLOCKED
        snapshot.changedFiles > 0 && !passedVerifications -> DeliveryReportState.NEEDS_VERIFICATION
        snapshot.changedFiles > 0 || checklist.watchCount > 0 || parity.watchCount > 0 ||
            (referenceBoard?.openCount ?: 0) > 0 ||
            (screenshotTrace?.waitingCount ?: 0) > 0 ||
            (designSystem?.watchCount ?: 0) > 0 ||
            (screenshotExtraction?.waitingCount ?: 0) > 0 ||
            (interactionFlow?.openCount ?: 0) > 0 ||
            (selfModel?.watchCount ?: 0) > 0 -> DeliveryReportState.NEEDS_REVIEW
        else -> DeliveryReportState.READY
    }
    return DeliveryReport(
        state = state,
        title = when (state) {
            DeliveryReportState.READY -> "可以交付或交接"
            DeliveryReportState.NEEDS_REVIEW -> "先审查注意项"
            DeliveryReportState.NEEDS_VERIFICATION -> "先补验证证据"
            DeliveryReportState.BLOCKED -> "仍有阻塞缺口"
        },
        goal = snapshot.goalText,
        changedFiles = changes,
        verifications = verifications,
        evidence = evidence,
        checklist = checklist,
        nextAction = nextAction,
        parity = parity,
        blueprint = blueprint,
        visualAcceptance = visualAcceptance,
        referenceBoard = referenceBoard,
        screenshotTrace = screenshotTrace,
        designSystem = designSystem,
        screenshotExtraction = screenshotExtraction,
        interactionFlow = interactionFlow,
        selfModel = selfModel,
        methodologySummary = methodologySummary,
        environmentContractSummary = environmentContractSummary,
        toolCapabilitySummary = toolCapabilitySummary,
    )
}

internal fun deliveryReportText(report: DeliveryReport): String = buildString {
    appendLine("## 交付报告")
    appendLine("- 状态: **${report.state.label}**")
    appendLine("- 结论: ${report.title}")
    appendLine("- 目标: ${report.goal.ifBlank { "(未设置)" }}")
    appendLine("- 下一步: ${report.nextAction.title} (`${report.nextAction.command}`)")
    appendLine()
    appendLine("### 变更")
    if (report.changedFiles.isEmpty()) {
        appendLine("- 暂无待审变更")
    } else {
        report.changedFiles.forEach { item ->
            appendLine("- `${item.path}` · ${if (item.isNew) "新建" else "修改"} · +${item.added} / -${item.removed}")
        }
    }
    appendLine()
    appendLine("### 验证")
    if (report.verifications.isEmpty()) {
        appendLine("- 暂无测试、构建或诊断记录")
    } else {
        report.verifications.forEach { entry ->
            appendLine("- ${entry.state.verificationStateLabel()} · `${entry.command}`: ${entry.detail.ifBlank { "(无输出摘要)" }}")
        }
    }
    appendLine()
    appendLine("### 证据")
    appendLine("- 文件: ${report.evidence.fileCount}")
    appendLine("- 网页: ${report.evidence.webCount}")
    appendLine("- UI 参考: ${report.evidence.uiReferenceCount}")
    appendLine("- 验证: ${report.evidence.verificationCount}")
    appendLine("- 授权: ${report.evidence.approvalCount}")
    appendLine()
    if (report.methodologySummary.isNotEmpty()) {
        appendLine("### 工作方法")
        report.methodologySummary.forEach { appendLine("- $it") }
        appendLine("- 入口: `/method`")
        appendLine()
    }
    if (report.environmentContractSummary.isNotEmpty()) {
        appendLine("### 环境契约")
        report.environmentContractSummary.forEach { appendLine("- $it") }
        appendLine("- 入口: `/instructions`")
        appendLine()
    }
    if (report.toolCapabilitySummary.isNotEmpty()) {
        appendLine("### 工具能力")
        report.toolCapabilitySummary.forEach { appendLine("- $it") }
        appendLine("- 入口: `/tools`")
        appendLine()
    }
    appendLine("### UI 蓝图")
    appendLine("- 状态: ${report.blueprint.state.label}")
    appendLine("- 参考: ${report.blueprint.referenceCount}")
    appendLine("- 入口: `${report.blueprint.primaryCommand}`")
    appendLine()
    appendLine("### 视觉验收")
    appendLine("- 状态: ${report.visualAcceptance.title}")
    appendLine("- 就绪: ${report.visualAcceptance.readyCount}")
    appendLine("- 待处理: ${report.visualAcceptance.waitingCount}")
    appendLine("- 入口: `${report.visualAcceptance.primaryCommand}`")
    appendLine()
    report.referenceBoard?.let { board ->
        appendLine("### 截图参考板")
        appendLine("- 状态: ${board.title}")
        appendLine("- 参考: ${board.referenceCount}")
        appendLine("- Codex: ${board.codexCount}")
        appendLine("- 已闭环: ${board.readyCount}")
        appendLine("- 待处理: ${board.openCount}")
        appendLine("- 入口: `${board.primaryCommand}`")
        appendLine()
    }
    report.screenshotTrace?.let { trace ->
        appendLine("### 截图实现追踪")
        appendLine("- 状态: ${trace.title}")
        appendLine("- 参考: ${trace.referenceCount}")
        appendLine("- 已闭环: ${trace.readyCount}")
        appendLine("- 待处理: ${trace.waitingCount}")
        appendLine("- 变更文件: ${trace.changedFileCount}")
        appendLine("- 入口: `${trace.primaryCommand}`")
        appendLine()
    }
    report.designSystem?.let { design ->
        appendLine("### 设计系统")
        appendLine("- 状态: ${design.title}")
        appendLine("- 已对齐: ${design.readyCount}")
        appendLine("- 注意: ${design.watchCount}")
        appendLine("- 缺口: ${design.gapCount}")
        appendLine("- 入口: `${design.primaryCommand}`")
        appendLine()
    }
    report.screenshotExtraction?.let { extraction ->
        appendLine("### 截图解析")
        appendLine("- 状态: ${extraction.title}")
        appendLine("- 参考: ${extraction.referenceCount}")
        appendLine("- 已闭环: ${extraction.readyCount}")
        appendLine("- 待处理: ${extraction.waitingCount}")
        appendLine("- 入口: `${extraction.primaryCommand}`")
        appendLine()
    }
    report.interactionFlow?.let { flow ->
        appendLine("### 交互流程")
        appendLine("- 状态: ${flow.title}")
        appendLine("- 就绪: ${flow.readyCount}")
        appendLine("- 进行中: ${flow.activeCount}")
        appendLine("- 注意: ${flow.watchCount}")
        appendLine("- 阻塞: ${flow.blockedCount}")
        appendLine("- 入口: `${flow.primaryCommand}`")
        appendLine()
    }
    report.selfModel?.let { self ->
        appendLine("### 自我模型")
        appendLine("- 状态: ${self.title}")
        appendLine("- 已建模: ${self.readyCount}")
        appendLine("- 需关注: ${self.watchCount}")
        appendLine("- 缺口: ${self.gapCount}")
        appendLine("- 入口: `${self.primaryCommand}`")
        appendLine()
    }
    appendLine("### Codex 对标")
    appendLine("- ${report.parity.title}")
    appendLine("- 已具备 ${report.parity.readyCount} · 注意 ${report.parity.watchCount} · 缺口 ${report.parity.gapCount}")
    appendLine()
    appendLine("### 清单")
    appendLine("- 缺口: ${report.checklist.missingCount}")
    appendLine("- 注意: ${report.checklist.watchCount}")
    appendLine("- 就绪: ${report.checklist.readyCount}")
}
