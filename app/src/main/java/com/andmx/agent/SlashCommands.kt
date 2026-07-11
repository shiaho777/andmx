package com.andmx.agent

/** Result of interpreting a composer input as a slash command. */
sealed interface SlashResult {
    data object NotCommand : SlashResult
    data object Clear : SlashResult
    data object Help : SlashResult
    data object Status : SlashResult
    data object Context : SlashResult
    data object Plan : SlashResult
    data object Verify : SlashResult
    data object Changes : SlashResult
    data object Activity : SlashResult
    data object Checklist : SlashResult
    data object Next : SlashResult
    data object Evidence : SlashResult
    data object References : SlashResult
    data object Blueprint : SlashResult
    data object Policy : SlashResult
    data object Tools : SlashResult
    data object Parity : SlashResult
    data object Report : SlashResult
    data object Architecture : SlashResult
    data object Surfaces : SlashResult
    data object VisualCheck : SlashResult
    data object DesignSystem : SlashResult
    data object ScreenshotExtract : SlashResult
    data object Appshots : SlashResult
    data object Trace : SlashResult
    data object SelfModel : SlashResult
    data object Flow : SlashResult
    data object Method : SlashResult
    data object Improve : SlashResult
    data object Instructions : SlashResult
    data object Commands : SlashResult
    data class Goal(val action: GoalAction, val text: String = "") : SlashResult
    data object Handoff : SlashResult
    data object Diag : SlashResult
    data object Export : SlashResult
    data object OpenModel : SlashResult
    data class Mode(val mode: ApprovalMode) : SlashResult
    data class Unknown(val name: String) : SlashResult
}

enum class GoalAction { SHOW, SET, EDIT, PAUSE, RESUME, CLEAR }

/** Codex-style `/` commands handled client-side (no LLM round-trip). */
object SlashCommands {

    data class Spec(
        val name: String,
        val desc: String,
        val aliases: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
    )

