package com.andmx.ui.workbench

enum class CommandId {
    NEW_CHAT,
    SHOW_PROGRESS,
    SHOW_GOAL,
    STATUS,
    CONTEXT,
    PLAN,
    VERIFY,
    CHANGES,
    ACTIVITY,
    CHECKLIST,
    NEXT,
    EVIDENCE,
    REFERENCES,
    BLUEPRINT,
    POLICY,
    TOOLS,
    PARITY,
    REPORT,
    ARCHITECTURE,
    SURFACES,
    VISUAL_CHECK,
    DESIGN_SYSTEM,
    SCREENSHOT_EXTRACT,
    APPSHOTS,
    TRACE,
    SELF_MODEL,
    FLOW,
    METHOD,
    IMPROVE,
    INSTRUCTIONS,
    COMMANDS,
    HANDOFF,
    SET_FULL_ACCESS,
    SET_ASK_APPROVAL,
    SET_READ_ONLY,
    DIAG,
    EXPORT,
    SETTINGS,
    PLUGINS,
    TOGGLE_THEME,
    TOGGLE_WORK_PANE,
    TOGGLE_TERMINAL_DOCK,
    OPEN_FILES,
    OPEN_TERMINAL,
    OPEN_DIFF,
    OPEN_BROWSER,
}

data class CommandPaletteItem(
    val id: CommandId,
    val title: String,
    val subtitle: String,
    val keywords: List<String> = emptyList(),
)

sealed interface PaletteEntry {
    data class Command(val item: CommandPaletteItem) : PaletteEntry
    data class Conversation(val id: Long) : PaletteEntry
}

data class PaletteCommandSections(
    val recent: List<CommandPaletteItem>,
    val commands: List<CommandPaletteItem>,
)

