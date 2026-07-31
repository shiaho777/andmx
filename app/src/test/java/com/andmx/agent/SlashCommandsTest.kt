package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun recognizesSessionCommands() {
        assertTrue(SlashCommands.parse("/clear") is SlashResult.Clear)
        assertTrue(SlashCommands.parse("/new") is SlashResult.Clear)
        assertTrue(SlashCommands.parse("/compact") is SlashResult.Compact)
        assertTrue(SlashCommands.parse("/compress") is SlashResult.Compact)
        assertTrue(SlashCommands.parse("/summarize") is SlashResult.Compact)
        assertTrue(SlashCommands.parse("/checkpoint") is SlashResult.Checkpoint)
        assertTrue(SlashCommands.parse("/handoff-checkpoint") is SlashResult.Checkpoint)
        assertTrue(SlashCommands.parse("/regen") is SlashResult.Regenerate)
        assertTrue(SlashCommands.parse("/retry") is SlashResult.Regenerate)
        assertTrue(SlashCommands.parse("/regenerate") is SlashResult.Regenerate)
        assertTrue(SlashCommands.parse("/stop") is SlashResult.Stop)
        assertTrue(SlashCommands.parse("/cancel") is SlashResult.Stop)
        assertTrue(SlashCommands.parse("/status") is SlashResult.Status)
        assertTrue(SlashCommands.parse("/help") is SlashResult.Help)
        assertTrue(SlashCommands.parse("/?") is SlashResult.Help)
        assertTrue(SlashCommands.parse("/tools") is SlashResult.Tools)
        assertTrue(SlashCommands.parse("/capabilities") is SlashResult.Tools)
        assertTrue(SlashCommands.parse("/handoff") is SlashResult.Handoff)
        assertTrue(SlashCommands.parse("/summary") is SlashResult.Handoff)
        assertTrue(SlashCommands.parse("/export") is SlashResult.Export)
        assertTrue(SlashCommands.parse("/model") is SlashResult.OpenModel)
        assertTrue(SlashCommands.parse("/settings") is SlashResult.OpenModel)
    }

    @Test
    fun recognizesApprovalModes() {
        assertEquals(ApprovalMode.FULL, (SlashCommands.parse("/full") as SlashResult.Mode).mode)
        assertEquals(ApprovalMode.ASK, (SlashCommands.parse("/ask") as SlashResult.Mode).mode)
        assertEquals(ApprovalMode.READ_ONLY, (SlashCommands.parse("/readonly") as SlashResult.Mode).mode)
        assertEquals(ApprovalMode.READ_ONLY, (SlashCommands.parse("/read") as SlashResult.Mode).mode)
    }

    @Test
    fun parsingIsCaseInsensitive() {
        assertTrue(SlashCommands.parse("/CLEAR") is SlashResult.Clear)
        assertTrue(SlashCommands.parse("/Status") is SlashResult.Status)
    }

    @Test
    fun parsesGoalActions() {
        assertEquals(GoalAction.SHOW, (SlashCommands.parse("/goal") as SlashResult.Goal).action)
        assertEquals(GoalAction.SHOW, (SlashCommands.parse("/goal status") as SlashResult.Goal).action)
        assertEquals(GoalAction.SHOW, (SlashCommands.parse("/goal 查看") as SlashResult.Goal).action)
        assertEquals(GoalAction.PAUSE, (SlashCommands.parse("/goal pause") as SlashResult.Goal).action)
        assertEquals(GoalAction.PAUSE, (SlashCommands.parse("/goal 暂停") as SlashResult.Goal).action)
        assertEquals(GoalAction.RESUME, (SlashCommands.parse("/goal resume") as SlashResult.Goal).action)
        assertEquals(GoalAction.CLEAR, (SlashCommands.parse("/goal clear") as SlashResult.Goal).action)

        val set = SlashCommands.parse("/goal 复刻 Codex 工作台") as SlashResult.Goal
        assertEquals(GoalAction.SET, set.action)
        assertEquals("复刻 Codex 工作台", set.text)

        val edit = SlashCommands.parse("/goal edit 换个目标") as SlashResult.Goal
        assertEquals(GoalAction.EDIT, edit.action)
        assertEquals("换个目标", edit.text)
    }

    @Test
    fun goalAliasesShareParsing() {
        assertTrue(SlashCommands.parse("/target") is SlashResult.Goal)
        assertTrue(SlashCommands.parse("/objective") is SlashResult.Goal)
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
        assertEquals("/clear", SlashCommands.suggestions("/cle").first().name)
    }

    @Test
    fun suggestionsRankExactNameOverPrefixMatch() {
        // "/compact" is an exact name; "/compress"/"/summarize" only prefix-match.
        assertEquals("/compact", SlashCommands.suggestions("/compact").first().name)
    }

    @Test
    fun suggestionsMatchAliases() {
        assertEquals("/clear", SlashCommands.suggestions("/new").first().name)
        assertEquals("/compact", SlashCommands.suggestions("/compress").first().name)
        assertEquals("/compact", SlashCommands.suggestions("/summarize").first().name)
        assertEquals("/goal", SlashCommands.suggestions("/objective").first().name)
        assertEquals("/goal", SlashCommands.suggestions("/target").first().name)
        assertEquals("/stop", SlashCommands.suggestions("/cancel").first().name)
        assertEquals("/model", SlashCommands.suggestions("/settings").first().name)
        assertEquals("/readonly", SlashCommands.suggestions("/read").first().name)
        assertEquals("/regen", SlashCommands.suggestions("/retry").first().name)
        assertEquals("/handoff", SlashCommands.suggestions("/summary").first().name)
        assertEquals("/checkpoint", SlashCommands.suggestions("/handoff-checkpoint").first().name)
        assertEquals("/tools", SlashCommands.suggestions("/capabilities").first().name)
    }

    @Test
    fun suggestionsMatchChineseKeywords() {
        assertEquals("/goal", SlashCommands.suggestions("/目标").first().name)
        assertEquals("/compact", SlashCommands.suggestions("/压缩").first().name)
        assertEquals("/status", SlashCommands.suggestions("/状态").first().name)
        assertEquals("/help", SlashCommands.suggestions("/帮助").first().name)
        assertEquals("/tools", SlashCommands.suggestions("/工具").first().name)
        assertEquals("/export", SlashCommands.suggestions("/导出").first().name)
        assertEquals("/checkpoint", SlashCommands.suggestions("/检查点").first().name)
    }

    @Test
    fun bareSlashListsCommands() {
        val all = SlashCommands.suggestions("/")
        assertTrue(all.isNotEmpty())
        assertEquals(SlashCommands.list.first().name, all.first().name)
    }

    @Test
    fun suggestionsRespectLimit() {
        assertEquals(3, SlashCommands.suggestions("/", limit = 3).size)
    }

    @Test
    fun suggestionsIncludeExtraSpecs() {
        val extra = SlashCommands.Spec("/deploy", "插件命令")
        assertEquals("/deploy", SlashCommands.suggestions("/deploy", extras = listOf(extra)).first().name)
    }

    @Test
    fun completeAddsTrailingSpace() {
        val spec = SlashCommands.suggestions("/sta").first()
        assertEquals("/status ", SlashCommands.complete(spec))
    }

    @Test
    fun commandNamesAreUnique() {
        val names = SlashCommands.list.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    /**
     * Guards the drift that let this suite rot: a command advertised in [SlashCommands.list]
     * (or one of its aliases) must actually parse to something other than Unknown.
     */
    @Test
    fun everyAdvertisedCommandParses() {
        for (spec in SlashCommands.list) {
            for (token in listOf(spec.name) + spec.aliases) {
                val result = SlashCommands.parse(token)
                assertFalse(
                    "$token is advertised in SlashCommands.list but parse() returns Unknown",
                    result is SlashResult.Unknown,
                )
                assertFalse(
                    "$token is advertised in SlashCommands.list but parse() returns NotCommand",
                    result is SlashResult.NotCommand,
                )
            }
        }
    }

    /** Every advertised command must also be reachable from the suggestion menu. */
    @Test
    fun everyAdvertisedCommandIsDiscoverable() {
        for (spec in SlashCommands.list) {
            for (token in listOf(spec.name) + spec.aliases) {
                val hit = SlashCommands.suggestions(token, limit = SlashCommands.list.size)
                assertTrue(
                    "$token yields no suggestion",
                    hit.any { it.name == spec.name },
                )
            }
        }
    }
}