    val list = listOf(
        Spec("/clear", "开始新对话", aliases = listOf("/new"), keywords = listOf("新建", "清空", "reset")),
        Spec("/full", "授权:完全访问", keywords = listOf("full access", "完全访问", "危险命令")),
        Spec("/ask", "授权:按需批准", keywords = listOf("approval", "批准", "确认")),
        Spec("/readonly", "授权:只读", aliases = listOf("/read"), keywords = listOf("只读", "安全", "sandbox")),
        Spec("/model", "打开模型设置", aliases = listOf("/settings"), keywords = listOf("模型", "设置", "配置")),
        Spec("/status", "显示会话状态", keywords = listOf("状态", "进度", "busy")),
        Spec("/context", "显示上下文快照", aliases = listOf("/ctx", "/budget"), keywords = listOf("上下文", "token", "预算")),
        Spec("/plan", "显示当前任务计划", aliases = listOf("/todo"), keywords = listOf("计划", "待办", "checklist")),
        Spec("/verify", "显示测试、构建与诊断摘要", aliases = listOf("/checks"), keywords = listOf("验证", "测试", "构建", "build", "test")),
        Spec("/changes", "显示待审变更摘要", aliases = listOf("/diffs"), keywords = listOf("变更", "差异", "审查", "diff", "changes")),
        Spec("/activity", "显示最近活动时间线", aliases = listOf("/log"), keywords = listOf("活动", "日志", "时间线", "timeline", "activity")),
        Spec("/checklist", "显示会话完成清单", aliases = listOf("/audit"), keywords = listOf("清单", "审计", "完成", "audit", "checklist", "completion")),
        Spec("/next", "解释下一步决策", aliases = listOf("/why"), keywords = listOf("下一步", "原因", "决策", "解释", "next", "why", "decision")),
        Spec("/evidence", "显示证据账本", aliases = listOf("/sources"), keywords = listOf("证据", "来源", "依据", "引用", "evidence", "sources", "proof")),
        Spec("/references", "显示截图与附件参考", aliases = listOf("/refs", "/screens"), keywords = listOf("截图", "图片", "界面", "参考", "reference", "references", "screenshot", "screens")),
        Spec("/blueprint", "生成 UI 复刻蓝图", aliases = listOf("/replica"), keywords = listOf("蓝图", "复刻", "视觉", "实现", "blueprint", "replica", "ui")),
        Spec("/policy", "显示工具授权策略", aliases = listOf("/permissions", "/safety"), keywords = listOf("策略", "权限", "边界", "安全", "policy", "permission", "permissions", "safety")),
        Spec("/tools", "显示 agent 能力与工具地图", aliases = listOf("/capabilities"), keywords = listOf("工具", "能力", "工具地图", "能力地图", "观察", "编辑", "执行", "插件", "mcp", "capabilities", "tools")),
        Spec("/parity", "显示 Codex 对标审计", aliases = listOf("/audit-codex"), keywords = listOf("对标", "审计", "缺口", "codex", "parity", "gap", "benchmark")),
        Spec("/report", "生成交付报告", aliases = listOf("/deliver"), keywords = listOf("交付", "报告", "总结", "收束", "report", "deliver", "delivery")),
        Spec("/architecture", "显示系统架构蓝图", aliases = listOf("/arch"), keywords = listOf("架构", "蓝图", "执行链路", "系统", "architecture", "arch", "system")),
        Spec("/surfaces", "显示 Codex UI 表面地图", aliases = listOf("/ui-map"), keywords = listOf("表面", "界面地图", "控件", "surface", "surfaces", "ui map")),
        Spec("/visual-check", "显示视觉验收清单", aliases = listOf("/visual"), keywords = listOf("视觉", "验收", "核查", "还原", "visual", "acceptance")),
        Spec("/design-system", "显示 Codex 设计系统审计", aliases = listOf("/design"), keywords = listOf("设计系统", "设计", "密度", "卡片", "排版", "design system", "design", "density")),
        Spec("/screenshot-extract", "显示截图解析清单", aliases = listOf("/extract-ui", "/screenshots"), keywords = listOf("截图解析", "提取", "图片解析", "界面提取", "screenshot extraction", "extract ui")),
        Spec("/appshots", "显示 Codex Appshots 视觉上下文采集单", aliases = listOf("/appshot", "/capture-ui"), keywords = listOf("appshot", "appshots", "视觉上下文", "窗口采集", "前台窗口", "双 command", "codex截图", "宿主截图", "capture ui")),
        Spec("/trace", "显示截图实现追踪", aliases = listOf("/implementation-trace", "/ui-trace"), keywords = listOf("实现追踪", "截图实现", "映射", "trace", "implementation trace", "ui trace")),
        Spec("/self-model", "显示 Codex 自我模型", aliases = listOf("/self"), keywords = listOf("自我模型", "自省", "运行方式", "系统环境", "self model", "self", "introspection")),
        Spec("/flow", "显示 Codex 交互流程", aliases = listOf("/interaction", "/loop"), keywords = listOf("交互流程", "执行循环", "操作链路", "工作闭环", "flow", "interaction", "loop")),
        Spec("/method", "显示 agent 工作方法", aliases = listOf("/workflow", "/methodology"), keywords = listOf("方法", "方法论", "流程", "工作方式", "执行方法", "工具环境", "method", "workflow", "agent loop")),
        Spec("/improve", "显示 AndMX 自我完善路线图", aliases = listOf("/self-improve", "/kaizen"), keywords = listOf("自我完善", "自我改进", "改进", "完善", "路线图", "闭环", "improve", "self improve", "self improvement", "kaizen")),
        Spec("/instructions", "显示可见指令栈与环境契约", aliases = listOf("/config", "/prompt"), keywords = listOf("指令", "系统提示", "配置", "环境契约", "边界", "工具环境", "instruction", "contract", "prompt")),
        Spec("/commands", "显示 Codex 命令、快捷键和深链地图", aliases = listOf("/shortcuts", "/keyboard"), keywords = listOf("命令", "快捷键", "键盘", "深链", "命令菜单", "command", "commands", "shortcut", "shortcuts", "keyboard", "deep link")),
        Spec("/goal", "查看或设置当前线程持久目标", aliases = listOf("/objective"), keywords = listOf("目标", "任务目标", "持久目标", "暂停", "恢复", "清除", "goal", "objective", "pause", "resume", "clear")),
        Spec("/handoff", "生成线程交接摘要", aliases = listOf("/summary", "/resume"), keywords = listOf("摘要", "交接", "继续")),
        Spec("/diag", "运行执行环境诊断", keywords = listOf("诊断", "环境", "debug")),
        Spec("/export", "导出对话为 Markdown 文件", keywords = listOf("导出", "markdown", "保存")),
        Spec("/help", "列出命令", aliases = listOf("/?"), keywords = listOf("帮助", "命令", "说明")),
    )

    fun suggestions(text: String, limit: Int = 8): List<Spec> {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return emptyList()
        val rawQuery = trimmed.substringBefore(' ')
        val query = rawQuery.lowercase()
        val needle = query.removePrefix("/")
        if (needle.isBlank()) return list.take(limit)

        return list.mapIndexedNotNull { index, spec ->
            spec.matchScore(query, needle)?.let { score -> ScoredSpec(spec, score, index) }
        }
            .sortedWith(compareBy<ScoredSpec> { it.score }.thenBy { it.index })
            .take(limit)
            .map { it.spec }
    }

