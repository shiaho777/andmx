package com.andmx.ui.workbench

internal enum class BlueprintState(val label: String) {
    WAITING_REFERENCES("等待参考"),
    READY_TO_EXTRACT("待提取"),
    IMPLEMENTING("实现中"),
    VERIFYING("验证中"),
}

internal data class UiReplicaBlueprint(
    val state: BlueprintState,
    val title: String,
    val referenceCount: Int,
    val extractionTasks: List<String>,
    val targetSurfaces: List<String>,
    val acceptanceChecks: List<String>,
    val primaryCommand: String,
)

internal fun buildUiReplicaBlueprint(
    references: UiReferenceLedger,
    snapshot: AgentInspectorSnapshot,
    evidence: EvidenceLedger,
): UiReplicaBlueprint {
    val hasReferences = references.attachmentCount > 0
    val hasChanges = snapshot.changedFiles > 0
    val hasVerification = evidence.verificationCount > 0
    val state = when {
        !hasReferences -> BlueprintState.WAITING_REFERENCES
        hasChanges && hasVerification -> BlueprintState.VERIFYING
        hasChanges -> BlueprintState.IMPLEMENTING
        else -> BlueprintState.READY_TO_EXTRACT
    }
    return UiReplicaBlueprint(
        state = state,
        title = when (state) {
            BlueprintState.WAITING_REFERENCES -> "等待截图参考"
            BlueprintState.READY_TO_EXTRACT -> "先提取界面模式"
            BlueprintState.IMPLEMENTING -> "按蓝图审查实现"
            BlueprintState.VERIFYING -> "核验复刻结果"
        },
        referenceCount = references.attachmentCount,
        extractionTasks = listOf(
            "布局: 分辨主区域、侧栏、顶栏、底栏、弹层和工作面板",
            "控件: 记录按钮、图标、输入框、标签、分段控件、列表和状态徽标",
            "交互: 梳理命令面板、审批卡、工具展开、Diff/Files/Terminal/Browser 切换",
            "状态: 提取运行中、待授权、失败、完成、缺口、注意和就绪的视觉表达",
            "工程: 映射到 Compose 组件、状态模型、slash 命令、测试和 APK 验证",
        ),
        targetSurfaces = listOf(
            "ChatPane / Composer / MessageList",
            "AgentInspectorPane / ProgressPopover",
            "WorkPane / Files / Terminal / Diff / Browser",
            "CommandPalette / SlashCommands",
            "EvidenceLedger / Checklist / NextAction / Parity",
        ),
        acceptanceChecks = listOf(
            "截图参考进入 /references、/evidence 和 Inspector 指标",
            "截图驱动任务会在 /next 和 /checklist 中出现明确下一步",
            "实现后能通过 Kotlin 编译、单测和 assembleProotDebug",
            "移动界面文本不挤压, 卡片、按钮、标签和计数稳定",
            "最终汇报包含改动、验证结果和 APK 路径",
        ),
        primaryCommand = when (state) {
            BlueprintState.WAITING_REFERENCES -> "/references"
            BlueprintState.READY_TO_EXTRACT -> "/blueprint"
            BlueprintState.IMPLEMENTING -> "/changes"
            BlueprintState.VERIFYING -> "/verify"
        },
    )
}

internal fun uiReplicaBlueprintText(blueprint: UiReplicaBlueprint): String = buildString {
    appendLine("## UI 复刻蓝图")
    appendLine("- 状态: **${blueprint.state.label}**")
    appendLine("- 动作: ${blueprint.title}")
    appendLine("- 参考: ${blueprint.referenceCount}")
    appendLine("- 入口: `${blueprint.primaryCommand}`")
    appendLine()
    appendLine("### 提取任务")
    blueprint.extractionTasks.forEach { appendLine("- $it") }
    appendLine()
    appendLine("### 落地区域")
    blueprint.targetSurfaces.forEach { appendLine("- $it") }
    appendLine()
    appendLine("### 验收")
    blueprint.acceptanceChecks.forEach { appendLine("- $it") }
}
