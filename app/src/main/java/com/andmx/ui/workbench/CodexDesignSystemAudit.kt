package com.andmx.ui.workbench

internal enum class DesignAuditState(val label: String) {
    READY("已对齐"),
    WATCH("注意"),
    GAP("缺口"),
}

internal data class CodexDesignSystemItem(
    val title: String,
    val state: DesignAuditState,
    val principle: String,
    val andmxApplication: String,
    val command: String,
)

internal data class CodexDesignSystemAudit(
    val title: String,
    val referenceCount: Int,
    val items: List<CodexDesignSystemItem>,
    val primaryCommand: String,
) {
    val readyCount: Int get() = items.count { it.state == DesignAuditState.READY }
    val watchCount: Int get() = items.count { it.state == DesignAuditState.WATCH }
    val gapCount: Int get() = items.count { it.state == DesignAuditState.GAP }
}

internal fun buildCodexDesignSystemAudit(
    references: UiReferenceLedger,
    blueprint: UiReplicaBlueprint,
    surfaceMap: CodexSurfaceMap,
    visualAcceptance: VisualAcceptanceSummary,
    snapshot: AgentInspectorSnapshot,
    evidence: EvidenceLedger,
    screenshotExtraction: ScreenshotExtractionSummary? = null,
): CodexDesignSystemAudit {
    val hasReferences = references.attachmentCount > 0
    val hasChanges = snapshot.changedFiles > 0
    val hasVerification = evidence.verificationCount > 0
    val surfaceReady = surfaceMap.waitingCount == 0 && surfaceMap.partialCount == 0
    val visualReady = visualAcceptance.waitingCount == 0
    val extractedOrImplementing = blueprint.state != BlueprintState.WAITING_REFERENCES

    fun screenshotAware(readyWhenReferenced: DesignAuditState = DesignAuditState.WATCH): DesignAuditState =
        if (hasReferences) readyWhenReferenced else DesignAuditState.WATCH

    val items = listOf(
        CodexDesignSystemItem(
            title = "工作台密度",
            state = DesignAuditState.READY,
            principle = "Codex 的主界面优先服务长时间工作: 信息密集、分区克制、正文可快速扫描。",
            andmxApplication = "Sidebar、Chat、Inspector、Progress 和 WorkPane 采用紧凑标题、短标签和指标网格。",
            command = "/surfaces",
        ),
        CodexDesignSystemItem(
            title = "布局骨架",
            state = if (surfaceReady) DesignAuditState.READY else DesignAuditState.WATCH,
            principle = "桌面是三栏/右侧工作区, 移动端以聊天为核心并通过面板补充状态。",
            andmxApplication = "Codex UI 表面地图追踪会话流、输入区、任务面板、Inspector、工作区和命令面板。",
            command = "/surfaces",
        ),
        CodexDesignSystemItem(
            title = "图标化控制",
            state = DesignAuditState.READY,
            principle = "常用操作优先使用熟悉图标, 文本只承担命令名、状态和必要说明。",
            andmxApplication = "Workbench chrome、SearchOverlay、Inspector 行动入口和 Progress 行使用 Material 图标与短命令。",
            command = "/tools",
        ),
        CodexDesignSystemItem(
            title = "卡片边界",
            state = DesignAuditState.READY,
            principle = "卡片用于消息、工具、审批、弹层和重复项; 半径克制, 避免页面区块套卡片。",
            andmxApplication = "主题半径和 Inspector/Progress 行统一使用小半径, 工作区保持清晰分隔而非装饰堆叠。",
            command = "/visual-check",
        ),
        CodexDesignSystemItem(
            title = "状态语言",
            state = DesignAuditState.READY,
            principle = "运行、等待授权、失败、验证、注意、缺口和就绪都要可读且可追溯。",
            andmxApplication = "GoalPhase、Checklist、Parity、VisualAcceptance、DeliveryReport 都有明确状态枚举和命令入口。",
            command = "/checklist",
        ),
        CodexDesignSystemItem(
            title = "截图证据",
            state = when {
                !hasReferences -> DesignAuditState.WATCH
                screenshotExtraction != null && screenshotExtraction.waitingCount == 0 -> DesignAuditState.READY
                screenshotExtraction != null -> DesignAuditState.WATCH
                else -> DesignAuditState.READY
            },
            principle = "真实截图是复刻的输入证据, 不能只靠记忆或描述猜界面。",
            andmxApplication = if (hasReferences) {
                "${references.attachmentCount} 个 UI 参考已进入 /references、/blueprint、/evidence; /screenshot-extract 会拆解具体内容。"
            } else {
                "Computer Use 不能读取当前 Codex 宿主窗口; 需要用户添加 Codex 截图后继续提取。"
            },
            command = if (hasReferences) "/screenshot-extract" else "/references",
        ),
        CodexDesignSystemItem(
            title = "文本稳定",
            state = screenshotAware(if (visualReady || hasChanges) DesignAuditState.READY else DesignAuditState.WATCH),
            principle = "按钮、标签、指标和卡片文字必须在小屏、宽屏、长路径和中文环境中不挤压。",
            andmxApplication = "视觉验收清单覆盖移动端稳定性、文本不挤压、计数和标签尺寸稳定。",
            command = "/visual-check",
        ),
        CodexDesignSystemItem(
            title = "色彩角色",
            state = DesignAuditState.READY,
            principle = "使用中性色承载工作密度, 用少量强调色表达行动、警告和选中。",
            andmxApplication = "AndmxTheme 统一 canvas/surface/sunken/accent/warning/selected 等语义色。",
            command = "/instructions",
        ),
        CodexDesignSystemItem(
            title = "交互闭环",
            state = when {
                !hasReferences -> DesignAuditState.WATCH
                surfaceReady && visualReady -> DesignAuditState.READY
                else -> DesignAuditState.WATCH
            },
            principle = "每个 UI 表面都要能回到聊天时间线、命令面板、证据和交付报告。",
            andmxApplication = "Surface map、visual check、parity 和 delivery report 通过 slash 命令串联。",
            command = if (hasReferences) "/report" else "/surfaces",
        ),
        CodexDesignSystemItem(
            title = "实现与验证",
            state = when {
                hasChanges && hasVerification -> DesignAuditState.READY
                hasChanges -> DesignAuditState.WATCH
                hasReferences && extractedOrImplementing -> DesignAuditState.WATCH
                else -> DesignAuditState.WATCH
            },
            principle = "设计复刻必须落到 Compose 文件、状态模型、测试和 APK, 而不是停在说明文档。",
            andmxApplication = if (hasVerification) "已有验证证据可进入交付报告。" else "实现后需要 Kotlin 编译、单测和 assembleProotDebug 证据。",
            command = if (hasVerification) "/report" else "/verify",
        ),
    )

    val firstOpen = items.firstOrNull { it.state != DesignAuditState.READY }
    val title = when {
        items.any { it.state == DesignAuditState.GAP } -> "设计系统仍有硬缺口"
        firstOpen == null -> "Codex 设计系统审计已闭环"
        !hasReferences -> "等待 Codex 截图补齐设计证据"
        else -> "Codex 设计系统正在对齐"
    }
    return CodexDesignSystemAudit(
        title = title,
        referenceCount = references.attachmentCount,
        items = items,
        primaryCommand = if (!hasReferences) "/references" else firstOpen?.command ?: "/report",
    )
}

internal fun codexDesignSystemText(audit: CodexDesignSystemAudit): String = buildString {
    appendLine("## Codex 设计系统审计")
    appendLine("- 状态: **${audit.title}**")
    appendLine("- 截图参考: ${audit.referenceCount}")
    appendLine("- 已对齐: ${audit.readyCount}")
    appendLine("- 注意: ${audit.watchCount}")
    appendLine("- 缺口: ${audit.gapCount}")
    appendLine("- 建议入口: `${audit.primaryCommand}`")
    appendLine()
    audit.items.forEach { item ->
        appendLine("### ${item.title}")
        appendLine("- 状态: **${item.state.label}**")
        appendLine("- 原则: ${item.principle}")
        appendLine("- AndMX 落点: ${item.andmxApplication}")
        appendLine("- 入口: `${item.command}`")
        appendLine()
    }
}
