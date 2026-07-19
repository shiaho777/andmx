package com.andmx.ui.workbench

internal enum class SurfaceReadiness(val label: String) {
    READY("已具备"),
    PARTIAL("待补齐"),
    WAITING_REFERENCE("等截图"),
}

internal data class CodexSurfaceSpec(
    val title: String,
    val readiness: SurfaceReadiness,
    val codexPattern: String,
    val andmxSurface: String,
    val states: List<String>,
    val interactions: List<String>,
    val acceptance: String,
    val command: String,
    val manualBasis: String = "",
)

internal data class CodexSurfaceMap(
    val title: String,
    val referenceCount: Int,
    val surfaces: List<CodexSurfaceSpec>,
    val primaryCommand: String,
) {
    val readyCount: Int get() = surfaces.count { it.readiness == SurfaceReadiness.READY }
    val partialCount: Int get() = surfaces.count { it.readiness == SurfaceReadiness.PARTIAL }
    val waitingCount: Int get() = surfaces.count { it.readiness == SurfaceReadiness.WAITING_REFERENCE }
}

internal fun buildCodexSurfaceMap(
    references: UiReferenceLedger,
    snapshot: AgentInspectorSnapshot,
    blueprint: UiReplicaBlueprint,
): CodexSurfaceMap {
    val hasReferences = references.attachmentCount > 0
    val hasChanges = snapshot.changedFiles > 0
    fun referenceAware(default: SurfaceReadiness): SurfaceReadiness =
        if (!hasReferences) SurfaceReadiness.WAITING_REFERENCE else default

    val surfaces = listOf(
        CodexSurfaceSpec(
            title = "线程与项目模式",
            readiness = if (snapshot.goalText.isBlank()) SurfaceReadiness.PARTIAL else SurfaceReadiness.READY,
            codexPattern = "Codex app 以项目为边界管理本地线程, 新线程可选择 Local、Worktree 或 Cloud 模式, 目标模式把长期任务固定在线程上。",
            andmxSurface = "Sidebar / WorkbenchScreen / ConversationController",
            states = listOf("项目", "本地线程", "目标运行中", "可暂停/恢复", "待交接"),
            interactions = listOf("新建线程", "切换项目", "设置目标", "查看计划", "生成 handoff"),
            acceptance = "线程目标、上下文、计划和交接能在 /status、/plan、/handoff 中互相恢复。",
            command = "/plan",
            manualBasis = "Codex app features: projects, Local/Worktree/Cloud modes, goal progress row.",
        ),
        CodexSurfaceSpec(
            title = "会话流",
            readiness = SurfaceReadiness.READY,
            codexPattern = "用户消息、assistant 回复、工具调用卡片和本地命令输出在同一时间线里展开。",
            andmxSurface = "MessageList / ChatPane",
            states = listOf("用户", "助手", "工具运行中", "工具失败", "本地命令"),
            interactions = listOf("复制/展开输出", "文件和 URL 引用可点击", "分支/重试保持线程上下文", "线程内查找"),
            acceptance = "消息、工具、审批和 slash 输出都能被 /activity 与 /evidence 追踪。",
            command = "/activity",
            manualBasis = "Codex app commands: thread search, find in thread, slash commands and goal management.",
        ),
        CodexSurfaceSpec(
            title = "输入区",
            readiness = SurfaceReadiness.READY,
            codexPattern = "底部 composer 承接文本、附件、slash 命令、技能调用和停止/重试控制。",
            andmxSurface = "Composer / Attachments",
            states = listOf("空输入", "附件摘要", "发送中", "可停止", "slash 建议", "技能建议"),
            interactions = listOf("发送目标", "添加截图", "运行本地命令", "停止当前轮", "输入 / 或 $ 选择命令/技能"),
            acceptance = "截图附件会进入 /references、/blueprint 和证据账本。",
            command = "/references",
            manualBasis = "Codex app commands: slash commands and explicit skill invocation from the composer.",
        ),
        CodexSurfaceSpec(
            title = "任务面板",
            readiness = SurfaceReadiness.READY,
            codexPattern = "目标、计划、工具、来源、变更、验证和交付状态集中在一个可恢复面板。",
            andmxSurface = "ProgressPopover / WorkbenchScreen",
            states = listOf("运行中", "待授权", "接近就绪", "阻塞", "可交付"),
            interactions = listOf("打开清单", "打开变更", "打开验证", "生成报告"),
            acceptance = "任务面板能从 /next、/checklist、/report 找到同一条收束路径。",
            command = "/report",
            manualBasis = "Codex app features: task sidebar surfaces plan, sources, generated artifacts and task summary.",
        ),
        CodexSurfaceSpec(
            title = "右侧 Inspector",
            readiness = SurfaceReadiness.READY,
            codexPattern = "自我状态面板展示模型、上下文、目标、工具、环境、指令和安全边界。",
            andmxSurface = "AgentInspectorPane",
            states = listOf("上下文压力", "目标阶段", "工具授权", "运行环境", "架构蓝图"),
            interactions = listOf("运行本地命令", "打开设置", "打开证据", "打开架构"),
            acceptance = "Inspector 内的关键区块均有 slash 入口可回到聊天时间线。",
            command = "/architecture",
            manualBasis = "Codex app settings and status commands expose model, context, permissions, MCP and environment state.",
        ),
        CodexSurfaceSpec(
            title = "工作区面板",
            readiness = SurfaceReadiness.READY,
            codexPattern = "Files、Terminal、Diff、Browser 共享同一个任务上下文。",
            andmxSurface = "WorkPane / FilePane / TerminalPane / DiffPane / BrowserPane",
            states = listOf("文件浏览", "终端会话", "待审 diff", "网页参考"),
            interactions = listOf("切换标签", "打开文件", "运行 shell", "审查变更"),
            acceptance = "文件、终端和 diff 的结果进入证据、变更摘要和交付报告。",
            command = "/changes",
            manualBasis = "Codex app features: built-in Git tools, diff pane, integrated terminal and in-app browser.",
        ),
        CodexSurfaceSpec(
            title = "Git 评审与交付",
            readiness = if (snapshot.changedFiles > 0) SurfaceReadiness.PARTIAL else SurfaceReadiness.READY,
            codexPattern = "Codex app 的 Diff 面板承接审查、内联反馈、分块处理、提交、推送和 PR 创建。",
            andmxSurface = "DiffPane / DeliveryReport / ChangeSummary",
            states = listOf("无变更", "待审", "已验证", "可报告", "需人工处理"),
            interactions = listOf("查看 diff", "按文件审查", "生成报告", "回到验证", "保留剩余风险"),
            acceptance = "每个待审文件都有变更摘要、验证证据和交付报告入口。",
            command = if (snapshot.changedFiles > 0) "/changes" else "/report",
            manualBasis = "Codex app features: diff pane, inline comments, staging/reverting chunks, commit/push/PR flows.",
        ),
        CodexSurfaceSpec(
            title = "命令面板",
            readiness = SurfaceReadiness.READY,
            codexPattern = "通过 Cmd+Shift+P 或 Cmd+K 统一触达命令菜单, 并支持设置、线程搜索、查找、侧栏、Diff 和终端切换。",
            andmxSurface = "SearchOverlay / CommandPalette",
            states = listOf("最近命令", "过滤结果", "键盘选中", "会话搜索", "线程内查找"),
            interactions = listOf("上下选择", "回车执行", "Esc 关闭", "中文/英文关键词搜索", "打开设置/技能/MCP"),
            acceptance = "所有 Codex 对标命令可通过 slash 与命令面板双入口触达。",
            command = "/help",
            manualBasis = "Codex app commands: command menu, settings, keyboard shortcuts, thread search and find in thread.",
        ),
        CodexSurfaceSpec(
            title = "审批与安全",
            readiness = SurfaceReadiness.READY,
            codexPattern = "高风险工具在当前授权模式下自动、询问或阻止，并显示可审计卡片。",
            andmxSurface = "Approval cards / ToolPolicySummary",
            states = listOf("自动", "询问", "阻止", "等待用户", "已允许/拒绝"),
            interactions = listOf("允许", "拒绝", "切换授权模式", "查看安全边界"),
            acceptance = "/policy 能解释每类工具和 Live UI 安全边界的处理方式。",
            command = "/policy",
            manualBasis = "Codex app features and Computer Use docs: approvals, sandboxing and scoped app permissions.",
        ),
        CodexSurfaceSpec(
            title = "浏览与桌面操作",
            readiness = SurfaceReadiness.PARTIAL,
            codexPattern = "Codex 用 in-app browser 预览和标注本地页面; Computer Use 只在允许的桌面应用中视觉操作, 不能自动化 Codex 自身。",
            andmxSurface = "BrowserPane / UiReferenceLedger / EvidenceLedger",
            states = listOf("浏览预览", "视觉评论", "截图参考", "受保护宿主", "用户接管"),
            interactions = listOf("打开 URL", "记录视觉证据", "截图转参考", "用 Browser 优先验证本地页面", "拒绝自动化 Codex 宿主"),
            acceptance = "无法直接操作宿主时, 截图资产、参考ID和证据链仍能推进复刻。",
            command = "/references",
            manualBasis = "In-app browser and Computer Use docs: browser comments, browser use, app permissions and Codex self-automation restriction.",
        ),
        CodexSurfaceSpec(
            title = "扩展",
            readiness = if (snapshot.mcpServers > 0) SurfaceReadiness.READY else SurfaceReadiness.PARTIAL,
            codexPattern = "Codex 通过 Skills、Plugins、MCP、AGENTS.md、rules 和 hooks 扩展长期工作方式。",
            andmxSurface = "PluginsOverlay / InstructionStackSummary / McpManager",
            states = listOf("技能可发现", "MCP 待连接", "项目指令", "生命周期约束"),
            interactions = listOf("打开工具", "检查 MCP", "查看指令栈", "把重复反馈固化"),
            acceptance = "可通过 /tools、/instructions、/self-model 看到扩展能力和缺口。",
            command = if (snapshot.mcpServers > 0) "/tools" else "/instructions",
            manualBasis = "Customization docs: AGENTS.md, skills, MCP, plugins, rules and hooks.",
        ),
        CodexSurfaceSpec(
            title = "截图复刻流水线",
            readiness = referenceAware(if (hasChanges || blueprint.state != BlueprintState.WAITING_REFERENCES) SurfaceReadiness.PARTIAL else SurfaceReadiness.WAITING_REFERENCE),
            codexPattern = "截图进入任务后, 先抽取布局、控件、状态和交互, 再映射到实现与验收。",
            andmxSurface = "UiReferenceLedger / UiReplicaBlueprint / CodexSurfaceMap",
            states = listOf("等待截图", "待提取", "实现中", "验证中"),
            interactions = listOf("查看截图参考", "生成 UI 蓝图", "审查 surface map", "运行验证"),
            acceptance = "每张截图都能落到具体 surface、Compose 文件和验收项。",
            command = if (hasReferences) "/blueprint" else "/references",
            manualBasis = "Appshots, in-app browser comments and screenshot-driven UI work feed the same evidence loop.",
        ),
    )

    val title = when {
        surfaces.any { it.readiness == SurfaceReadiness.WAITING_REFERENCE } -> "等待截图补全 UI 表面"
        surfaces.any { it.readiness == SurfaceReadiness.PARTIAL } -> "Codex UI 表面正在复刻"
        else -> "Codex UI 表面地图已成形"
    }
    val primary = surfaces.firstOrNull { it.readiness == SurfaceReadiness.WAITING_REFERENCE }?.command
        ?: surfaces.firstOrNull { it.readiness == SurfaceReadiness.PARTIAL }?.command
        ?: "/blueprint"
    return CodexSurfaceMap(
        title = title,
        referenceCount = references.attachmentCount,
        surfaces = surfaces,
        primaryCommand = primary,
    )
}

internal fun codexSurfaceMapText(map: CodexSurfaceMap): String = buildString {
    appendLine("## Codex UI 表面地图")
    appendLine("- 状态: **${map.title}**")
    appendLine("- 截图参考: ${map.referenceCount}")
    appendLine("- 已具备: ${map.readyCount}")
    appendLine("- 待补齐: ${map.partialCount}")
    appendLine("- 等截图: ${map.waitingCount}")
    appendLine("- 建议入口: `${map.primaryCommand}`")
    appendLine()
    map.surfaces.forEach { surface ->
        appendLine("### ${surface.title}")
        appendLine("- 状态: **${surface.readiness.label}**")
        appendLine("- Codex 模式: ${surface.codexPattern}")
        appendLine("- AndMX 落点: `${surface.andmxSurface}`")
        appendLine("- 状态集: ${surface.states.joinToString(" / ")}")
        appendLine("- 交互: ${surface.interactions.joinToString(" / ")}")
        appendLine("- 验收: ${surface.acceptance}")
        if (surface.manualBasis.isNotBlank()) appendLine("- 依据: ${surface.manualBasis}")
        appendLine("- 入口: `${surface.command}`")
        appendLine()
    }
}