internal val DefaultCommandPaletteItems = listOf(
    CommandPaletteItem(CommandId.NEW_CHAT, "新对话", "开启一个干净线程", listOf("new", "chat", "thread")),
    CommandPaletteItem(CommandId.SHOW_PROGRESS, "任务面板", "查看当前目标、工具调用和变更", listOf("progress", "status", "panel", "进度")),
    CommandPaletteItem(CommandId.SHOW_GOAL, "查看目标", "打开当前线程的目标状态, 或运行 /goal 管理持久目标", listOf("/goal", "/objective", "goal", "objective", "任务", "目标", "任务目标", "持久目标", "暂停目标", "恢复目标")),
    CommandPaletteItem(CommandId.STATUS, "会话状态", "运行 /status", listOf("/status", "model", "tokens", "状态")),
    CommandPaletteItem(CommandId.CONTEXT, "上下文快照", "运行 /context 查看预算与结构", listOf("/context", "/ctx", "/budget", "tokens", "budget", "上下文", "预算")),
    CommandPaletteItem(CommandId.PLAN, "任务计划", "运行 /plan 查看当前执行计划", listOf("/plan", "/todo", "plan", "todo", "计划", "任务")),
    CommandPaletteItem(CommandId.VERIFY, "验证摘要", "运行 /verify 查看测试、构建与诊断", listOf("/verify", "/checks", "verify", "checks", "test", "build", "验证", "测试", "构建")),
    CommandPaletteItem(CommandId.CHANGES, "变更摘要", "运行 /changes 查看待审 diff", listOf("/changes", "/diffs", "changes", "diff", "变更", "差异", "审查")),
    CommandPaletteItem(CommandId.ACTIVITY, "活动时间线", "运行 /activity 查看最近事件", listOf("/activity", "/log", "activity", "timeline", "log", "活动", "日志", "时间线")),
    CommandPaletteItem(CommandId.CHECKLIST, "会话清单", "运行 /checklist 审计完成状态", listOf("/checklist", "/audit", "checklist", "audit", "completion", "清单", "审计", "完成")),
    CommandPaletteItem(CommandId.NEXT, "下一步解释", "运行 /next 查看决策原因", listOf("/next", "/why", "next", "why", "decision", "reason", "下一步", "原因", "决策", "解释")),
    CommandPaletteItem(CommandId.EVIDENCE, "证据账本", "运行 /evidence 查看来源依据", listOf("/evidence", "/sources", "evidence", "sources", "proof", "证据", "来源", "依据", "引用")),
    CommandPaletteItem(CommandId.REFERENCES, "截图参考", "运行 /references 查看图片与 UI 参考", listOf("/references", "/refs", "/screens", "reference", "references", "screenshot", "screens", "截图", "图片", "界面", "参考")),
    CommandPaletteItem(CommandId.BLUEPRINT, "UI 复刻蓝图", "运行 /blueprint 把截图转成执行清单", listOf("/blueprint", "/replica", "blueprint", "replica", "ui", "蓝图", "复刻", "视觉", "实现")),
    CommandPaletteItem(CommandId.POLICY, "权限策略", "运行 /policy 查看工具与安全边界", listOf("/policy", "/permissions", "/safety", "policy", "permission", "permissions", "safety", "策略", "权限", "安全", "边界")),
    CommandPaletteItem(CommandId.TOOLS, "能力与工具", "运行 /tools 查看 agent 能力地图、工具边界和 MCP", listOf("/tools", "/capabilities", "capabilities", "tools", "tool map", "能力", "工具", "能力地图", "工具地图", "观察", "编辑", "执行", "插件", "mcp")),
    CommandPaletteItem(CommandId.PARITY, "Codex 对标", "运行 /parity 查看能力缺口审计", listOf("/parity", "/audit-codex", "codex", "parity", "benchmark", "gap", "对标", "审计", "缺口")),
    CommandPaletteItem(CommandId.REPORT, "交付报告", "运行 /report 汇总改动、证据和验证", listOf("/report", "/deliver", "report", "deliver", "delivery", "交付", "报告", "总结", "收束")),
    CommandPaletteItem(CommandId.ARCHITECTURE, "系统架构蓝图", "运行 /architecture 查看执行链路", listOf("/architecture", "/arch", "architecture", "arch", "system", "架构", "系统", "链路")),
    CommandPaletteItem(CommandId.SURFACES, "Codex UI 表面地图", "运行 /surfaces 查看可复刻界面表面", listOf("/surfaces", "/ui-map", "surface", "surfaces", "ui map", "表面", "界面地图", "控件")),
    CommandPaletteItem(CommandId.VISUAL_CHECK, "视觉验收清单", "运行 /visual-check 核查截图复刻", listOf("/visual-check", "/visual", "visual", "acceptance", "视觉", "验收", "核查", "还原")),
    CommandPaletteItem(CommandId.DESIGN_SYSTEM, "Codex 设计系统审计", "运行 /design-system 核查密度、布局和控件规范", listOf("/design-system", "/design", "design system", "design", "density", "设计系统", "设计", "密度", "卡片", "排版")),
    CommandPaletteItem(CommandId.SCREENSHOT_EXTRACT, "截图解析清单", "运行 /screenshot-extract 拆解布局、控件、状态和交互", listOf("/screenshot-extract", "/extract-ui", "/screenshots", "screenshot extraction", "extract ui", "截图解析", "图片解析", "界面提取", "提取")),
    CommandPaletteItem(CommandId.APPSHOTS, "Codex Appshots", "运行 /appshots 查看前台窗口采集与截图复刻链路", listOf("/appshots", "/appshot", "/capture-ui", "appshot", "appshots", "capture ui", "frontmost window", "视觉上下文", "窗口采集", "前台窗口", "双 command", "codex截图", "宿主截图")),
    CommandPaletteItem(CommandId.TRACE, "截图实现追踪", "运行 /trace 查看截图到文件、变更和验证的映射", listOf("/trace", "/implementation-trace", "/ui-trace", "trace", "implementation trace", "ui trace", "实现追踪", "截图实现", "映射")),
    CommandPaletteItem(CommandId.SELF_MODEL, "Codex 自我模型", "运行 /self-model 查看指令、工具、环境和工作循环", listOf("/self-model", "/self", "self model", "self", "introspection", "自我模型", "自省", "运行方式", "系统环境")),
    CommandPaletteItem(CommandId.FLOW, "Codex 交互流程", "运行 /flow 查看目标、工具、审批、验证和交付链路", listOf("/flow", "/interaction", "/loop", "flow", "interaction", "loop", "交互流程", "执行循环", "操作链路", "工作闭环")),
    CommandPaletteItem(CommandId.METHOD, "工作方法", "运行 /method 查看 agent 执行循环、工具环境和自我完善路径", listOf("/method", "/workflow", "/methodology", "methodology", "codex", "method", "workflow", "agent loop", "方法", "方法论", "工作流", "工作方式", "执行方法", "工具环境", "自我完善")),
    CommandPaletteItem(CommandId.IMPROVE, "自我完善路线图", "运行 /improve 查看 AndMX 自我改进队列、证据和下一入口", listOf("/improve", "/self-improve", "/kaizen", "improve", "self improve", "self improvement", "kaizen", "自我完善", "自我改进", "改进", "完善", "路线图", "闭环")),
    CommandPaletteItem(CommandId.INSTRUCTIONS, "指令栈", "运行 /instructions 查看可见配置、系统边界和环境契约", listOf("/instructions", "/config", "/prompt", "instructions", "instruction", "prompt", "config", "contract", "system prompt", "指令", "系统提示", "配置", "环境契约", "边界", "工具环境")),
    CommandPaletteItem(CommandId.COMMANDS, "命令与快捷键", "运行 /commands 查看 Codex 命令菜单、快捷键和深链地图", listOf("/commands", "/shortcuts", "/keyboard", "commands", "command menu", "shortcuts", "keyboard", "deep link", "命令", "命令菜单", "快捷键", "键盘", "深链")),
    CommandPaletteItem(CommandId.HANDOFF, "线程交接", "运行 /handoff 生成可恢复摘要", listOf("/handoff", "/summary", "/resume", "handoff", "summary", "交接", "摘要", "恢复")),
    CommandPaletteItem(CommandId.SET_FULL_ACCESS, "授权: 完全访问", "运行 /full, 工具自动执行", listOf("/full", "approval", "mode", "full", "完全访问")),
    CommandPaletteItem(CommandId.SET_ASK_APPROVAL, "授权: 按需", "运行 /ask, 写入/执行前询问", listOf("/ask", "approval", "mode", "prompt", "按需")),
    CommandPaletteItem(CommandId.SET_READ_ONLY, "授权: 只读", "运行 /readonly, 阻止写入/执行/网络", listOf("/readonly", "/read", "approval", "mode", "readonly", "只读")),
    CommandPaletteItem(CommandId.DIAG, "诊断环境", "运行 /diag 检查执行环境", listOf("/diag", "doctor", "probe", "环境")),
    CommandPaletteItem(CommandId.EXPORT, "导出 Markdown", "运行 /export 保存当前对话", listOf("/export", "markdown", "download", "导出")),
    CommandPaletteItem(CommandId.SETTINGS, "设置", "模型、密钥、MCP 与偏好", listOf("settings", "model", "key", "配置")),
    CommandPaletteItem(CommandId.PLUGINS, "插件", "查看内置工具和 MCP 工具", listOf("plugins", "tools", "mcp")),
    CommandPaletteItem(CommandId.TOGGLE_THEME, "切换主题", "在 system/light/dark 间切换", listOf("theme", "dark", "light", "主题")),
    CommandPaletteItem(CommandId.TOGGLE_WORK_PANE, "显示/隐藏工作区", "切换右侧 Files/Terminal/Diff/Browser", listOf("work", "pane", "sidebar", "工作区")),
    CommandPaletteItem(CommandId.TOGGLE_TERMINAL_DOCK, "显示/隐藏底部终端", "切换底部共享 shell", listOf("terminal", "dock", "shell", "终端")),
    CommandPaletteItem(CommandId.OPEN_FILES, "打开文件面板", "切到右侧 Files", listOf("files", "folder", "文件")),
    CommandPaletteItem(CommandId.OPEN_TERMINAL, "打开终端面板", "切到右侧 Terminal", listOf("terminal", "shell", "终端")),
    CommandPaletteItem(CommandId.OPEN_DIFF, "打开差异面板", "审查 agent 变更和 Git diff", listOf("diff", "changes", "review", "差异")),
    CommandPaletteItem(CommandId.OPEN_BROWSER, "打开浏览器面板", "切到右侧 Browser", listOf("browser", "web", "search", "浏览")),
)

