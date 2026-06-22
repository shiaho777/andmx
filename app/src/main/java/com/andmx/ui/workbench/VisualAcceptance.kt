package com.andmx.ui.workbench

internal enum class VisualAcceptanceState(val label: String) {
    READY("可核查"),
    NEEDS_REFERENCE("等截图"),
    NEEDS_EXTRACTION("待提取"),
    NEEDS_IMPLEMENTATION("待实现"),
    NEEDS_VERIFICATION("待验证"),
}

internal data class VisualAcceptanceItem(
    val title: String,
    val state: VisualAcceptanceState,
    val detail: String,
    val command: String,
)

internal data class VisualAcceptanceSummary(
    val title: String,
    val referenceCount: Int,
    val items: List<VisualAcceptanceItem>,
    val primaryCommand: String,
) {
    val readyCount: Int get() = items.count { it.state == VisualAcceptanceState.READY }
    val waitingCount: Int get() = items.size - readyCount
}

internal fun buildVisualAcceptanceSummary(
    references: UiReferenceLedger,
    blueprint: UiReplicaBlueprint,
    surfaceMap: CodexSurfaceMap,
    snapshot: AgentInspectorSnapshot,
    evidence: EvidenceLedger,
    verifications: List<VerificationEntry>,
): VisualAcceptanceSummary {
    val hasReferences = references.attachmentCount > 0
    val hasChanges = snapshot.changedFiles > 0
    val passedVerification = verifications.any { it.state == VerificationState.PASSED } || evidence.verificationCount > 0
    val layoutState = when {
        !hasReferences -> VisualAcceptanceState.NEEDS_REFERENCE
        blueprint.state == BlueprintState.READY_TO_EXTRACT -> VisualAcceptanceState.NEEDS_EXTRACTION
        else -> VisualAcceptanceState.READY
    }
    val implementationState = when {
        !hasReferences -> VisualAcceptanceState.NEEDS_REFERENCE
        !hasChanges && blueprint.state != BlueprintState.VERIFYING -> VisualAcceptanceState.NEEDS_IMPLEMENTATION
        else -> VisualAcceptanceState.READY
    }
    val verificationState = when {
        !hasReferences -> VisualAcceptanceState.NEEDS_REFERENCE
        !hasChanges -> VisualAcceptanceState.NEEDS_IMPLEMENTATION
        !passedVerification -> VisualAcceptanceState.NEEDS_VERIFICATION
        else -> VisualAcceptanceState.READY
    }

    val items = listOf(
        VisualAcceptanceItem(
            title = "截图证据",
            state = if (hasReferences) VisualAcceptanceState.READY else VisualAcceptanceState.NEEDS_REFERENCE,
            detail = if (hasReferences) "${references.attachmentCount} 个截图/附件参考已进入证据链" else "先添加 Codex 界面截图或附件",
            command = "/references",
        ),
        VisualAcceptanceItem(
            title = "布局提取",
            state = layoutState,
            detail = "主区域、侧栏、顶栏、底栏、弹层和工作面板需要从截图中拆出。",
            command = if (hasReferences) "/blueprint" else "/references",
        ),
        VisualAcceptanceItem(
            title = "控件与状态",
            state = if (!hasReferences) VisualAcceptanceState.NEEDS_REFERENCE else if (surfaceMap.waitingCount > 0) VisualAcceptanceState.NEEDS_EXTRACTION else VisualAcceptanceState.READY,
            detail = "按钮、输入框、图标、标签、工具卡、审批卡和运行状态需要落到 surface map。",
            command = "/surfaces",
        ),
        VisualAcceptanceItem(
            title = "交互路径",
            state = if (!hasReferences) VisualAcceptanceState.NEEDS_REFERENCE else if (surfaceMap.partialCount > 0) VisualAcceptanceState.NEEDS_IMPLEMENTATION else VisualAcceptanceState.READY,
            detail = "命令面板、附件输入、工具展开、审批、Diff/Files/Terminal/Browser 切换需要可操作。",
            command = "/surfaces",
        ),
        VisualAcceptanceItem(
            title = "Compose 落地",
            state = implementationState,
            detail = if (hasChanges) "${snapshot.changedFiles} 个文件有待审实现" else "还没有截图驱动的实现变更",
            command = if (hasChanges) "/changes" else "/blueprint",
        ),
        VisualAcceptanceItem(
            title = "移动端稳定性",
            state = implementationState,
            detail = "文本不挤压, 卡片/按钮/标签/计数尺寸稳定, 小屏与宽屏都能扫描。",
            command = if (hasChanges) "/changes" else "/blueprint",
        ),
        VisualAcceptanceItem(
            title = "验证证据",
            state = verificationState,
            detail = if (passedVerification) "已有构建、测试或诊断证据" else "实现后需要编译、单测、打包或截图核验",
            command = "/verify",
        ),
    )

    val primary = items.firstOrNull { it.state != VisualAcceptanceState.READY }?.command ?: "/report"
    val title = when (items.firstOrNull { it.state != VisualAcceptanceState.READY }?.state) {
        VisualAcceptanceState.NEEDS_REFERENCE -> "等待截图参考"
        VisualAcceptanceState.NEEDS_EXTRACTION -> "等待提取截图结构"
        VisualAcceptanceState.NEEDS_IMPLEMENTATION -> "等待实现截图差异"
        VisualAcceptanceState.NEEDS_VERIFICATION -> "等待视觉验证证据"
        else -> "视觉复刻可核查"
    }
    return VisualAcceptanceSummary(
        title = title,
        referenceCount = references.attachmentCount,
        items = items,
        primaryCommand = primary,
    )
}

internal fun visualAcceptanceText(summary: VisualAcceptanceSummary): String = buildString {
    appendLine("## 视觉验收清单")
    appendLine("- 状态: **${summary.title}**")
    appendLine("- 截图参考: ${summary.referenceCount}")
    appendLine("- 就绪: ${summary.readyCount}")
    appendLine("- 待处理: ${summary.waitingCount}")
    appendLine("- 建议入口: `${summary.primaryCommand}`")
    appendLine()
    summary.items.forEach { item ->
        appendLine("- **${item.state.label}** · ${item.title}: ${item.detail}")
        appendLine("  - 入口: `${item.command}`")
    }
}
