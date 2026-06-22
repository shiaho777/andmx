package com.andmx.ui.workbench

internal enum class ScreenshotExtractionState(val label: String) {
    WAITING_REFERENCE("等截图"),
    NEEDS_EXTRACTION("待提取"),
    NEEDS_IMPLEMENTATION("待实现"),
    NEEDS_VERIFICATION("待验证"),
    READY("已闭环"),
}

internal val screenshotTargetSurfaceTitles = setOf(
    "会话流",
    "输入区",
    "任务面板",
    "右侧 Inspector",
    "工作区面板",
    "命令面板",
    "审批与安全",
    "浏览与桌面操作",
)

internal data class ScreenshotExtractionDimension(
    val title: String,
    val detail: String,
    val output: String,
)

internal data class ScreenshotReplicationWorkOrder(
    val title: String = "截图复刻执行单",
    val referenceId: String = "",
    val assetPath: String = "",
    val targetSurface: String = "",
    val targetFiles: List<String> = emptyList(),
    val observationChecklist: List<String> = emptyList(),
    val implementationMoves: List<String> = emptyList(),
    val verificationGates: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
)

private data class ScreenshotSurfaceProfile(
    val observationChecklist: List<String>,
    val implementationMoves: List<String>,
    val verificationGates: List<String>,
)

internal data class ScreenshotExtractionItem(
    val label: String,
    val state: ScreenshotExtractionState,
    val detail: String,
    val dimensions: List<ScreenshotExtractionDimension>,
    val targetSurfaces: List<String>,
    val acceptance: List<String>,
    val command: String,
    val workOrder: ScreenshotReplicationWorkOrder = ScreenshotReplicationWorkOrder(),
)

internal data class ScreenshotExtractionSummary(
    val title: String,
    val referenceCount: Int,
    val items: List<ScreenshotExtractionItem>,
    val primaryCommand: String,
) {
    val readyCount: Int get() = items.count { it.state == ScreenshotExtractionState.READY }
    val waitingCount: Int get() = items.size - readyCount
    val imageCount: Int get() = items.count { it.label.contains("图") || it.label.contains("截图", ignoreCase = true) }
}

