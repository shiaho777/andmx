package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {

    @Test
    fun plainTextIsNotCommand() {
        assertTrue(SlashCommands.parse("hello world") is SlashResult.NotCommand)
        assertTrue(SlashCommands.parse("  not / a command") is SlashResult.NotCommand)
    }

    @Test
    fun suggestionsIgnorePlainText() {
        assertTrue(SlashCommands.suggestions("hello world").isEmpty())
        assertTrue(SlashCommands.suggestions("  not / a command").isEmpty())
    }

    @Test
    fun recognizesCommands() {
        assertTrue(SlashCommands.parse("/clear") is SlashResult.Clear)
        assertTrue(SlashCommands.parse("/help") is SlashResult.Help)
        assertTrue(SlashCommands.parse("/status") is SlashResult.Status)
        assertTrue(SlashCommands.parse("/context") is SlashResult.Context)
        assertTrue(SlashCommands.parse("/budget") is SlashResult.Context)
        assertTrue(SlashCommands.parse("/plan") is SlashResult.Plan)
        assertTrue(SlashCommands.parse("/todo") is SlashResult.Plan)
        assertTrue(SlashCommands.parse("/verify") is SlashResult.Verify)
        assertTrue(SlashCommands.parse("/checks") is SlashResult.Verify)
        assertTrue(SlashCommands.parse("/changes") is SlashResult.Changes)
        assertTrue(SlashCommands.parse("/diffs") is SlashResult.Changes)
        assertTrue(SlashCommands.parse("/activity") is SlashResult.Activity)
        assertTrue(SlashCommands.parse("/log") is SlashResult.Activity)
        assertTrue(SlashCommands.parse("/checklist") is SlashResult.Checklist)
        assertTrue(SlashCommands.parse("/audit") is SlashResult.Checklist)
        assertTrue(SlashCommands.parse("/next") is SlashResult.Next)
        assertTrue(SlashCommands.parse("/why") is SlashResult.Next)
        assertTrue(SlashCommands.parse("/evidence") is SlashResult.Evidence)
        assertTrue(SlashCommands.parse("/sources") is SlashResult.Evidence)
        assertTrue(SlashCommands.parse("/references") is SlashResult.References)
        assertTrue(SlashCommands.parse("/refs") is SlashResult.References)
        assertTrue(SlashCommands.parse("/screens") is SlashResult.References)
        assertTrue(SlashCommands.parse("/blueprint") is SlashResult.Blueprint)
        assertTrue(SlashCommands.parse("/replica") is SlashResult.Blueprint)
        assertTrue(SlashCommands.parse("/policy") is SlashResult.Policy)
        assertTrue(SlashCommands.parse("/permissions") is SlashResult.Policy)
        assertTrue(SlashCommands.parse("/safety") is SlashResult.Policy)
        assertTrue(SlashCommands.parse("/tools") is SlashResult.Tools)
        assertTrue(SlashCommands.parse("/capabilities") is SlashResult.Tools)
        assertTrue(SlashCommands.parse("/parity") is SlashResult.Parity)
        assertTrue(SlashCommands.parse("/audit-codex") is SlashResult.Parity)
        assertTrue(SlashCommands.parse("/report") is SlashResult.Report)
        assertTrue(SlashCommands.parse("/deliver") is SlashResult.Report)
        assertTrue(SlashCommands.parse("/architecture") is SlashResult.Architecture)
        assertTrue(SlashCommands.parse("/arch") is SlashResult.Architecture)
        assertTrue(SlashCommands.parse("/surfaces") is SlashResult.Surfaces)
        assertTrue(SlashCommands.parse("/ui-map") is SlashResult.Surfaces)
        assertTrue(SlashCommands.parse("/visual-check") is SlashResult.VisualCheck)
        assertTrue(SlashCommands.parse("/visual") is SlashResult.VisualCheck)
        assertTrue(SlashCommands.parse("/design-system") is SlashResult.DesignSystem)
        assertTrue(SlashCommands.parse("/design") is SlashResult.DesignSystem)
        assertTrue(SlashCommands.parse("/screenshot-extract") is SlashResult.ScreenshotExtract)
        assertTrue(SlashCommands.parse("/extract-ui") is SlashResult.ScreenshotExtract)
        assertTrue(SlashCommands.parse("/screenshots") is SlashResult.ScreenshotExtract)
        assertTrue(SlashCommands.parse("/appshots") is SlashResult.Appshots)
        assertTrue(SlashCommands.parse("/appshot") is SlashResult.Appshots)
        assertTrue(SlashCommands.parse("/capture-ui") is SlashResult.Appshots)
        assertTrue(SlashCommands.parse("/trace") is SlashResult.Trace)
        assertTrue(SlashCommands.parse("/implementation-trace") is SlashResult.Trace)
        assertTrue(SlashCommands.parse("/ui-trace") is SlashResult.Trace)
        assertTrue(SlashCommands.parse("/self-model") is SlashResult.SelfModel)
        assertTrue(SlashCommands.parse("/self") is SlashResult.SelfModel)
        assertTrue(SlashCommands.parse("/flow") is SlashResult.Flow)
        assertTrue(SlashCommands.parse("/interaction") is SlashResult.Flow)
        assertTrue(SlashCommands.parse("/loop") is SlashResult.Flow)
        assertTrue(SlashCommands.parse("/method") is SlashResult.Method)
        assertTrue(SlashCommands.parse("/workflow") is SlashResult.Method)
        assertTrue(SlashCommands.parse("/methodology") is SlashResult.Method)
        assertTrue(SlashCommands.parse("/improve") is SlashResult.Improve)
        assertTrue(SlashCommands.parse("/self-improve") is SlashResult.Improve)
        assertTrue(SlashCommands.parse("/kaizen") is SlashResult.Improve)
        assertTrue(SlashCommands.parse("/instructions") is SlashResult.Instructions)
        assertTrue(SlashCommands.parse("/config") is SlashResult.Instructions)
        assertTrue(SlashCommands.parse("/commands") is SlashResult.Commands)
        assertTrue(SlashCommands.parse("/shortcuts") is SlashResult.Commands)
        assertTrue(SlashCommands.parse("/keyboard") is SlashResult.Commands)
        assertTrue(SlashCommands.parse("/goal") is SlashResult.Goal)
        assertTrue(SlashCommands.parse("/objective") is SlashResult.Goal)
        assertTrue(SlashCommands.parse("/handoff") is SlashResult.Handoff)
        assertTrue(SlashCommands.parse("/summary") is SlashResult.Handoff)
        assertEquals(ApprovalMode.FULL, (SlashCommands.parse("/full") as SlashResult.Mode).mode)
        assertEquals(ApprovalMode.READ_ONLY, (SlashCommands.parse("/readonly") as SlashResult.Mode).mode)
    }

    @Test
    fun parsesGoalActions() {
        assertEquals(GoalAction.SHOW, (SlashCommands.parse("/goal") as SlashResult.Goal).action)
        assertEquals(GoalAction.SHOW, (SlashCommands.parse("/goal status") as SlashResult.Goal).action)
        assertEquals(GoalAction.PAUSE, (SlashCommands.parse("/goal pause") as SlashResult.Goal).action)
        assertEquals(GoalAction.RESUME, (SlashCommands.parse("/goal resume") as SlashResult.Goal).action)
        assertEquals(GoalAction.CLEAR, (SlashCommands.parse("/goal clear") as SlashResult.Goal).action)

        val set = SlashCommands.parse("/goal 复刻 Codex 工作台") as SlashResult.Goal
        assertEquals(GoalAction.SET, set.action)
        assertEquals("复刻 Codex 工作台", set.text)
    }

    @Test
    fun unknownCommand() {
        val r = SlashCommands.parse("/wat now")
        assertTrue(r is SlashResult.Unknown)
        assertEquals("/wat", (r as SlashResult.Unknown).name)
    }

    @Test
    fun suggestionsMatchCommandPrefix() {
        assertEquals("/status", SlashCommands.suggestions("/sta").first().name)
    }

    @Test
    fun suggestionsMatchAliases() {
        assertEquals("/context", SlashCommands.suggestions("/bud").first().name)
        assertEquals("/plan", SlashCommands.suggestions("/todo").first().name)
        assertEquals("/verify", SlashCommands.suggestions("/checks").first().name)
        assertEquals("/changes", SlashCommands.suggestions("/diffs").first().name)
        assertEquals("/activity", SlashCommands.suggestions("/log").first().name)
        assertEquals("/checklist", SlashCommands.suggestions("/audit").first().name)
        assertEquals("/next", SlashCommands.suggestions("/why").first().name)
        assertEquals("/evidence", SlashCommands.suggestions("/sources").first().name)
        assertEquals("/references", SlashCommands.suggestions("/refs").first().name)
        assertEquals("/references", SlashCommands.suggestions("/screens").first().name)
        assertEquals("/blueprint", SlashCommands.suggestions("/replica").first().name)
        assertEquals("/policy", SlashCommands.suggestions("/permissions").first().name)
        assertEquals("/policy", SlashCommands.suggestions("/safety").first().name)
        assertEquals("/parity", SlashCommands.suggestions("/audit-codex").first().name)
        assertEquals("/report", SlashCommands.suggestions("/deliver").first().name)
        assertEquals("/architecture", SlashCommands.suggestions("/arch").first().name)
        assertEquals("/surfaces", SlashCommands.suggestions("/ui-map").first().name)
        assertEquals("/visual-check", SlashCommands.suggestions("/visual").first().name)
        assertEquals("/trace", SlashCommands.suggestions("/implementation-trace").first().name)
        assertEquals("/trace", SlashCommands.suggestions("/ui-trace").first().name)
        assertEquals("/appshots", SlashCommands.suggestions("/appshot").first().name)
        assertEquals("/appshots", SlashCommands.suggestions("/capture-ui").first().name)
        assertEquals("/flow", SlashCommands.suggestions("/interaction").first().name)
        assertEquals("/flow", SlashCommands.suggestions("/loop").first().name)
        assertEquals("/method", SlashCommands.suggestions("/methodology").first().name)
        assertEquals("/improve", SlashCommands.suggestions("/self-improve").first().name)
        assertEquals("/improve", SlashCommands.suggestions("/kaizen").first().name)
        assertEquals("/commands", SlashCommands.suggestions("/shortcuts").first().name)
        assertEquals("/commands", SlashCommands.suggestions("/keyboard").first().name)
        assertEquals("/goal", SlashCommands.suggestions("/objective").first().name)
    }

    @Test
    fun suggestionsMatchDescriptionsAndKeywords() {
        assertEquals("/instructions", SlashCommands.suggestions("/指令").first().name)
        assertEquals("/instructions", SlashCommands.suggestions("/系统提示").first().name)
        assertEquals("/instructions", SlashCommands.suggestions("/环境契约").first().name)
        assertEquals("/verify", SlashCommands.suggestions("/验证").first().name)
        assertEquals("/changes", SlashCommands.suggestions("/变更").first().name)
        assertEquals("/activity", SlashCommands.suggestions("/活动").first().name)
        assertEquals("/activity", SlashCommands.suggestions("/日志").first().name)
        assertEquals("/checklist", SlashCommands.suggestions("/清单").first().name)
        assertEquals("/report", SlashCommands.suggestions("/交付").first().name)
        assertEquals("/next", SlashCommands.suggestions("/下一步").first().name)
        assertEquals("/next", SlashCommands.suggestions("/原因").first().name)
        assertEquals("/evidence", SlashCommands.suggestions("/证据").first().name)
        assertEquals("/evidence", SlashCommands.suggestions("/依据").first().name)
        assertEquals("/references", SlashCommands.suggestions("/截图").first().name)
        assertEquals("/references", SlashCommands.suggestions("/界面").first().name)
        assertEquals("/blueprint", SlashCommands.suggestions("/蓝图").first().name)
        assertEquals("/blueprint", SlashCommands.suggestions("/复刻").first().name)
        assertEquals("/policy", SlashCommands.suggestions("/策略").first().name)
        assertEquals("/policy", SlashCommands.suggestions("/权限").first().name)
        assertEquals("/policy", SlashCommands.suggestions("/边界").first().name)
        assertEquals("/parity", SlashCommands.suggestions("/对标").first().name)
        assertEquals("/parity", SlashCommands.suggestions("/缺口").first().name)
        assertEquals("/architecture", SlashCommands.suggestions("/架构").first().name)
        assertEquals("/architecture", SlashCommands.suggestions("/链路").first().name)
        assertEquals("/surfaces", SlashCommands.suggestions("/表面").first().name)
        assertEquals("/surfaces", SlashCommands.suggestions("/控件").first().name)
        assertEquals("/visual-check", SlashCommands.suggestions("/验收").first().name)
        assertEquals("/visual-check", SlashCommands.suggestions("/核查").first().name)
        assertEquals("/report", SlashCommands.suggestions("/报告").first().name)
        assertEquals("/report", SlashCommands.suggestions("/收束").first().name)
        assertEquals("/ask", SlashCommands.suggestions("/确认").first().name)
        assertEquals("/tools", SlashCommands.suggestions("/插件").first().name)
        assertEquals("/tools", SlashCommands.suggestions("/工具地图").first().name)
        assertEquals("/tools", SlashCommands.suggestions("/能力地图").first().name)
        assertEquals("/trace", SlashCommands.suggestions("/实现追踪").first().name)
        assertEquals("/trace", SlashCommands.suggestions("/截图实现").first().name)
        assertEquals("/appshots", SlashCommands.suggestions("/视觉上下文").first().name)
        assertEquals("/appshots", SlashCommands.suggestions("/窗口采集").first().name)
        assertEquals("/flow", SlashCommands.suggestions("/交互流程").first().name)
        assertEquals("/flow", SlashCommands.suggestions("/操作链路").first().name)
        assertEquals("/method", SlashCommands.suggestions("/方法论").first().name)
        assertEquals("/improve", SlashCommands.suggestions("/自我完善").first().name)
        assertEquals("/improve", SlashCommands.suggestions("/自我改进").first().name)
        assertEquals("/improve", SlashCommands.suggestions("/路线图").first().name)
        assertEquals("/commands", SlashCommands.suggestions("/快捷键").first().name)
        assertEquals("/commands", SlashCommands.suggestions("/深链").first().name)
        assertEquals("/goal", SlashCommands.suggestions("/目标").first().name)
        assertEquals("/goal", SlashCommands.suggestions("/持久目标").first().name)
    }

    @Test
    fun completeAddsTrailingSpace() {
        val spec = SlashCommands.suggestions("/sta").first()
        assertEquals("/status ", SlashCommands.complete(spec))
    }
}