    fun complete(spec: Spec): String = "${spec.name} "

    private data class ScoredSpec(val spec: Spec, val score: Int, val index: Int)

    private fun Spec.matchScore(query: String, needle: String): Int? {
        val aliasesLower = aliases.map { it.lowercase() }
        val nameLower = name.lowercase()
        val searchable = listOf(name, desc) + aliases + keywords
        return when {
            nameLower == query -> 0
            aliasesLower.any { it == query } -> 1
            nameLower.startsWith(query) -> 2
            aliasesLower.any { it.startsWith(query) } -> 3
            searchable.any { it.lowercase().contains(needle) } -> 4
            else -> null
        }
    }

    fun parse(text: String): SlashResult {
        val t = text.trim()
        if (!t.startsWith("/")) return SlashResult.NotCommand
        return when (t.substringBefore(' ').lowercase()) {
            "/clear", "/new" -> SlashResult.Clear
            "/full" -> SlashResult.Mode(ApprovalMode.FULL)
            "/ask" -> SlashResult.Mode(ApprovalMode.ASK)
            "/readonly", "/read" -> SlashResult.Mode(ApprovalMode.READ_ONLY)
            "/model", "/settings" -> SlashResult.OpenModel
            "/status" -> SlashResult.Status
            "/context", "/ctx", "/budget" -> SlashResult.Context
            "/plan", "/todo" -> SlashResult.Plan
            "/verify", "/checks" -> SlashResult.Verify
            "/changes", "/diffs" -> SlashResult.Changes
            "/activity", "/log" -> SlashResult.Activity
            "/checklist", "/audit" -> SlashResult.Checklist
            "/next", "/why" -> SlashResult.Next
            "/evidence", "/sources" -> SlashResult.Evidence
            "/references", "/refs", "/screens" -> SlashResult.References
            "/blueprint", "/replica" -> SlashResult.Blueprint
            "/policy", "/permissions", "/safety" -> SlashResult.Policy
            "/tools", "/capabilities" -> SlashResult.Tools
            "/parity", "/audit-codex" -> SlashResult.Parity
            "/report", "/deliver" -> SlashResult.Report
            "/architecture", "/arch" -> SlashResult.Architecture
            "/surfaces", "/ui-map" -> SlashResult.Surfaces
            "/visual-check", "/visual" -> SlashResult.VisualCheck
            "/design-system", "/design" -> SlashResult.DesignSystem
            "/screenshot-extract", "/extract-ui", "/screenshots" -> SlashResult.ScreenshotExtract
            "/appshots", "/appshot", "/capture-ui" -> SlashResult.Appshots
            "/trace", "/implementation-trace", "/ui-trace" -> SlashResult.Trace
            "/self-model", "/self" -> SlashResult.SelfModel
            "/flow", "/interaction", "/loop" -> SlashResult.Flow
            "/method", "/workflow", "/methodology" -> SlashResult.Method
            "/improve", "/self-improve", "/kaizen" -> SlashResult.Improve
            "/instructions", "/config", "/prompt" -> SlashResult.Instructions
            "/commands", "/shortcuts", "/keyboard" -> SlashResult.Commands
            "/goal", "/objective" -> parseGoal(t)
            "/handoff", "/summary", "/resume" -> SlashResult.Handoff
            "/diag" -> SlashResult.Diag
            "/export" -> SlashResult.Export
            "/help", "/?" -> SlashResult.Help
            else -> SlashResult.Unknown(t.substringBefore(' '))
        }
    }

    private fun parseGoal(text: String): SlashResult.Goal {
        val args = text.substringAfter(' ', missingDelimiterValue = "").trim()
        if (args.isBlank()) return SlashResult.Goal(GoalAction.SHOW)
        val first = args.substringBefore(' ').lowercase()
        return when (first) {
            "pause", "paused", "stop", "暂停" -> SlashResult.Goal(GoalAction.PAUSE)
            "resume", "continue", "run", "恢复", "继续" -> SlashResult.Goal(GoalAction.RESUME)
            "clear", "delete", "reset", "清除", "删除", "重置" -> SlashResult.Goal(GoalAction.CLEAR)
            "edit", "change", "修改", "编辑" -> SlashResult.Goal(GoalAction.EDIT)
            "show", "status", "view", "查看", "状态" -> SlashResult.Goal(GoalAction.SHOW)
            else -> SlashResult.Goal(GoalAction.SET, args)
        }
    }
}
