package com.andmx.ui.workbench

import com.andmx.agent.SlashCommands

internal data class CommandReferenceItem(
    val title: String,
    val detail: String,
    val shortcut: String = "",
    val command: String = "",
    val deepLink: String = "",
    val keywords: List<String> = emptyList(),
)

internal data class CommandReferenceSection(
    val title: String,
    val detail: String,
    val items: List<CommandReferenceItem>,
)

internal data class CodexCommandReference(
    val title: String,
    val sections: List<CommandReferenceSection>,
    val primaryCommand: String,
) {
    val itemCount: Int get() = sections.sumOf { it.items.size }
    val shortcutCount: Int get() = sections.sumOf { section -> section.items.count { it.shortcut.isNotBlank() } }
    val slashCount: Int get() = sections.sumOf { section -> section.items.count { it.command.startsWith("/") } }
    val deepLinkCount: Int get() = sections.sumOf { section -> section.items.count { it.deepLink.startsWith("codex://") } }
}

internal fun buildCodexCommandReference(
    commands: List<CommandPaletteItem> = DefaultCommandPaletteItems,
    slashCommands: List<SlashCommands.Spec> = SlashCommands.list,
): CodexCommandReference {
    val byId = commands.associateBy { it.id }
    fun item(
        id: CommandId,
        shortcut: String = "",
        command: String = "",
        deepLink: String = "",
        detailOverride: String = "",
    ): CommandReferenceItem {
        val palette = byId.getValue(id)
        return CommandReferenceItem(
            title = palette.title,
            detail = detailOverride.ifBlank { palette.subtitle },
            shortcut = shortcut,
            command = command,
            deepLink = deepLink,
            keywords = palette.keywords,
        )
    }

    val slashItems = slashCommands
        .filterNot { it.name in setOf("/clear", "/full", "/ask", "/readonly", "/help") }
        .map {
            CommandReferenceItem(
                title = it.name,
                detail = it.desc,
                command = it.name,
                keywords = it.aliases + it.keywords,
            )
        }

    val sections = listOf(
        CommandReferenceSection(
            title = "全局快捷",
            detail = "对齐 Codex app 的命令菜单、线程搜索、线程内查找、设置、侧栏、Diff 和终端切换。",
            items = listOf(
                item(CommandId.NEW_CHAT, shortcut = "Cmd/Ctrl+N", deepLink = "codex://threads/new"),
                item(CommandId.SETTINGS, shortcut = "Cmd/Ctrl+,", deepLink = "codex://settings"),
                item(CommandId.TOGGLE_WORK_PANE, shortcut = "Cmd/Ctrl+B"),
                item(CommandId.OPEN_DIFF, shortcut = "Cmd/Ctrl+Alt+B"),
                item(CommandId.OPEN_TERMINAL, shortcut = "Cmd/Ctrl+J"),
                item(CommandId.ACTIVITY, shortcut = "Cmd/Ctrl+G", command = "/activity", detailOverride = "线程搜索和活动时间线入口"),
                item(CommandId.FLOW, shortcut = "Cmd/Ctrl+F", command = "/flow", detailOverride = "当前线程内查找/流程定位入口"),
            ),
        ),
        CommandReferenceSection(
            title = "命令发现",
            detail = "Composer 输入 / 运行本地命令; 输入 $ 代表显式技能调用的产品模型。",
            items = listOf(
                item(CommandId.STATUS, shortcut = "Cmd/Ctrl+K", command = "/status"),
                item(CommandId.SHOW_GOAL, command = "/goal", detailOverride = "查看、设置、暂停、恢复或清除当前线程的持久目标"),
                item(CommandId.PLAN, command = "/plan"),
                item(CommandId.SELF_MODEL, command = "/self-model"),
                item(CommandId.IMPROVE, command = "/improve"),
                item(CommandId.SURFACES, command = "/surfaces"),
                item(CommandId.APPSHOTS, command = "/appshots"),
                item(CommandId.TOOLS, command = "/tools"),
                CommandReferenceItem(
                    title = "技能调用",
                    detail = "在 composer 输入 `${'$'}skill` 显式调用技能; 技能也可按描述隐式触发。",
                    command = "${'$'}skill",
                    deepLink = "codex://skills",
                    keywords = listOf("skills", "skill", "技能", "插件"),
                ),
            ) + slashItems.take(10),
        ),
        CommandReferenceSection(
            title = "工作台动作",
            detail = "把 Codex app 的任务侧栏、Git/Diff、终端、Browser、设置和扩展入口映射到移动工作台。",
            items = listOf(
                item(CommandId.SHOW_PROGRESS, command = "/checklist"),
                item(CommandId.CHANGES, command = "/changes"),
                item(CommandId.VERIFY, command = "/verify"),
                item(CommandId.OPEN_FILES),
                item(CommandId.OPEN_TERMINAL),
                item(CommandId.OPEN_DIFF),
                item(CommandId.OPEN_BROWSER),
                item(CommandId.PLUGINS, deepLink = "codex://plugins/openai-developers@openai-curated"),
                item(CommandId.AUTOMATIONS, deepLink = "codex://automations"),
            ),
        ),
        CommandReferenceSection(
            title = "深链模型",
            detail = "AndMX 不是直接打开 codex://, 但用同一信息架构标记可跳转的产品目的地。",
            items = listOf(
                CommandReferenceItem("新线程", "创建新本地线程并可预填 prompt/path", deepLink = "codex://threads/new"),
                CommandReferenceItem("设置", "打开设置面板", deepLink = "codex://settings"),
                CommandReferenceItem("技能", "打开 Skills", deepLink = "codex://skills"),
                CommandReferenceItem("自动化", "打开 Automations 创建流程", deepLink = "codex://automations"),
                CommandReferenceItem("Computer Use", "打开 Chrome/Computer Use 设置", deepLink = "codex://settings/computer-use/google-chrome"),
            ),
        ),
        CommandReferenceSection(
            title = "安全备注",
            detail = "命令入口必须保留 Codex 的授权、沙箱和 Live UI 边界。",
            items = listOf(
                item(CommandId.POLICY, command = "/policy"),
                item(CommandId.INSTRUCTIONS, command = "/instructions"),
                item(CommandId.APPSHOTS, command = "/appshots", detailOverride = "把 Codex Appshots、截图和受保护宿主边界整理成视觉上下文采集单"),
                item(CommandId.REFERENCES, command = "/references", detailOverride = "受保护宿主无法直接操作时, 使用截图资产和参考ID继续复刻"),
                CommandReferenceItem(
                    title = "Computer Use 限制",
                    detail = "Computer Use 可操作允许的桌面 app, 但不能自动化 Codex 自身或终端类受保护宿主。",
                    command = "/references",
                    keywords = listOf("computer use", "安全", "截图", "受保护宿主"),
                ),
            ),
        ),
    )

    return CodexCommandReference(
        title = "Codex 命令与快捷入口地图",
        sections = sections,
        primaryCommand = "/commands",
    )
}

internal fun codexCommandReferenceText(reference: CodexCommandReference): String = buildString {
    appendLine("## Codex 命令地图")
    appendLine("- 状态: **${reference.title}**")
    appendLine("- 条目: ${reference.itemCount}")
    appendLine("- 快捷键: ${reference.shortcutCount}")
    appendLine("- Slash: ${reference.slashCount}")
    appendLine("- 深链: ${reference.deepLinkCount}")
    appendLine("- 建议入口: `${reference.primaryCommand}`")
    appendLine()
    reference.sections.forEach { section ->
        appendLine("### ${section.title}")
        appendLine("- ${section.detail}")
        section.items.forEach { item ->
            append("- ${item.title}: ${item.detail}")
            appendLine()
            val bits = listOfNotNull(
                item.shortcut.takeIf { it.isNotBlank() }?.let { "快捷: `$it`" },
                item.command.takeIf { it.isNotBlank() }?.let { "命令: `$it`" },
                item.deepLink.takeIf { it.isNotBlank() }?.let { "深链: `$it`" },
            )
            if (bits.isNotEmpty()) appendLine("  - ${bits.joinToString(" · ")}")
        }
        appendLine()
    }
}
