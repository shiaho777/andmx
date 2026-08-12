package com.andmx.ui2.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.andmx.agent.ToolArgs

/**
 * Resolves a tool call to its UI presentation. Single authority for the
 * ZCode-aligned status machine, family/icon, summary line and affordances —
 * so ToolCallCard only has to lay things out. Recognizes both wire surfaces
 * (PascalCase ZCode names and legacy snake_case) via [ToolArgs.canonical].
 */
object ToolPresentation {

    enum class Status { PENDING, RUNNING, COMPLETED, FAILED, DENIED, STOPPED }

    fun status(tc: ToolCall): Status = when {
        tc.isRunning -> if (tc.args.isBlank() && tc.output == null) Status.PENDING else Status.RUNNING
        tc.isError -> deniedOrStopped(tc.output) ?: Status.FAILED
        else -> Status.COMPLETED
    }

    private fun deniedOrStopped(output: String?): ToolPresentation.Status? {
        if (output.isNullOrBlank()) return null
        return when {
            output.contains("已被用户拒绝") || output.contains("已拒绝") -> Status.DENIED
            output.contains("已停止") || output.contains("已取消") -> Status.STOPPED
            else -> null
        }
    }

    data class Family(
        val label: String,
        val icon: ImageVector,
    )

    fun family(name: String): Family = when (ToolArgs.canonical(name)) {
        "shell" -> Family("终端", Icons.Outlined.Terminal)
        "git" -> Family("Git", Icons.Outlined.Terminal)
        "read", "list", "todoread" -> Family("读取", Icons.Outlined.Description)
        "write", "edit", "multiedit", "patch" -> Family("编辑", Icons.Outlined.Edit)
        "grep", "glob" -> Family("搜索", Icons.Outlined.Search)
        "webfetch", "search" -> Family("网络", Icons.Outlined.Language)
        "todo" -> Family("待办", Icons.AutoMirrored.Outlined.ListAlt)
        "skill" -> Family("技能", Icons.Outlined.AutoAwesome)
        "agent" -> Family("子代理", Icons.Outlined.Build)
        "taskoutput" -> Family("任务输出", Icons.Outlined.Build)
        "taskstop" -> Family("停止任务", Icons.Outlined.Build)
        "enterplan", "exitplan" -> Family("计划", Icons.AutoMirrored.Outlined.ListAlt)
        "ask" -> Family("询问", Icons.AutoMirrored.Outlined.HelpOutline)
        "sessionctx" -> Family("会话", Icons.Outlined.History)
        "goal" -> Family("目标", Icons.Outlined.Flag)
        "mcp" -> Family("MCP", Icons.Outlined.Code)
        else -> Family(displayName(name), Icons.Outlined.Build)
    }

    /** Short verb label reflecting current status, ZCode-aligned. */
    fun kindLabel(tc: ToolCall): String {
        val st = status(tc)
        if (st == Status.FAILED) return "失败"
        if (st == Status.DENIED) return "已拒绝"
        if (st == Status.STOPPED) return "已停止"
        val running = st == Status.RUNNING || st == Status.PENDING
        return when (ToolArgs.canonical(tc.name)) {
            "shell", "git" -> if (running) "执行中" else "已执行"
            "read" -> if (running) "读取中" else "已读取"
            "list" -> if (running) "浏览中" else "已浏览"
            "todoread" -> if (running) "读取待办" else "已读取"
            "write" -> if (running) "写入中" else "已写入"
            "edit", "multiedit", "patch" -> if (running) "编辑中" else "已编辑"
            "grep" -> if (running) "搜索中" else "已搜索"
            "glob" -> if (running) "匹配中" else "已匹配"
            "webfetch" -> if (running) "抓取中" else "已抓取"
            "search" -> if (running) "检索中" else "已检索"
            "todo" -> if (running) "更新待办" else "已更新待办"
            "skill" -> if (running) "运行技能" else "已运行技能"
            "agent" -> if (running) "子代理运行中" else "子代理完成"
            "taskoutput" -> if (running) "获取输出中" else "已获取输出"
            "taskstop" -> if (running) "停止中" else "已停止"
            "enterplan" -> if (running) "进入计划" else "已进入计划"
            "exitplan" -> if (running) "提交计划" else "计划已提交"
            "ask" -> if (running) "等待回答" else "已回答"
            "sessionctx" -> if (running) "读取上下文" else "已读取上下文"
            "goal" -> if (running) "更新目标" else "目标已更新"
            "mcp" -> if (running) "调用中" else "已调用"
            else -> if (running) "运行中" else family(tc.name).label
        }
    }

    fun summary(tc: ToolCall): String {
        val preview = ToolArgs.preview(tc.name, tc.args)
        return preview.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(160)
    }

    /** Right-aligned secondary hint when collapsed (e.g. output line count). */
    fun secondary(tc: ToolCall): String? {
        if (tc.isRunning) return null
        val out = tc.output ?: return null
        val lines = out.lineSequence().count { it.isNotBlank() }
        return when {
            tc.isError && out.isNotBlank() -> out.firstLine().take(40)
            lines <= 0 -> null
            lines == 1 -> "1 行"
            else -> "$lines 行"
        }
    }

    fun isEditTool(name: String): Boolean = ToolArgs.isEditTool(name)

    /** Collapsible by default; some tools stay single-line (read/search). */
    fun isCollapsible(tc: ToolCall): Boolean = when (ToolArgs.canonical(tc.name)) {
        "read", "list", "grep", "glob", "todoread", "ask" -> false
        else -> true
    }

    fun defaultExpanded(tc: ToolCall): Boolean {
        if (tc.isRunning || tc.isError) return true
        return isEditTool(tc.name)
    }

    /** Read-only fan-out tools group together when batched in sequence. */
    fun shouldGroup(name: String): Boolean = when (ToolArgs.canonical(name)) {
        "read", "list", "grep", "glob", "git", "goal", "todoread" -> true
        else -> false
    }

    sealed class Action(val label: String) {
        class OpenFile(val path: String) : Action("打开文件")
        data object OpenTerminal : Action("终端")
        class OpenUrl(val url: String) : Action("打开链接")
        class Copy(val text: String) : Action("复制")
    }

    fun actions(tc: ToolCall): List<Action> {
        val out = mutableListOf<Action>()
        when (ToolArgs.canonical(tc.name)) {
            "shell", "git" -> {
                out += Action.OpenTerminal
                ToolArgs.shellCommand(tc.name, tc.args).takeIf { it.isNotBlank() }?.let { out += Action.Copy(it) }
            }
            "read", "list", "write", "edit", "multiedit", "patch", "grep", "glob" -> {
                ToolArgs.filePath(tc.name, tc.args).takeIf { it.isNotBlank() }?.let { out += Action.OpenFile(it) }
            }
            "webfetch", "search" -> {
                ToolArgs.webUrl(tc.name, tc.args).takeIf { it.isNotBlank() }?.let { out += Action.OpenUrl(it) }
            }
            else -> Unit
        }
        if (tc.isError && !tc.output.isNullOrBlank()) out += Action.Copy(tc.output.take(4000))
        return out
    }

    private fun displayName(name: String): String =
        name.removePrefix("plugin_").replace('_', ' ').ifBlank { name }

    private fun String.firstLine(): String = lineSequence().firstOrNull().orEmpty().trim()
}
