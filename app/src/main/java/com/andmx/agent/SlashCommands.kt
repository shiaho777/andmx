package com.andmx.agent

sealed interface SlashResult {
    data object NotCommand : SlashResult
    data object Clear : SlashResult
    data object Help : SlashResult
    data object Status : SlashResult
    data object Compact : SlashResult
    data object Checkpoint : SlashResult
    data object Regenerate : SlashResult
    data object Stop : SlashResult
    data object Tools : SlashResult
    data object Handoff : SlashResult
    data object Export : SlashResult
    data object OpenModel : SlashResult
    data class Mode(val mode: ApprovalMode) : SlashResult
    data class Goal(val action: GoalAction, val text: String = "") : SlashResult
    data class Unknown(val name: String) : SlashResult
    data class PluginSlash(val name: String, val args: String) : SlashResult
}

enum class GoalAction { SHOW, SET, EDIT, PAUSE, RESUME, CLEAR }

object SlashCommands {

    data class Spec(
        val name: String,
        val desc: String,
        val aliases: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
    )

    val list = listOf(
        Spec("/clear", "开始新对话", aliases = listOf("/new"), keywords = listOf("新建", "清空", "reset")),
        Spec("/compact", "压缩当前上下文", aliases = listOf("/compress", "/summarize"), keywords = listOf("压缩", "摘要")),
        Spec("/goal", "查看或设置会话目标", aliases = listOf("/target", "/objective"), keywords = listOf("目标")),
        Spec("/stop", "停止当前任务", aliases = listOf("/cancel"), keywords = listOf("停止", "取消")),
        Spec("/model", "打开模型设置", aliases = listOf("/settings"), keywords = listOf("模型", "设置")),
        Spec("/status", "显示会话状态", keywords = listOf("状态")),
        Spec("/help", "显示可用命令", aliases = listOf("/?"), keywords = listOf("帮助")),
        Spec("/full", "授权:完全访问", keywords = listOf("full access", "完全访问")),
        Spec("/ask", "授权:按需批准", keywords = listOf("approval", "批准")),
        Spec("/readonly", "授权:只读", aliases = listOf("/read"), keywords = listOf("只读", "sandbox")),
        Spec("/tools", "显示工具能力", keywords = listOf("工具")),
        Spec("/handoff", "生成交接摘要", aliases = listOf("/summary"), keywords = listOf("交接")),
        Spec("/export", "导出当前对话", keywords = listOf("导出")),
        Spec("/regen", "重新生成上一轮回复", aliases = listOf("/retry", "/regenerate"), keywords = listOf("重试")),
        Spec("/checkpoint", "生成交接检查点", aliases = listOf("/handoff-checkpoint"), keywords = listOf("检查点")),
    )

    fun suggestions(
        query: String,
        limit: Int = 12,
        extras: List<Spec> = emptyList(),
    ): List<Spec> {
        val trimmed = query.trimStart()
        if (!trimmed.startsWith("/")) return emptyList()
        val rawQuery = trimmed.substringBefore(' ')
        val q = rawQuery.lowercase()
        val needle = q.removePrefix("/")
        val pool = list + extras
        if (needle.isBlank()) return pool.take(limit)
        return pool.mapIndexedNotNull { index, spec ->
            spec.matchScore(q, needle)?.let { score -> Triple(spec, score, index) }
        }
            .sortedWith(compareBy<Triple<Spec, Int, Int>> { it.second }.thenBy { it.third })
            .take(limit)
            .map { it.first }
    }

    fun complete(spec: Spec): String = "${spec.name} "

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
            "/compact", "/compress", "/summarize" -> SlashResult.Compact
            "/checkpoint", "/handoff-checkpoint" -> SlashResult.Checkpoint
            "/regen", "/retry", "/regenerate" -> SlashResult.Regenerate
            "/stop", "/cancel" -> SlashResult.Stop
            "/full" -> SlashResult.Mode(ApprovalMode.FULL)
            "/ask" -> SlashResult.Mode(ApprovalMode.ASK)
            "/readonly", "/read" -> SlashResult.Mode(ApprovalMode.READ_ONLY)
            "/model", "/settings" -> SlashResult.OpenModel
            "/status" -> SlashResult.Status
            "/tools", "/capabilities" -> SlashResult.Tools
            "/goal", "/target", "/objective" -> parseGoal(t)
            "/handoff", "/summary" -> SlashResult.Handoff
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
            "edit", "change", "修改", "编辑" -> {
                val rest = args.substringAfter(' ').trim()
                SlashResult.Goal(GoalAction.EDIT, rest.ifBlank { args })
            }
            "show", "status", "view", "查看", "状态" -> SlashResult.Goal(GoalAction.SHOW)
            else -> SlashResult.Goal(GoalAction.SET, args)
        }
    }
}