internal fun filterCommands(
    query: String,
    commands: List<CommandPaletteItem> = DefaultCommandPaletteItems,
): List<CommandPaletteItem> {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return commands
    return commands.mapIndexedNotNull { index, command ->
        val haystack = buildString {
            append(command.title.lowercase())
            append(' ')
            append(command.subtitle.lowercase())
            append(' ')
            append(command.keywords.joinToString(" ").lowercase())
        }
        var score = 0
        for (token in tokens) {
            val tokenScore = when {
                command.title.lowercase().contains(token) -> 4
                command.keywords.any { it.lowercase().contains(token) } -> 3
                command.subtitle.lowercase().contains(token) -> 1
                haystack.contains(token) -> 1
                else -> return@mapIndexedNotNull null
            }
            score += tokenScore
        }
        Triple(score, index, command)
    }
        .sortedWith(compareByDescending<Triple<Int, Int, CommandPaletteItem>> { it.first }.thenBy { it.second })
        .map { it.third }
}

internal fun recentCommandItems(
    commandIds: List<CommandId>,
    commands: List<CommandPaletteItem> = DefaultCommandPaletteItems,
    limit: Int = 5,
): List<CommandPaletteItem> {
    val byId = commands.associateBy { it.id }
    return commandIds.distinct().mapNotNull { byId[it] }.take(limit)
}

