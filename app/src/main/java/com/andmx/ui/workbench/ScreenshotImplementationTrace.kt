package com.andmx.ui.workbench

import com.andmx.agent.ChangeSummaryItem

internal enum class TraceState(val label: String) {
    WAITING_REFERENCE("等截图"),
    NEEDS_MAPPING("待映射"),
    IMPLEMENTING("实现中"),
    NEEDS_VERIFICATION("待验证"),
    READY("已闭环"),
}

internal data class ScreenshotTraceItem(
    val reference: String,
    val state: TraceState,
    val targetSurface: String,
    val targetFiles: List<String>,
    val changedFiles: List<String>,
    val verification: List<String>,
    val acceptance: List<String>,
    val command: String,
    val referenceId: String = "",
    val assetPath: String = "",
)

internal data class ScreenshotImplementationTrace(
    val title: String,
    val items: List<ScreenshotTraceItem>,
    val primaryCommand: String,
) {
    val referenceCount: Int get() = items.size
    val readyCount: Int get() = items.count { it.state == TraceState.READY }
    val waitingCount: Int get() = items.size - readyCount
    val changedFileCount: Int get() = items.flatMap { it.changedFiles }.distinct().size
}

internal fun buildScreenshotImplementationTrace(
    board: UiReferenceBoard,
    surfaceMap: CodexSurfaceMap,
    changes: List<ChangeSummaryItem>,
    verifications: List<VerificationEntry>,
    evidence: EvidenceLedger,
): ScreenshotImplementationTrace {
    val changedPaths = changes.map { it.path }
    val verificationLines = verifications.map { "${it.state.verificationStateLabel()} · ${it.command}" }
    val surfaceTargets = surfaceMap.surfaces
        .filter { it.title in screenshotTargetSurfaceTitles }
        .map { it.title to it.andmxSurface }

    fun targetFor(index: Int, item: UiReferenceBoardItem): Pair<String, String> {
        val label = item.label.lowercase()
        val preferred = when {
            label.contains("composer") || label.contains("输入") -> surfaceTargets.firstOrNull { it.first == "输入区" }
            label.contains("inspector") || label.contains("状态") -> surfaceTargets.firstOrNull { it.first == "右侧 Inspector" }
            label.contains("diff") || label.contains("terminal") || label.contains("files") || label.contains("browser") -> surfaceTargets.firstOrNull { it.first == "工作区面板" }
            label.contains("command") || label.contains("命令") -> surfaceTargets.firstOrNull { it.first == "命令面板" }
            label.contains("approval") || label.contains("权限") || label.contains("审批") -> surfaceTargets.firstOrNull { it.first == "审批与安全" }
            label.contains("progress") || label.contains("任务") -> surfaceTargets.firstOrNull { it.first == "任务面板" }
            else -> null
        }
        val fallback = surfaceTargets.getOrNull(index % surfaceTargets.size)
        return preferred ?: fallback ?: ("截图复刻流水线" to "UiReferenceBoard / ScreenshotImplementationTrace")
    }

    fun filesFor(surface: String): List<String> {
        val base = when {
            surface.contains("ChatPane") || surface.contains("MessageList") ->
                listOf("app/src/main/java/com/andmx/ui/workbench/ChatPane.kt", "app/src/main/java/com/andmx/ui/workbench/Composer.kt")
            surface.contains("AgentInspectorPane") ->
                listOf("app/src/main/java/com/andmx/ui/workbench/AgentInspectorPane.kt")
            surface.contains("ProgressPopover") || surface.contains("WorkbenchScreen") ->
                listOf("app/src/main/java/com/andmx/ui/workbench/WorkbenchScreen.kt", "app/src/main/java/com/andmx/ui/workbench/ProgressTabsModel.kt")
            surface.contains("WorkPane") || surface.contains("Terminal") || surface.contains("Diff") || surface.contains("Browser") ->
                listOf("app/src/main/java/com/andmx/ui/workbench/WorkPane.kt", "app/src/main/java/com/andmx/ui/workbench/DiffPane.kt", "app/src/main/java/com/andmx/ui/workbench/TerminalPane.kt", "app/src/main/java/com/andmx/ui/workbench/BrowserPane.kt")
            surface.contains("CommandPalette") || surface.contains("SearchOverlay") ->
                listOf("app/src/main/java/com/andmx/ui/workbench/CommandPalette.kt", "app/src/main/java/com/andmx/ui/workbench/SearchOverlay.kt")
            surface.contains("Approval") || surface.contains("ToolPolicy") ->
                listOf("app/src/main/java/com/andmx/ui/workbench/ToolPolicySummary.kt", "app/src/main/java/com/andmx/ui/conversation/ConversationController.kt")
            else ->
                listOf("app/src/main/java/com/andmx/ui/workbench/UiReferenceBoard.kt", "app/src/main/java/com/andmx/ui/workbench/ScreenshotExtraction.kt")
        }
        return (base + changedPaths.filter { path -> base.any { target -> path.endsWith(target.substringAfterLast('/')) } }).distinct()
    }

    fun stateFor(item: UiReferenceBoardItem): TraceState = when (item.state) {
        UiReferenceBoardState.WAITING -> TraceState.WAITING_REFERENCE
        UiReferenceBoardState.READY_TO_EXTRACT -> TraceState.NEEDS_MAPPING
        UiReferenceBoardState.IMPLEMENTING -> TraceState.IMPLEMENTING
        UiReferenceBoardState.VERIFYING -> TraceState.NEEDS_VERIFICATION
        UiReferenceBoardState.READY -> TraceState.READY
    }

    fun referenceIdFor(item: UiReferenceBoardItem): String =
        item.evidence.asSequence()
            .mapNotNull { line -> Regex("""参考ID:\s*(ref:[a-f0-9]{8})""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            .orEmpty()

    fun assetPathFor(item: UiReferenceBoardItem): String =
        item.evidence.asSequence()
            .mapNotNull { line -> Regex("""本地资产:\s*(\S+)""").find(line)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            .orEmpty()

    val items = board.items.mapIndexed { index, boardItem ->
        val (_, surface) = targetFor(index, boardItem)
        val files = filesFor(surface)
        val state = stateFor(boardItem)
        ScreenshotTraceItem(
            reference = boardItem.label,
            state = state,
            targetSurface = surface,
            targetFiles = files,
            changedFiles = changedPaths.filter { path -> files.any { target -> path.endsWith(target.substringAfterLast('/')) } },
            verification = verificationLines.ifEmpty { listOf("暂无验证记录") },
            acceptance = listOf(
                "参考进入 /references 和 /evidence",
                "本地资产可在 /references、/evidence 和 /trace 中追踪",
                "解析进入 /screenshot-extract、/blueprint、/surfaces",
                "实现进入 /changes 和 Diff",
                "验证进入 /verify、/visual-check、/report",
            ),
            command = when (state) {
                TraceState.WAITING_REFERENCE -> "/references"
                TraceState.NEEDS_MAPPING -> "/screenshot-extract"
                TraceState.IMPLEMENTING -> "/changes"
                TraceState.NEEDS_VERIFICATION -> "/verify"
                TraceState.READY -> "/report"
            },
            referenceId = referenceIdFor(boardItem),
            assetPath = assetPathFor(boardItem),
        )
    }

    val firstOpen = items.firstOrNull { it.state != TraceState.READY }
    return ScreenshotImplementationTrace(
        title = when (firstOpen?.state) {
            TraceState.WAITING_REFERENCE -> "等待截图建立实现追踪"
            TraceState.NEEDS_MAPPING -> "截图等待映射到实现"
            TraceState.IMPLEMENTING -> "截图映射正在实现"
            TraceState.NEEDS_VERIFICATION -> "截图实现等待验证"
            null, TraceState.READY -> "截图实现追踪已闭环"
        },
        items = items,
        primaryCommand = firstOpen?.command ?: "/report",
    )
}

internal fun screenshotImplementationTraceText(trace: ScreenshotImplementationTrace): String = buildString {
    appendLine("## 截图实现追踪")
    appendLine("- 状态: **${trace.title}**")
    appendLine("- 参考: ${trace.referenceCount}")
    appendLine("- 已闭环: ${trace.readyCount}")
    appendLine("- 待处理: ${trace.waitingCount}")
    appendLine("- 变更文件: ${trace.changedFileCount}")
    appendLine("- 建议入口: `${trace.primaryCommand}`")
    appendLine()
    trace.items.forEach { item ->
        appendLine("### ${item.reference}")
        if (item.referenceId.isNotBlank()) appendLine("- 参考ID: `${item.referenceId}`")
        if (item.assetPath.isNotBlank()) appendLine("- 本地资产: `${item.assetPath}`")
        appendLine("- 状态: **${item.state.label}**")
        appendLine("- 目标表面: `${item.targetSurface}`")
        appendLine("- 入口: `${item.command}`")
        appendLine()
        appendLine("#### 目标文件")
        item.targetFiles.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("#### 已变更")
        if (item.changedFiles.isEmpty()) {
            appendLine("- 暂无对应变更")
        } else {
            item.changedFiles.forEach { appendLine("- `$it`") }
        }
        appendLine()
        appendLine("#### 验证")
        item.verification.take(4).forEach { appendLine("- $it") }
        appendLine()
        appendLine("#### 验收")
        item.acceptance.forEach { appendLine("- $it") }
        appendLine()
    }
}
