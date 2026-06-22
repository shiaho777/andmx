package com.andmx.ui.workbench

internal enum class UiReferenceKind(val label: String) {
    CODEX_SCREENSHOT("Codex截图"),
    UI_SCREENSHOT("界面截图"),
    IMAGE("图片"),
    ATTACHMENT("附件"),
}

internal enum class UiReferenceBoardState(val label: String) {
    WAITING("等待参考"),
    READY_TO_EXTRACT("待解析"),
    IMPLEMENTING("实现中"),
    VERIFYING("验证中"),
    READY("已闭环"),
}

internal data class UiReferenceBoardItem(
    val label: String,
    val kind: UiReferenceKind,
    val state: UiReferenceBoardState,
    val detail: String,
    val evidence: List<String>,
    val extractionTargets: List<String>,
    val commands: List<String>,
)

internal data class UiReferenceBoard(
    val title: String,
    val items: List<UiReferenceBoardItem>,
    val primaryCommand: String,
) {
    val referenceCount: Int get() = items.size
    val imageCount: Int get() = items.count { it.kind != UiReferenceKind.ATTACHMENT }
    val codexCount: Int get() = items.count { it.kind == UiReferenceKind.CODEX_SCREENSHOT }
    val readyCount: Int get() = items.count { it.state == UiReferenceBoardState.READY }
    val openCount: Int get() = items.size - readyCount
}

internal fun buildUiReferenceBoard(
    references: UiReferenceLedger,
    blueprint: UiReplicaBlueprint,
    screenshotExtraction: ScreenshotExtractionSummary,
    visualAcceptance: VisualAcceptanceSummary,
    designSystem: CodexDesignSystemAudit,
    evidence: EvidenceLedger,
    snapshot: AgentInspectorSnapshot,
): UiReferenceBoard {
    val hasReferences = references.attachmentCount > 0
    val fallback = if (hasReferences) {
        references.items
    } else {
        listOf(
            UiReferenceItem(
                label = "等待 Codex 截图",
                detail = "添加截图后会建立逐图解析对象",
                image = true,
            ),
        )
    }

    fun kindFor(reference: UiReferenceItem): UiReferenceKind {
        val label = reference.label.lowercase()
        return when {
            !reference.image -> UiReferenceKind.ATTACHMENT
            label.contains("codex") -> UiReferenceKind.CODEX_SCREENSHOT
            label.contains("ui") || label.contains("ux") || label.contains("截图") || label.contains("截屏") || label.contains("界面") -> UiReferenceKind.UI_SCREENSHOT
            else -> UiReferenceKind.IMAGE
        }
    }

    fun stateFor(): UiReferenceBoardState = when {
        !hasReferences -> UiReferenceBoardState.WAITING
        blueprint.state == BlueprintState.READY_TO_EXTRACT || screenshotExtraction.items.any { it.state == ScreenshotExtractionState.NEEDS_EXTRACTION } -> UiReferenceBoardState.READY_TO_EXTRACT
        snapshot.changedFiles > 0 && evidence.verificationCount == 0 -> UiReferenceBoardState.IMPLEMENTING
        screenshotExtraction.waitingCount > 0 || visualAcceptance.waitingCount > 0 || designSystem.watchCount > 0 || designSystem.gapCount > 0 -> UiReferenceBoardState.VERIFYING
        else -> UiReferenceBoardState.READY
    }

    val commonTargets = listOf(
        "布局结构",
        "控件清单",
        "状态语言",
        "交互路径",
        "设计 token",
        "验证证据",
    )
    val items = fallback.mapIndexed { index, reference ->
        val state = stateFor()
        UiReferenceBoardItem(
            label = if (hasReferences) "图/附件 ${index + 1}: ${reference.label}" else reference.label,
            kind = kindFor(reference),
            state = state,
            detail = when (state) {
                UiReferenceBoardState.WAITING -> "尚未收到截图; 先准备截图导入、解析和验收链路。"
                UiReferenceBoardState.READY_TO_EXTRACT -> "参考已进入证据链, 下一步提取布局、控件、状态和交互。"
                UiReferenceBoardState.IMPLEMENTING -> "截图已映射成实现任务, 当前需要审查 Compose/Diff 变更。"
                UiReferenceBoardState.VERIFYING -> "已有参考或实现, 还需要设计审计、视觉验收或构建测试证据。"
                UiReferenceBoardState.READY -> "截图、实现、验证和交付证据已形成闭环。"
            },
            evidence = buildList {
                add(reference.detail)
                if (reference.meta.isNotBlank()) add("图片元数据: ${reference.meta}")
                if (reference.referenceId.isNotBlank()) add("参考ID: ${reference.referenceId}")
                if (reference.assetPath.isNotBlank()) add("本地资产: ${reference.assetPath}")
                add("UI 参考: ${references.attachmentCount}")
                add("证据: ${evidence.uiReferenceCount}")
                add("验证: ${evidence.verificationCount}")
            },
            extractionTargets = commonTargets,
            commands = when (state) {
                UiReferenceBoardState.WAITING -> listOf("/references")
                UiReferenceBoardState.READY_TO_EXTRACT -> listOf("/screenshot-extract", "/blueprint", "/surfaces")
                UiReferenceBoardState.IMPLEMENTING -> listOf("/changes", "/design-system", "/visual-check")
                UiReferenceBoardState.VERIFYING -> listOf("/visual-check", "/design-system", "/verify")
                UiReferenceBoardState.READY -> listOf("/report", "/evidence")
            },
        )
    }

    val firstOpen = items.firstOrNull { it.state != UiReferenceBoardState.READY }
    return UiReferenceBoard(
        title = when (firstOpen?.state) {
            UiReferenceBoardState.WAITING -> "等待截图建立参考板"
            UiReferenceBoardState.READY_TO_EXTRACT -> "截图参考等待解析"
            UiReferenceBoardState.IMPLEMENTING -> "截图参考正在落地"
            UiReferenceBoardState.VERIFYING -> "截图参考等待验证"
            null, UiReferenceBoardState.READY -> "截图参考板已闭环"
        },
        items = items,
        primaryCommand = firstOpen?.commands?.firstOrNull() ?: "/report",
    )
}

internal fun uiReferenceBoardText(board: UiReferenceBoard): String = buildString {
    appendLine("## 截图参考板")
    appendLine("- 状态: **${board.title}**")
    appendLine("- 参考: ${board.referenceCount}")
    appendLine("- 图片: ${board.imageCount}")
    appendLine("- Codex: ${board.codexCount}")
    appendLine("- 已闭环: ${board.readyCount}")
    appendLine("- 待处理: ${board.openCount}")
    appendLine("- 建议入口: `${board.primaryCommand}`")
    appendLine()
    board.items.forEach { item ->
        appendLine("### ${item.label}")
        appendLine("- 类型: ${item.kind.label}")
        appendLine("- 状态: **${item.state.label}**")
        appendLine("- 说明: ${item.detail}")
        appendLine("- 入口: ${item.commands.joinToString(" ") { "`$it`" }}")
        appendLine()
        appendLine("#### 解析目标")
        item.extractionTargets.forEach { appendLine("- $it") }
        appendLine()
        appendLine("#### 证据")
        item.evidence.forEach { appendLine("- $it") }
        appendLine()
    }
}