internal fun updatedRecentCommands(command: CommandId, current: List<CommandId>, limit: Int = 5): List<CommandId> =
    (listOf(command) + current.filterNot { it == command }).take(limit)

internal fun paletteCommandSections(
    query: String,
    recentCommandIds: List<CommandId>,
    commands: List<CommandPaletteItem> = DefaultCommandPaletteItems,
    commandLimit: Int = 8,
): PaletteCommandSections {
    val recent = if (query.isBlank()) recentCommandItems(recentCommandIds, commands) else emptyList()
    val recentIds = recent.map { it.id }.toSet()
    val filtered = filterCommands(query, commands).filterNot { it.id in recentIds }.take(commandLimit)
    return PaletteCommandSections(recent = recent, commands = filtered)
}

internal fun paletteEntries(
    commands: List<CommandPaletteItem>,
    conversationIds: List<Long>,
): List<PaletteEntry> =
    commands.map { PaletteEntry.Command(it) } + conversationIds.map { PaletteEntry.Conversation(it) }

internal fun clampPaletteSelection(index: Int, size: Int): Int =
    if (size <= 0) 0 else index.coerceIn(0, size - 1)

internal fun movePaletteSelection(index: Int, size: Int, delta: Int): Int {
    if (size <= 0) return 0
    val next = (index + delta) % size
    return if (next < 0) next + size else next
}

internal fun isCommandPaletteShortcut(
    key: String,
    ctrl: Boolean,
    meta: Boolean,
    shift: Boolean,
): Boolean {
    if (!ctrl && !meta) return false
    val normalized = key.lowercase()
    return normalized == "k" || (shift && normalized == "p")
}
