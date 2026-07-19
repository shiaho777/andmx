package com.andmx.ui.workbench

import com.andmx.agent.ToolCapability
import com.andmx.agent.label

internal enum class ToolCapabilityDomain(val label: String) {
    OBSERVE("观察"),
    EDIT("编辑"),
    EXECUTE("执行"),
    BROWSE("浏览与截图"),
    GUI("桌面操作"),
    EXTEND("外部能力"),
    DELIVER("交付"),
}

internal data class ToolCapabilityDomainItem(
    val domain: ToolCapabilityDomain,
    val title: String,
    val detail: String,
    val tools: List<ToolCapability>,
    val command: String,
    val safety: String,
)

internal data class CodexToolCapabilityMap(
    val title: String,
    val items: List<ToolCapabilityDomainItem>,
    val mcpServerCount: Int,
    val primaryCommand: String,
) {
    val domainCount: Int get() = items.size
    val toolCount: Int get() = items.sumOf { it.tools.size }
    val emptyDomainCount: Int get() = items.count {
        it.tools.isEmpty() &&
            it.domain != ToolCapabilityDomain.EXTEND
    }
}

internal fun buildCodexToolCapabilityMap(
    tools: List<ToolCapability>,
    mcpServerCount: Int,
): CodexToolCapabilityMap {
    fun matching(vararg names: String): List<ToolCapability> =
        tools.filter { tool -> names.any { name -> tool.name.contains(name, ignoreCase = true) } }

    val observe = matching("read", "list", "git")
    val edit = matching("write", "edit", "patch")
    val execute = matching("shell", "run", "git")
    val browse = matching("browse", "web", "search")
    val gui = matching("computer", "browser")
    val extend = tools.filter { it.name.contains("mcp", ignoreCase = true) }
    val deliver = (observe + execute + edit).distinctBy { it.name }

    val items = listOf(
        ToolCapabilityDomainItem(
            domain = ToolCapabilityDomain.OBSERVE,
            title = "观察事实",
            detail = "读取项目结构、文件、目录、Git 状态和已有输出, 为计划与实现建立事实。",
            tools = observe.distinctBy { it.name },
            command = "/evidence",
            safety = "通常为读取风险, 可自动执行; 结果进入证据账本。",
        ),
        ToolCapabilityDomainItem(
            domain = ToolCapabilityDomain.EDIT,
            title = "修改工作区",
            detail = "通过写入、编辑和补丁把计划落到代码、配置或文档。",
            tools = edit.distinctBy { it.name },
            command = "/changes",
            safety = "写入风险会进入授权策略和 Diff 审查路径。",
        ),
        ToolCapabilityDomainItem(
            domain = ToolCapabilityDomain.EXECUTE,
            title = "运行验证",
            detail = "使用 shell、构建、测试、诊断和 Git 命令证明改动是否可交付。",
            tools = execute.distinctBy { it.name },
            command = "/verify",
            safety = "执行风险按 `/full`、`/ask`、`/readonly` 决定自动、询问或阻止。",
        ),
        ToolCapabilityDomainItem(
            domain = ToolCapabilityDomain.BROWSE,
            title = "浏览与视觉参考",
            detail = "访问网页、搜索资料、记录截图参考, 或在受限宿主场景下用用户截图继续复刻。",
            tools = browse.distinctBy { it.name },
            command = "/references",
            safety = "in-app browser 适合本地页面预览和标注; 网络与页面内容按不可信上下文处理。",
        ),
        ToolCapabilityDomainItem(
            domain = ToolCapabilityDomain.GUI,
            title = "桌面 GUI 操作",
            detail = "通过 Browser/Computer Use 类能力检查真实界面、桌面应用、模拟器或 GUI-only bug。",
            tools = gui.distinctBy { it.name },
            command = "/references",
            safety = "Computer Use 需要显式 app 权限; 不能自动化 Codex 自身, 受保护窗口使用截图证据替代。",
        ),
        ToolCapabilityDomainItem(
            domain = ToolCapabilityDomain.EXTEND,
            title = "MCP 与插件扩展",
            detail = "通过 Skills、Plugins 和 MCP 接入外部工具服务器、知识库、浏览器、Figma 或专用系统能力。",
            tools = extend.distinctBy { it.name },
            command = "/tools",
            safety = if (mcpServerCount > 0) "$mcpServerCount 个 MCP 服务器已连接。" else "MCP 需要在设置中显式配置后才能使用。",
        ),
        ToolCapabilityDomainItem(
            domain = ToolCapabilityDomain.DELIVER,
            title = "交付闭环",
            detail = "把观察、修改、验证、证据和剩余风险汇总成报告或交接摘要。",
            tools = deliver,
            command = "/report",
            safety = "交付结论必须由文件、工具输出、截图、验证或报告证据支撑。",
        ),
    )
    val firstEmpty = items.firstOrNull {
        it.tools.isEmpty() &&
            it.domain != ToolCapabilityDomain.EXTEND
    }
    return CodexToolCapabilityMap(
        title = when {
            firstEmpty != null -> "Codex 工具能力地图仍有缺口"
            mcpServerCount == 0 -> "Codex 工具能力地图可用, MCP 待扩展"
            else -> "Codex 工具能力地图已成形"
        },
        items = items,
        mcpServerCount = mcpServerCount,
        primaryCommand = firstEmpty?.command ?: "/tools",
    )
}

internal fun codexToolCapabilityMapText(map: CodexToolCapabilityMap): String = buildString {
    appendLine("## 工具能力地图")
    appendLine("- 状态: **${map.title}**")
    appendLine("- 能力域: ${map.domainCount}")
    appendLine("- 工具: ${map.toolCount}")
    appendLine("- MCP: ${map.mcpServerCount}")
    appendLine("- 建议入口: `${map.primaryCommand}`")
    appendLine()
    map.items.forEach { item ->
        appendLine("### ${item.domain.label} · ${item.title}")
        appendLine("- 说明: ${item.detail}")
        appendLine("- 安全: ${item.safety}")
        appendLine("- 入口: `${item.command}`")
        if (item.tools.isEmpty()) {
            appendLine("- 工具: 暂无")
        } else {
            appendLine("- 工具: ${item.tools.joinToString("、") { "`${it.name}`" }}")
            appendLine("- 风险: ${item.tools.map { it.risk.label }.distinct().joinToString("、")}")
        }
        appendLine()
    }
}

internal fun CodexToolCapabilityMap.summaryLines(): List<String> = listOf(
    "状态: $title",
    "能力域: $domainCount · 工具: $toolCount · MCP: $mcpServerCount",
    "入口: `$primaryCommand`",
)
