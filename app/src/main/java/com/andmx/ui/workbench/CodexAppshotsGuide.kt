package com.andmx.ui.workbench

internal enum class AppshotCaptureState(val label: String) {
    WAITING_REFERENCE("等视觉上下文"),
    READY_TO_EXTRACT("待解析"),
    NEEDS_IMPLEMENTATION("待落地"),
    NEEDS_VERIFICATION("待验证"),
    READY("已闭环"),
}

internal data class AppshotCaptureStep(
    val title: String,
    val detail: String,
    val command: String,
)

internal data class CodexAppshotCaptureGuide(
    val title: String,
    val state: AppshotCaptureState,
    val referenceCount: Int,
    val codexReferenceCount: Int,
    val assetCount: Int,
    val primaryCommand: String,
    val sourceFacts: List<String>,
    val captureSteps: List<AppshotCaptureStep>,
    val landingSteps: List<AppshotCaptureStep>,
    val safetyNotes: List<String>,
)

internal fun buildCodexAppshotCaptureGuide(
    references: UiReferenceLedger,
    evidence: EvidenceLedger,
    snapshot: AgentInspectorSnapshot,
): CodexAppshotCaptureGuide {
    val referenceCount = references.attachmentCount
    val codexReferenceCount = references.items.count { it.label.contains("codex", ignoreCase = true) }
    val state = when {
        referenceCount == 0 -> AppshotCaptureState.WAITING_REFERENCE
        snapshot.changedFiles == 0 -> AppshotCaptureState.READY_TO_EXTRACT
        evidence.verificationCount == 0 -> AppshotCaptureState.NEEDS_VERIFICATION
        evidence.changeCount > 0 -> AppshotCaptureState.READY
        else -> AppshotCaptureState.NEEDS_IMPLEMENTATION
    }
    val primaryCommand = when (state) {
        AppshotCaptureState.WAITING_REFERENCE -> "/references"
        AppshotCaptureState.READY_TO_EXTRACT -> "/screenshot-extract"
        AppshotCaptureState.NEEDS_IMPLEMENTATION -> "/changes"
        AppshotCaptureState.NEEDS_VERIFICATION -> "/visual-check"
        AppshotCaptureState.READY -> "/report"
    }

    return CodexAppshotCaptureGuide(
        title = when (state) {
            AppshotCaptureState.WAITING_REFERENCE -> "等待真实 Codex 视觉上下文"
            AppshotCaptureState.READY_TO_EXTRACT -> "Codex 视觉上下文已可解析"
            AppshotCaptureState.NEEDS_IMPLEMENTATION -> "Codex 视觉上下文等待落地"
            AppshotCaptureState.NEEDS_VERIFICATION -> "Codex 视觉复刻等待验证"
            AppshotCaptureState.READY -> "Codex 视觉上下文采集链路已闭环"
        },
        state = state,
        referenceCount = referenceCount,
        codexReferenceCount = codexReferenceCount,
        assetCount = references.assetCount,
        primaryCommand = primaryCommand,
        sourceFacts = listOf(
            "Codex Appshots 会把前台窗口的可见图像和可用文本作为线程附件。",
            "Appshots 在 macOS Codex app 中可用, 默认用双 Command 键或自定义热键触发。",
            "Appshots 需要屏幕录制/系统音频录制与辅助功能权限, 采集内容应像截图和文档一样先由用户确认。",
            "Computer Use 可操作被允许的桌面 app, 但不能自动化 Codex 自身或终端类受保护宿主。",
        ),
        captureSteps = listOf(
            AppshotCaptureStep(
                title = "选定目标窗口",
                detail = "打开 Codex 桌面 app 中要对标的线程、命令面板、Inspector、Diff、Terminal、Browser 或设置界面。",
                command = "/appshots",
            ),
            AppshotCaptureStep(
                title = "采集视觉上下文",
                detail = "用 Codex Appshots、系统截图或手动附件把前台窗口送进当前 AndMX 线程。",
                command = "/references",
            ),
            AppshotCaptureStep(
                title = "保留可追踪标识",
                detail = "截图进入消息后应带有 ref: 标识和 asset: 路径, 方便后续解析、实现和报告回指。",
                command = "/evidence",
            ),
        ),
        landingSteps = listOf(
            AppshotCaptureStep(
                title = "拆解界面结构",
                detail = "按布局、控件、状态语言、交互路径、设计 token 和验证证据逐项提取。",
                command = "/screenshot-extract",
            ),
            AppshotCaptureStep(
                title = "映射到 AndMX 表面",
                detail = "把 Codex 视觉线索映射到会话流、输入区、任务面板、Inspector、工作区和命令面板。",
                command = "/surfaces",
            ),
            AppshotCaptureStep(
                title = "执行复刻变更",
                detail = "只改与截图目标相关的 Compose、状态模型、命令入口或报告链路。",
                command = "/changes",
            ),
            AppshotCaptureStep(
                title = "验证并交付",
                detail = "用视觉验收、设计审计、单测、构建和 APK 产物证明结果。",
                command = "/visual-check",
            ),
        ),
        safetyNotes = listOf(
            "不要让采集动作包含密钥、隐私内容、账号页面或无关应用窗口。",
            "由于宿主 Codex 不能被 Computer Use 自动化, AndMX 应优先使用用户提供的截图、Appshots 和参考ID。",
            "每次视觉复刻都要能从 /report 回指到截图资产、目标文件、变更和验证命令。",
        ),
    )
}

internal fun codexAppshotCaptureGuideText(guide: CodexAppshotCaptureGuide): String = buildString {
    appendLine("## Codex Appshots 采集单")
    appendLine("- 状态: **${guide.title}**")
    appendLine("- 阶段: ${guide.state.label}")
    appendLine("- 参考: ${guide.referenceCount}")
    appendLine("- Codex 参考: ${guide.codexReferenceCount}")
    appendLine("- 本地资产: ${guide.assetCount}")
    appendLine("- 建议入口: `${guide.primaryCommand}`")
    appendLine()
    appendLine("### 官方能力事实")
    guide.sourceFacts.forEach { appendLine("- $it") }
    appendLine()
    appendLine("### 采集路径")
    guide.captureSteps.forEachIndexed { index, step ->
        appendLine("${index + 1}. ${step.title}: ${step.detail}")
        appendLine("   - 入口: `${step.command}`")
    }
    appendLine()
    appendLine("### AndMX 落地路径")
    guide.landingSteps.forEachIndexed { index, step ->
        appendLine("${index + 1}. ${step.title}: ${step.detail}")
        appendLine("   - 入口: `${step.command}`")
    }
    appendLine()
    appendLine("### 安全与边界")
    guide.safetyNotes.forEach { appendLine("- $it") }
}