internal fun buildScreenshotExtractionSummary(
    references: UiReferenceLedger,
    blueprint: UiReplicaBlueprint,
    surfaceMap: CodexSurfaceMap,
    visualAcceptance: VisualAcceptanceSummary,
    designSystem: CodexDesignSystemAudit,
    evidence: EvidenceLedger,
    snapshot: AgentInspectorSnapshot,
): ScreenshotExtractionSummary {
    val hasReferences = references.attachmentCount > 0
    val hasChanges = snapshot.changedFiles > 0
    val hasVerification = evidence.verificationCount > 0
    val allVisualReady = visualAcceptance.waitingCount == 0 && designSystem.watchCount == 0 && designSystem.gapCount == 0
    val fallbackItems = if (hasReferences) references.items else listOf(
        UiReferenceItem(
            label = "等待 Codex 截图",
            detail = "用 Appshots、系统截图或手动附件添加 Codex 界面后开始解析",
            image = true,
        ),
    )

    fun stateFor(reference: UiReferenceItem): ScreenshotExtractionState = when {
        !hasReferences -> ScreenshotExtractionState.WAITING_REFERENCE
        blueprint.state == BlueprintState.READY_TO_EXTRACT -> ScreenshotExtractionState.NEEDS_EXTRACTION
        !hasChanges -> ScreenshotExtractionState.NEEDS_IMPLEMENTATION
        !hasVerification || !allVisualReady -> ScreenshotExtractionState.NEEDS_VERIFICATION
        else -> ScreenshotExtractionState.READY
    }

    val dimensions = listOf(
        ScreenshotExtractionDimension(
            title = "布局结构",
            detail = "识别侧栏、聊天流、composer、任务面板、Inspector、工作区、弹层和底部/顶部控制。",
            output = "Compose 容器、pane 宽度、滚动区域、弹层尺寸和响应式折叠规则。",
        ),
        ScreenshotExtractionDimension(
            title = "控件清单",
            detail = "枚举按钮、图标、输入框、标签、分段控件、列表、计数、工具卡和审批卡。",
            output = "可复用组件、图标选择、点击/禁用/选中状态和命令入口。",
        ),
        ScreenshotExtractionDimension(
            title = "状态语言",
            detail = "抽取运行中、等待、失败、完成、注意、缺口、已验证和可交付状态表达。",
            output = "状态枚举、颜色角色、文案、badge 和证据/报告映射。",
        ),
        ScreenshotExtractionDimension(
            title = "交互路径",
            detail = "还原命令面板、附件输入、工具展开、审批、Diff/Files/Terminal/Browser 切换和 handoff/resume。",
            output = "用户路径、键盘快捷键、slash 命令和 Inspector/Progress 互跳。",
        ),
        ScreenshotExtractionDimension(
            title = "设计 token",
            detail = "记录密度、间距、半径、文本层级、中性色、强调色、警告色和边界线。",
            output = "AndmxTheme、Radii、Spacing、typography 和组件约束。",
        ),
        ScreenshotExtractionDimension(
            title = "验证证据",
            detail = "实现后用编译、单测、APK、截图核验或用户截图对照证明结果。",
            output = "/verify、/visual-check、/design-system 和 /report 中的可追溯证据。",
        ),
    )

    val surfaceTargets = surfaceMap.surfaces
        .filter { it.title in screenshotTargetSurfaceTitles }
        .ifEmpty { surfaceMap.surfaces }

    fun targetSurfaceFor(index: Int, reference: UiReferenceItem): CodexSurfaceSpec? {
        val label = reference.label.lowercase()
        val preferred = when {
            label.contains("composer") || label.contains("input") || label.contains("输入") || label.contains("附件") ->
                surfaceTargets.firstOrNull { it.title == "输入区" }
            label.contains("chat") || label.contains("message") || label.contains("conversation") || label.contains("会话") || label.contains("消息") ->
                surfaceTargets.firstOrNull { it.title == "会话流" }
            label.contains("inspector") || label.contains("status") || label.contains("状态") || label.contains("右侧") ->
                surfaceTargets.firstOrNull { it.title == "右侧 Inspector" }
            label.contains("progress") || label.contains("task") || label.contains("plan") || label.contains("任务") || label.contains("计划") ->
                surfaceTargets.firstOrNull { it.title == "任务面板" }
            label.contains("command") || label.contains("palette") || label.contains("slash") || label.contains("命令") || label.contains("搜索") ->
                surfaceTargets.firstOrNull { it.title == "命令面板" }
            label.contains("diff") || label.contains("terminal") || label.contains("browser") || label.contains("files") || label.contains("工作区") || label.contains("终端") ->
                surfaceTargets.firstOrNull { it.title == "工作区面板" }
            label.contains("approval") || label.contains("permission") || label.contains("安全") || label.contains("授权") || label.contains("审批") ->
                surfaceTargets.firstOrNull { it.title == "审批与安全" }
            else -> null
        }
        return preferred ?: surfaceTargets.getOrNull(index % surfaceTargets.size)
    }

    fun filesFor(surface: String, reference: UiReferenceItem): List<String> {
        val label = reference.label.lowercase()
        val base = when {
            surface.contains("Composer") || label.contains("composer") || label.contains("附件") ->
                listOf(
                    "app/src/main/java/com/andmx/ui/workbench/Composer.kt",
                    "app/src/main/java/com/andmx/ui/conversation/Attachment.kt",
                )
            surface.contains("MessageList") || surface.contains("ChatPane") ->
                listOf(
                    "app/src/main/java/com/andmx/ui/workbench/ChatPane.kt",
                    "app/src/main/java/com/andmx/ui/conversation/MessageList.kt",
                )
            surface.contains("AgentInspectorPane") ->
                listOf("app/src/main/java/com/andmx/ui/workbench/AgentInspectorPane.kt")
            surface.contains("ProgressPopover") || surface.contains("WorkbenchScreen") ->
                listOf(
                    "app/src/main/java/com/andmx/ui/workbench/WorkbenchScreen.kt",
                    "app/src/main/java/com/andmx/ui/workbench/ProgressTabsModel.kt",
                )
            surface.contains("WorkPane") || surface.contains("Terminal") || surface.contains("Diff") || surface.contains("Browser") ->
                listOf(
                    "app/src/main/java/com/andmx/ui/workbench/WorkPane.kt",
                    "app/src/main/java/com/andmx/ui/workbench/DiffPane.kt",
                    "app/src/main/java/com/andmx/ui/workbench/TerminalPane.kt",
                    "app/src/main/java/com/andmx/ui/workbench/BrowserPane.kt",
                )
            surface.contains("CommandPalette") || surface.contains("SearchOverlay") ->
                listOf(
                    "app/src/main/java/com/andmx/ui/workbench/CommandPalette.kt",
                    "app/src/main/java/com/andmx/ui/workbench/SearchOverlay.kt",
                    "app/src/main/java/com/andmx/agent/SlashCommands.kt",
                )
            surface.contains("Approval") || surface.contains("ToolPolicy") ->
                listOf(
                    "app/src/main/java/com/andmx/ui/workbench/ToolPolicySummary.kt",
                    "app/src/main/java/com/andmx/ui/conversation/ConversationController.kt",
                )
            else ->
                listOf(
                    "app/src/main/java/com/andmx/ui/workbench/UiReferenceBoard.kt",
                    "app/src/main/java/com/andmx/ui/workbench/ScreenshotExtraction.kt",
                    "app/src/main/java/com/andmx/ui/workbench/ScreenshotImplementationTrace.kt",
                )
        }
        return base.distinct()
    }

    fun surfaceProfileFor(targetSurface: String, reference: UiReferenceItem): ScreenshotSurfaceProfile {
        val label = reference.label.lowercase()
        val surface = targetSurface.lowercase()
        return when {
            surface.contains("commandpalette") || surface.contains("searchoverlay") || label.contains("command") || label.contains("palette") || label.contains("命令") ->
                ScreenshotSurfaceProfile(
                    observationChecklist = listOf(
                        "命令面板: 标出搜索框、最近命令、命令结果、会话结果和空状态分区。",
                        "命令面板: 记录选中行、hover/pressed 态、图标语义、快捷键提示和滚动位置。",
                        "命令面板: 对照中英文关键词、slash 别名、深链入口和线程搜索的优先级。",
                    ),
                    implementationMoves = listOf(
                        "在 CommandPalette.kt 固化命令元数据、关键词、别名和最近命令排序。",
                        "在 SearchOverlay.kt 校准结果行密度、键盘选中、图标、滚动定位和长文本截断。",
                        "在 SlashCommands.kt 保持命令解析、建议和命令面板搜索同源。",
                    ),
                    verificationGates = listOf(
                        "`CommandPaletteTest` 覆盖中英文搜索、slash 别名、最近命令和键盘选择。",
                        "`SlashCommandsTest` 覆盖新增命令、别名和关键词建议。",
                        "命令面板截图复刻后检查搜索框、结果列表、选中态和快捷提示不重叠。",
                    ),
                )
            surface.contains("composer") || surface.contains("attachment") || label.contains("composer") || label.contains("input") || label.contains("附件") || label.contains("输入") ->
                ScreenshotSurfaceProfile(
                    observationChecklist = listOf(
                        "输入区: 标出文本框、附件按钮、发送/停止按钮、slash 建议和技能调用入口。",
                        "输入区: 记录附件预检、UI 参考 chip、ref/asset 标识、换行和键盘安全区。",
                        "输入区: 对照空输入、含附件、发送中、可停止和未配置模型状态。",
                    ),
                    implementationMoves = listOf(
                        "在 Composer.kt 校准输入区布局、按钮尺寸、附件预检和命令建议触达。",
                        "在 Attachment.kt 保持图片元数据、UI 参考判定、ref 标识和 asset 路径可恢复。",
                        "在 ChatPane.kt 保证 composer、目标浮层和消息流在小屏/横屏下不互相挤压。",
                    ),
                    verificationGates = listOf(
                        "`AttachmentsTest` 覆盖 UI 参考、asset marker、预检入口和本地接收文案。",
                        "输入区截图复刻后检查最长文件名、附件 chip 和发送按钮不会改变工具栏高度。",
                        "未配置模型时截图仍进入 `/references`、`/evidence` 和本地资产链路。",
                    ),
                )
            surface.contains("agentinspector") || label.contains("inspector") || label.contains("status") || label.contains("状态") || label.contains("右侧") ->
                ScreenshotSurfaceProfile(
                    observationChecklist = listOf(
                        "Inspector: 标出模型、目标、上下文、工具、环境、MCP、指令栈和风险提示区块。",
                        "Inspector: 记录计数、状态 badge、进度、可点击入口和设置/继续动作。",
                        "Inspector: 对照运行中、等待授权、未配置、目标暂停和可交付状态。",
                    ),
                    implementationMoves = listOf(
                        "在 AgentInspectorPane.kt 保持状态区块可扫描, 入口按钮与 slash 输出互相指向。",
                        "在 AgentInspectorModel.kt 确保目标、工具、截图参考和上下文压力来自同一快照。",
                        "在 InstructionStackSummary.kt 与 CodexEnvironmentContract.kt 对齐可见指令和环境边界。",
                    ),
                    verificationGates = listOf(
                        "`AgentInspectorModelTest` 覆盖截图参考、工具事件、目标和上下文指标。",
                        "`InstructionStackSummaryTest` 与 `CodexEnvironmentContractTest` 覆盖环境契约输出。",
                        "Inspector 截图复刻后检查 badge、计数、按钮和说明文字在窄屏不溢出。",
                    ),
                )
            surface.contains("workpane") || surface.contains("terminal") || surface.contains("diff") || surface.contains("browser") || surface.contains("filepane") || label.contains("diff") || label.contains("terminal") || label.contains("browser") || label.contains("files") || label.contains("工作区") || label.contains("终端") ->
                ScreenshotSurfaceProfile(
                    observationChecklist = listOf(
                        "工作区: 标出 Files、Terminal、Diff、Browser 标签、当前选中态和内容工具栏。",
                        "工作区: 记录文件路径、diff 状态、终端输出、浏览器 URL 和错误/空状态。",
                        "工作区: 对照右侧面板、底部终端 dock、横屏/竖屏折叠和键盘切换路径。",
                    ),
                    implementationMoves = listOf(
                        "在 WorkPane.kt 固化标签切换、选中态、面板恢复和空状态布局。",
                        "在 DiffPane.kt、TerminalPane.kt、BrowserPane.kt 分别补齐内容密度与错误态。",
                        "在 WorkbenchScreen.kt 保证工作区、终端 dock、文件路径和 browser URL 能随线程恢复。",
                    ),
                    verificationGates = listOf(
                        "工作区截图复刻后检查标签、路径、终端输出和 diff 行号可读且不遮挡。",
                        "`DeliveryReportTest` 与 `ScreenshotImplementationTraceTest` 能回指变更、验证和目标文件。",
                        "`./gradlew assembleProotDebug --no-daemon` 确认本地 native/terminal 相关代码仍能打包。",
                    ),
                )
            surface.contains("progresspopover") || label.contains("progress") || label.contains("task") || label.contains("plan") || label.contains("任务") || label.contains("计划") ->
                ScreenshotSurfaceProfile(
                    observationChecklist = listOf(
                        "任务面板: 标出目标、计划、下一步、验证、变更、证据和 handoff 区块。",
                        "任务面板: 记录 ready/watch/gap、运行中、等待授权、暂停和可交付状态表达。",
                        "任务面板: 对照继续、打开设置、清单、报告和交接动作的可点击路径。",
                    ),
                    implementationMoves = listOf(
                        "在 ProgressTabsModel.kt 与 WorkbenchScreen.kt 保持任务分组、计数和入口状态一致。",
                        "在 ChatPane.kt 维护目标浮层继续/暂停/恢复/清除动作与 `/goal` 同步。",
                        "在 DeliveryReport.kt 保证任务面板状态能进入最终交付报告和 handoff。",
                    ),
                    verificationGates = listOf(
                        "`GoalOverlayTest` 覆盖目标浮层编辑、暂停、恢复、清除和继续动作。",
                        "`SessionChecklistTest`、`NextActionTest`、`DeliveryReportTest` 覆盖收束状态。",
                        "任务面板截图复刻后检查状态标签、动作按钮和列表密度稳定。",
                    ),
                )
            surface.contains("approval") || surface.contains("toolpolicy") || label.contains("approval") || label.contains("permission") || label.contains("安全") || label.contains("授权") || label.contains("审批") ->
                ScreenshotSurfaceProfile(
                    observationChecklist = listOf(
                        "审批: 标出风险等级、工具名、参数摘要、允许/拒绝按钮和授权模式说明。",
                        "审批: 记录等待、已允许、已拒绝、失败和只读阻止状态。",
                        "审批: 对照 Computer Use、网络、写入、执行和敏感动作的边界说明。",
                    ),
                    implementationMoves = listOf(
                        "在 MessageList.kt 审批卡保持风险文案、按钮状态和历史结果可审计。",
                        "在 ToolPolicySummary.kt 与 ConversationController.kt 保持授权模式和风险说明同源。",
                        "在 CodexToolCapabilityMap.kt 标出 Computer Use 不能自动化 Codex 自身的边界。",
                    ),
                    verificationGates = listOf(
                        "`CodexToolCapabilityMapTest` 覆盖工具能力域和 Computer Use 安全说明。",
                        "审批截图复刻后检查允许/拒绝按钮、风险 badge 和长参数摘要不重叠。",
                        "`/policy`、`/tools`、`/evidence` 能回指同一授权事件。",
                    ),
                )
            surface.contains("messagelist") || surface.contains("chatpane") || label.contains("chat") || label.contains("message") || label.contains("conversation") || label.contains("会话") || label.contains("消息") ->
                ScreenshotSurfaceProfile(
                    observationChecklist = listOf(
                        "会话流: 标出用户气泡、助手块、工具卡、引用 chip、handoff 动作和重试/分支入口。",
                        "会话流: 记录流式输出、工具运行中、工具失败、图片参考、代码块和本地命令输出。",
                        "会话流: 对照当前线程继续、新线程继续、复制恢复提示和目标保持逻辑。",
                    ),
                    implementationMoves = listOf(
                        "在 MessageList.kt 校准消息动作、引用 chip、工具组和 handoff 按钮密度。",
                        "在 HandoffResumePrompt.kt 保持恢复提示解析、标题生成和目标覆盖规则稳定。",
                        "在 ConversationController.kt 确保当前线程续接不会误覆盖持久目标。",
                    ),
                    verificationGates = listOf(
                        "`HandoffResumePromptTest` 覆盖当前线程继续、新线程继续和目标保留。",
                        "`MessageReferencesTest` 覆盖 UI 参考 chip、文件和 URL 引用。",
                        "会话截图复刻后检查消息动作不会压住正文、代码块或引用行。",
                    ),
                )
            else ->
                ScreenshotSurfaceProfile(
                    observationChecklist = listOf(
                        "通用: 标出截图中的主容器、导航、操作区、状态区和可点击元素。",
                        "通用: 记录文本层级、间距、圆角、颜色角色、图标语义和响应式折叠。",
                    ),
                    implementationMoves = listOf(
                        "在目标 Compose 文件中优先补齐可见结构、状态和交互, 再处理视觉细节。",
                        "把新模式同步到 `/surfaces`、`/trace`、`/visual-check` 和 `/report`。",
                    ),
                    verificationGates = listOf(
                        "截图复刻后检查无文本溢出、控件重叠、状态遗漏或入口断链。",
                        "`testProotDebugUnitTest` 与 `assembleProotDebug` 通过。",
                    ),
                )
        }
    }

    fun workOrderFor(
        index: Int,
        reference: UiReferenceItem,
        state: ScreenshotExtractionState,
        command: String,
    ): ScreenshotReplicationWorkOrder {
        val surface = targetSurfaceFor(index, reference)
        val targetSurface = surface?.andmxSurface.orEmpty().ifBlank { "UiReferenceLedger / ScreenshotExtraction" }
        val surfaceProfile = surfaceProfileFor(targetSurface, reference)
        val commands = listOf(command, "/references", "/trace", "/visual-check", "/report").distinct()
        val baseObservation = listOf(
            "分区: 标出主工作区、导航/侧栏、输入区、状态区、弹层和工具区。",
            "控件: 逐项记录按钮、图标、输入框、标签、列表、计数和可点击入口。",
            "状态: 识别运行中、等待授权、失败、完成、注意、缺口和可交付表达。",
            "交互: 还原用户从截图入口到下一步命令、审批、Diff、验证或报告的路径。",
            "设计: 记录密度、间距、半径、文本层级、颜色角色和移动端折叠规则。",
        )
        val stateMoves = when (state) {
            ScreenshotExtractionState.WAITING_REFERENCE -> listOf(
                "保持附件导入、参考板、证据账本和 Inspector 指标随时可接收截图。",
                "收到截图后先生成逐图解析, 再决定具体 Compose 改动。",
            )
            ScreenshotExtractionState.NEEDS_EXTRACTION -> listOf(
                "按观察清单提取截图元素, 不先假设实现方案。",
                "把可见模式映射到目标 surface、状态模型和可复用组件。",
            )
            ScreenshotExtractionState.NEEDS_IMPLEMENTATION -> listOf(
                "在目标文件中实现布局、控件、状态和交互差异。",
                "把截图证据同步到 /references、/surfaces、/trace 和 /report。",
            )
            ScreenshotExtractionState.NEEDS_VERIFICATION -> listOf(
                "对照截图检查文本溢出、控件重叠、密度、状态色和命令入口。",
                "补齐 Kotlin 编译、单测、APK 或视觉验收记录。",
            )
            ScreenshotExtractionState.READY -> listOf(
                "保留截图到文件、变更和验证的映射, 作为后续复刻基线。",
                "后续新截图进入同一执行单链路增量比较。",
            )
        }
        val baseVerification = listOf(
            "`/references` 能看到该截图、类型和元数据。",
            "`/evidence` 能看到该截图的参考ID和本地资产路径。",
            "`/trace` 能看到目标 surface、目标文件、变更和验证。",
            "`/visual-check` 与 `/design-system` 没有未解释的等待项或缺口。",
            "`./gradlew compileProotDebugKotlin testProotDebugUnitTest --no-daemon` 通过。",
            "`./gradlew assembleProotDebug --no-daemon` 产出 APK。",
        )
        return ScreenshotReplicationWorkOrder(
            title = if (reference.image) "图 ${index + 1} 复刻执行单" else "附件 ${index + 1} 复刻执行单",
            referenceId = reference.referenceId,
            assetPath = reference.assetPath,
            targetSurface = targetSurface,
            targetFiles = filesFor(targetSurface, reference),
            observationChecklist = (baseObservation + surfaceProfile.observationChecklist).distinct(),
            implementationMoves = (stateMoves + surfaceProfile.implementationMoves).distinct(),
            verificationGates = (baseVerification + surfaceProfile.verificationGates).distinct(),
            commands = commands,
        )
    }

    val items = fallbackItems.mapIndexed { index, reference ->
        val state = stateFor(reference)
        val command = when (state) {
            ScreenshotExtractionState.WAITING_REFERENCE -> "/appshots"
            ScreenshotExtractionState.NEEDS_EXTRACTION -> "/blueprint"
            ScreenshotExtractionState.NEEDS_IMPLEMENTATION -> "/changes"
            ScreenshotExtractionState.NEEDS_VERIFICATION -> "/visual-check"
            ScreenshotExtractionState.READY -> "/report"
        }
        ScreenshotExtractionItem(
            label = if (hasReferences) "图 ${index + 1}: ${reference.label}" else reference.label,
            state = state,
            detail = when (state) {
                ScreenshotExtractionState.WAITING_REFERENCE -> "尚未收到截图证据; 当前先准备 Appshots/截图采集与解析框架。"
                ScreenshotExtractionState.NEEDS_EXTRACTION -> "已收到参考, 下一步逐项提取布局、控件、状态和交互。"
                ScreenshotExtractionState.NEEDS_IMPLEMENTATION -> "已完成解析准备, 需要把差异落到 Compose 和状态模型。"
                ScreenshotExtractionState.NEEDS_VERIFICATION -> "已有实现变更, 需要视觉验收、设计审计和构建/测试证据。"
                ScreenshotExtractionState.READY -> "截图、实现、视觉验收和交付证据已形成闭环。"
            },
            dimensions = dimensions,
            targetSurfaces = surfaceMap.surfaces.map { it.andmxSurface }.distinct().take(8),
            acceptance = listOf(
                "参考进入 /references、/evidence 和 Inspector 指标",
                "解析结果进入 /blueprint、/surfaces 和 /design-system",
                "实现变更进入 /changes、Diff 和交付报告",
                "验证结果进入 /verify、/visual-check 和 /report",
            ),
            command = command,
            workOrder = workOrderFor(index, reference, state, command),
        )
    }

    val firstOpen = items.firstOrNull { it.state != ScreenshotExtractionState.READY }
    val title = when (firstOpen?.state) {
        ScreenshotExtractionState.WAITING_REFERENCE -> "等待截图开始解析"
        ScreenshotExtractionState.NEEDS_EXTRACTION -> "等待提取截图内容"
        ScreenshotExtractionState.NEEDS_IMPLEMENTATION -> "等待实现截图差异"
        ScreenshotExtractionState.NEEDS_VERIFICATION -> "等待验证截图复刻"
        null -> "截图复刻解析已闭环"
        ScreenshotExtractionState.READY -> "截图复刻解析已闭环"
    }
    return ScreenshotExtractionSummary(
        title = title,
        referenceCount = references.attachmentCount,
        items = items,
        primaryCommand = firstOpen?.command ?: "/report",
    )
}

