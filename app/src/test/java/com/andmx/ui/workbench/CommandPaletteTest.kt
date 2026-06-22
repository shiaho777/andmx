package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPaletteTest {

    @Test
    fun emptyQueryReturnsDefaultCommands() {
        val commands = filterCommands("")

        assertTrue(commands.size >= 10)
        assertEquals(CommandId.NEW_CHAT, commands.first().id)
    }

    @Test
    fun filtersBySlashCommandAndEnglishKeywords() {
        assertEquals(CommandId.STATUS, filterCommands("/status").first().id)
        assertEquals(CommandId.CONTEXT, filterCommands("/context").first().id)
        assertEquals(CommandId.CONTEXT, filterCommands("budget").first().id)
        assertEquals(CommandId.PLAN, filterCommands("/plan").first().id)
        assertEquals(CommandId.PLAN, filterCommands("todo").first().id)
        assertEquals(CommandId.VERIFY, filterCommands("/verify").first().id)
        assertEquals(CommandId.VERIFY, filterCommands("checks").first().id)
        assertEquals(CommandId.CHANGES, filterCommands("/changes").first().id)
        assertEquals(CommandId.CHANGES, filterCommands("diffs").first().id)
        assertEquals(CommandId.ACTIVITY, filterCommands("/activity").first().id)
        assertEquals(CommandId.ACTIVITY, filterCommands("/log").first().id)
        assertEquals(CommandId.ACTIVITY, filterCommands("activity").first().id)
        assertEquals(CommandId.ACTIVITY, filterCommands("timeline").first().id)
        assertEquals(CommandId.CHECKLIST, filterCommands("/checklist").first().id)
        assertEquals(CommandId.CHECKLIST, filterCommands("/audit").first().id)
        assertEquals(CommandId.CHECKLIST, filterCommands("completion").first().id)
        assertEquals(CommandId.NEXT, filterCommands("/next").first().id)
        assertEquals(CommandId.NEXT, filterCommands("/why").first().id)
        assertEquals(CommandId.NEXT, filterCommands("decision").first().id)
        assertEquals(CommandId.EVIDENCE, filterCommands("/evidence").first().id)
        assertEquals(CommandId.EVIDENCE, filterCommands("/sources").first().id)
        assertEquals(CommandId.EVIDENCE, filterCommands("proof").first().id)
        assertEquals(CommandId.REFERENCES, filterCommands("/references").first().id)
        assertEquals(CommandId.REFERENCES, filterCommands("/refs").first().id)
        assertEquals(CommandId.REFERENCES, filterCommands("screenshot").first().id)
        assertEquals(CommandId.BLUEPRINT, filterCommands("/blueprint").first().id)
        assertEquals(CommandId.BLUEPRINT, filterCommands("/replica").first().id)
        assertEquals(CommandId.BLUEPRINT, filterCommands("blueprint").first().id)
        assertEquals(CommandId.POLICY, filterCommands("/policy").first().id)
        assertEquals(CommandId.POLICY, filterCommands("/permissions").first().id)
        assertEquals(CommandId.POLICY, filterCommands("/safety").first().id)
        assertEquals(CommandId.POLICY, filterCommands("permission").first().id)
        assertEquals(CommandId.TOOLS, filterCommands("/tools").first().id)
        assertEquals(CommandId.TOOLS, filterCommands("tool map").first().id)
        assertEquals(CommandId.PARITY, filterCommands("/parity").first().id)
        assertEquals(CommandId.PARITY, filterCommands("/audit-codex").first().id)
        assertEquals(CommandId.PARITY, filterCommands("benchmark").first().id)
        assertEquals(CommandId.REPORT, filterCommands("/report").first().id)
        assertEquals(CommandId.REPORT, filterCommands("/deliver").first().id)
        assertEquals(CommandId.REPORT, filterCommands("report").first().id)
        assertEquals(CommandId.ARCHITECTURE, filterCommands("/architecture").first().id)
        assertEquals(CommandId.ARCHITECTURE, filterCommands("/arch").first().id)
        assertEquals(CommandId.ARCHITECTURE, filterCommands("architecture").first().id)
        assertEquals(CommandId.SURFACES, filterCommands("/surfaces").first().id)
        assertEquals(CommandId.SURFACES, filterCommands("/ui-map").first().id)
        assertEquals(CommandId.SURFACES, filterCommands("surfaces").first().id)
        assertEquals(CommandId.VISUAL_CHECK, filterCommands("/visual-check").first().id)
        assertEquals(CommandId.VISUAL_CHECK, filterCommands("/visual").first().id)
        assertEquals(CommandId.VISUAL_CHECK, filterCommands("visual").first().id)
        assertEquals(CommandId.DESIGN_SYSTEM, filterCommands("/design-system").first().id)
        assertEquals(CommandId.DESIGN_SYSTEM, filterCommands("/design").first().id)
        assertEquals(CommandId.DESIGN_SYSTEM, filterCommands("density").first().id)
        assertEquals(CommandId.SCREENSHOT_EXTRACT, filterCommands("/screenshot-extract").first().id)
        assertEquals(CommandId.SCREENSHOT_EXTRACT, filterCommands("/extract-ui").first().id)
        assertEquals(CommandId.SCREENSHOT_EXTRACT, filterCommands("screenshot extraction").first().id)
        assertEquals(CommandId.APPSHOTS, filterCommands("/appshots").first().id)
        assertEquals(CommandId.APPSHOTS, filterCommands("/capture-ui").first().id)
        assertEquals(CommandId.APPSHOTS, filterCommands("appshots").first().id)
        assertEquals(CommandId.TRACE, filterCommands("/trace").first().id)
        assertEquals(CommandId.TRACE, filterCommands("/implementation-trace").first().id)
        assertEquals(CommandId.TRACE, filterCommands("implementation trace").first().id)
        assertEquals(CommandId.SELF_MODEL, filterCommands("/self-model").first().id)
        assertEquals(CommandId.SELF_MODEL, filterCommands("/self").first().id)
        assertEquals(CommandId.SELF_MODEL, filterCommands("introspection").first().id)
        assertEquals(CommandId.FLOW, filterCommands("/flow").first().id)
        assertEquals(CommandId.FLOW, filterCommands("/interaction").first().id)
        assertEquals(CommandId.FLOW, filterCommands("interaction loop").first().id)
        assertEquals(CommandId.METHOD, filterCommands("/method").first().id)
        assertEquals(CommandId.METHOD, filterCommands("methodology").first().id)
        assertEquals(CommandId.METHOD, filterCommands("agent loop").first().id)
        assertEquals(CommandId.IMPROVE, filterCommands("/improve").first().id)
        assertEquals(CommandId.IMPROVE, filterCommands("/self-improve").first().id)
        assertEquals(CommandId.IMPROVE, filterCommands("self improvement").first().id)
        assertEquals(CommandId.PARITY, filterCommands("codex").first().id)
        assertEquals(CommandId.INSTRUCTIONS, filterCommands("/instructions").first().id)
        assertEquals(CommandId.INSTRUCTIONS, filterCommands("config").first().id)
        assertEquals(CommandId.INSTRUCTIONS, filterCommands("contract").first().id)
        assertEquals(CommandId.COMMANDS, filterCommands("/commands").first().id)
        assertEquals(CommandId.COMMANDS, filterCommands("/shortcuts").first().id)
        assertEquals(CommandId.COMMANDS, filterCommands("deep link").first().id)
        assertEquals(CommandId.SHOW_GOAL, filterCommands("/goal").first().id)
        assertEquals(CommandId.SHOW_GOAL, filterCommands("/objective").first().id)
        assertEquals(CommandId.HANDOFF, filterCommands("/handoff").first().id)
        assertEquals(CommandId.HANDOFF, filterCommands("summary").first().id)
        assertEquals(CommandId.SET_FULL_ACCESS, filterCommands("/full").first().id)
        assertEquals(CommandId.SET_ASK_APPROVAL, filterCommands("/ask").first().id)
        assertEquals(CommandId.SET_READ_ONLY, filterCommands("/readonly").first().id)
        assertEquals(CommandId.OPEN_INSPECTOR, filterCommands("inspector").first().id)
        assertEquals(CommandId.OPEN_DIFF, filterCommands("review changes").first().id)
    }

    @Test
    fun filtersByChineseKeywords() {
        assertEquals(CommandId.OPEN_BROWSER, filterCommands("浏览").first().id)
        assertEquals(CommandId.CONTEXT, filterCommands("上下文").first().id)
        assertEquals(CommandId.PLAN, filterCommands("计划").first().id)
        assertEquals(CommandId.VERIFY, filterCommands("验证").first().id)
        assertEquals(CommandId.VERIFY, filterCommands("测试").first().id)
        assertEquals(CommandId.CHANGES, filterCommands("变更").first().id)
        assertEquals(CommandId.ACTIVITY, filterCommands("活动").first().id)
        assertEquals(CommandId.ACTIVITY, filterCommands("日志").first().id)
        assertEquals(CommandId.CHECKLIST, filterCommands("清单").first().id)
        assertEquals(CommandId.REPORT, filterCommands("交付").first().id)
        assertEquals(CommandId.NEXT, filterCommands("下一步").first().id)
        assertEquals(CommandId.NEXT, filterCommands("原因").first().id)
        assertEquals(CommandId.EVIDENCE, filterCommands("证据").first().id)
        assertEquals(CommandId.EVIDENCE, filterCommands("依据").first().id)
        assertEquals(CommandId.REFERENCES, filterCommands("截图").first().id)
        assertEquals(CommandId.REFERENCES, filterCommands("界面").first().id)
        assertEquals(CommandId.BLUEPRINT, filterCommands("蓝图").first().id)
        assertEquals(CommandId.BLUEPRINT, filterCommands("复刻").first().id)
        assertEquals(CommandId.POLICY, filterCommands("策略").first().id)
        assertEquals(CommandId.POLICY, filterCommands("权限").first().id)
        assertEquals(CommandId.POLICY, filterCommands("边界").first().id)
        assertEquals(CommandId.TOOLS, filterCommands("能力").first().id)
        assertEquals(CommandId.TOOLS, filterCommands("工具地图").first().id)
        assertEquals(CommandId.TOOLS, filterCommands("能力地图").first().id)
        assertEquals(CommandId.PARITY, filterCommands("对标").first().id)
        assertEquals(CommandId.PARITY, filterCommands("缺口").first().id)
        assertEquals(CommandId.REPORT, filterCommands("报告").first().id)
        assertEquals(CommandId.REPORT, filterCommands("收束").first().id)
        assertEquals(CommandId.ARCHITECTURE, filterCommands("架构").first().id)
        assertEquals(CommandId.ARCHITECTURE, filterCommands("链路").first().id)
        assertEquals(CommandId.SURFACES, filterCommands("表面").first().id)
        assertEquals(CommandId.SURFACES, filterCommands("控件").first().id)
        assertEquals(CommandId.VISUAL_CHECK, filterCommands("视觉").first().id)
        assertEquals(CommandId.VISUAL_CHECK, filterCommands("验收").first().id)
        assertEquals(CommandId.DESIGN_SYSTEM, filterCommands("设计系统").first().id)
        assertEquals(CommandId.DESIGN_SYSTEM, filterCommands("密度").first().id)
        assertEquals(CommandId.SCREENSHOT_EXTRACT, filterCommands("截图解析").first().id)
        assertEquals(CommandId.SCREENSHOT_EXTRACT, filterCommands("界面提取").first().id)
        assertEquals(CommandId.APPSHOTS, filterCommands("视觉上下文").first().id)
        assertEquals(CommandId.APPSHOTS, filterCommands("窗口采集").first().id)
        assertEquals(CommandId.TRACE, filterCommands("实现追踪").first().id)
        assertEquals(CommandId.TRACE, filterCommands("截图实现").first().id)
        assertEquals(CommandId.SELF_MODEL, filterCommands("自我模型").first().id)
        assertEquals(CommandId.SELF_MODEL, filterCommands("系统环境").first().id)
        assertEquals(CommandId.FLOW, filterCommands("交互流程").first().id)
        assertEquals(CommandId.FLOW, filterCommands("操作链路").first().id)
        assertEquals(CommandId.METHOD, filterCommands("工作流").first().id)
        assertEquals(CommandId.IMPROVE, filterCommands("自我完善").first().id)
        assertEquals(CommandId.IMPROVE, filterCommands("自我改进").first().id)
        assertEquals(CommandId.IMPROVE, filterCommands("路线图").first().id)
        assertEquals(CommandId.METHOD, filterCommands("工具环境").first().id)
        assertEquals(CommandId.INSTRUCTIONS, filterCommands("指令").first().id)
        assertEquals(CommandId.INSTRUCTIONS, filterCommands("系统提示").first().id)
        assertEquals(CommandId.INSTRUCTIONS, filterCommands("环境契约").first().id)
        assertEquals(CommandId.COMMANDS, filterCommands("快捷键").first().id)
        assertEquals(CommandId.COMMANDS, filterCommands("深链").first().id)
        assertEquals(CommandId.SHOW_GOAL, filterCommands("目标").first().id)
        assertEquals(CommandId.SHOW_GOAL, filterCommands("持久目标").first().id)
        assertEquals(CommandId.HANDOFF, filterCommands("交接").first().id)
        assertEquals(CommandId.OPEN_INSPECTOR, filterCommands("自检").first().id)
        assertEquals(CommandId.DIAG, filterCommands("环境").first().id)
    }

    @Test
    fun buildsSelectableEntriesWithCommandsFirst() {
        val entries = paletteEntries(
            commands = listOf(DefaultCommandPaletteItems.first()),
            conversationIds = listOf(10L, 20L),
        )

        assertEquals(PaletteEntry.Command(DefaultCommandPaletteItems.first()), entries[0])
        assertEquals(PaletteEntry.Conversation(10L), entries[1])
        assertEquals(PaletteEntry.Conversation(20L), entries[2])
    }

    @Test
    fun recentCommandsAreDedupedAndLimited() {
        val recent = updatedRecentCommands(
            CommandId.STATUS,
            listOf(
                CommandId.TOOLS,
                CommandId.STATUS,
                CommandId.VERIFY,
                CommandId.CHANGES,
                CommandId.DIAG,
                CommandId.EXPORT,
                CommandId.SET_READ_ONLY,
            ),
            limit = 4,
        )

        assertEquals(
            listOf(CommandId.STATUS, CommandId.TOOLS, CommandId.VERIFY, CommandId.CHANGES),
            recent,
        )
    }

    @Test
    fun emptyQueryShowsRecentCommandsBeforeDefaultCommands() {
        val sections = paletteCommandSections(
            query = "",
            recentCommandIds = listOf(CommandId.TOOLS, CommandId.STATUS),
        )

        assertEquals(listOf(CommandId.TOOLS, CommandId.STATUS), sections.recent.map { it.id })
        assertTrue(sections.commands.none { it.id == CommandId.TOOLS })
        assertTrue(sections.commands.none { it.id == CommandId.STATUS })
        assertEquals(CommandId.NEW_CHAT, sections.commands.first().id)
    }

    @Test
    fun queryHidesRecentCommandsAndFiltersNormally() {
        val sections = paletteCommandSections(
            query = "授权",
            recentCommandIds = listOf(CommandId.STATUS, CommandId.TOOLS),
        )

        assertTrue(sections.recent.isEmpty())
        assertEquals(
            listOf(CommandId.SET_FULL_ACCESS, CommandId.SET_ASK_APPROVAL, CommandId.SET_READ_ONLY),
            sections.commands.map { it.id },
        )
    }

    @Test
    fun keyboardSelectionWrapsAndClamps() {
        assertEquals(1, movePaletteSelection(0, size = 3, delta = 1))
        assertEquals(0, movePaletteSelection(2, size = 3, delta = 1))
        assertEquals(2, movePaletteSelection(0, size = 3, delta = -1))
        assertEquals(0, movePaletteSelection(0, size = 0, delta = 1))

        assertEquals(2, clampPaletteSelection(9, size = 3))
        assertEquals(0, clampPaletteSelection(0, size = 0))
    }

    @Test
    fun detectsCommandPaletteShortcuts() {
        assertTrue(isCommandPaletteShortcut("k", ctrl = true, meta = false, shift = false))
        assertTrue(isCommandPaletteShortcut("K", ctrl = false, meta = true, shift = false))
        assertTrue(isCommandPaletteShortcut("p", ctrl = true, meta = false, shift = true))
        assertTrue(isCommandPaletteShortcut("P", ctrl = false, meta = true, shift = true))

        assertEquals(false, isCommandPaletteShortcut("p", ctrl = true, meta = false, shift = false))
        assertEquals(false, isCommandPaletteShortcut("k", ctrl = false, meta = false, shift = false))
    }
}