internal fun screenshotExtractionText(summary: ScreenshotExtractionSummary): String = buildString {
    appendLine("## 截图解析清单")
    appendLine("- 状态: **${summary.title}**")
    appendLine("- 参考: ${summary.referenceCount}")
    appendLine("- 已闭环: ${summary.readyCount}")
    appendLine("- 待处理: ${summary.waitingCount}")
    appendLine("- 建议入口: `${summary.primaryCommand}`")
    appendLine()
    summary.items.forEach { item ->
        appendLine("### ${item.label}")
        appendLine("- 状态: **${item.state.label}**")
        appendLine("- 说明: ${item.detail}")
        appendLine("- 入口: `${item.command}`")
        appendLine()
        appendLine("#### 解析维度")
        item.dimensions.forEach { dimension ->
            appendLine("- ${dimension.title}: ${dimension.detail}")
            appendLine("  - 输出: ${dimension.output}")
        }
        appendLine()
        appendLine("#### 落地区域")
        item.targetSurfaces.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("#### 验收")
        item.acceptance.forEach { appendLine("- $it") }
        appendLine()
        appendLine("#### 执行单")
        appendLine("- 标题: ${item.workOrder.title}")
        if (item.workOrder.referenceId.isNotBlank()) appendLine("- 参考ID: `${item.workOrder.referenceId}`")
        if (item.workOrder.assetPath.isNotBlank()) appendLine("- 本地资产: `${item.workOrder.assetPath}`")
        appendLine("- 目标表面: `${item.workOrder.targetSurface}`")
        appendLine("- 入口: ${item.workOrder.commands.joinToString(" ") { "`$it`" }}")
        appendLine()
        appendLine("##### 目标文件")
        item.workOrder.targetFiles.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("##### 观察清单")
        item.workOrder.observationChecklist.forEach { appendLine("- $it") }
        appendLine()
        appendLine("##### 实现动作")
        item.workOrder.implementationMoves.forEach { appendLine("- $it") }
        appendLine()
        appendLine("##### 验证门槛")
        item.workOrder.verificationGates.forEach { appendLine("- $it") }
        appendLine()
    }
}
