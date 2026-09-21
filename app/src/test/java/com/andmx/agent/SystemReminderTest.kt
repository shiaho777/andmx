package com.andmx.agent

import com.andmx.agent.multi.formatAgentTaskNotification
import com.andmx.llm.ApiMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemReminderTest {

    @Test
    fun wrapProducesCanonicalTags() {
        assertEquals(
            "<system-reminder>\nhello\n</system-reminder>",
            SystemReminder.wrap("hello"),
        )
    }

    @Test
    fun wrapRejectsEmptyAndNestedTags() {
        try {
            SystemReminder.wrap("")
            error("expected failure")
        } catch (e: IllegalArgumentException) { }
        try {
            SystemReminder.wrap("a <system-reminder> b")
            error("expected failure")
        } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun wrapForSourceEscapesNestedTags() {
        val out = SystemReminder.wrap(
            SystemReminder.Source.MODEL_ANOMALY,
            "model echoed <system-reminder> literally",
        )
        assertTrue(out.contains("&lt;system-reminder>"))
        assertTrue(out.startsWith("<system-reminder>\n"))
        assertTrue(out.endsWith("</system-reminder>"))
    }

    @Test
    fun contextPrefixGetsTrailingNewline() {
        assertTrue(
            SystemReminder.wrap(SystemReminder.Source.CONTEXT_PREFIX, "x").endsWith("</system-reminder>\n"),
        )
    }

    @Test
    fun descriptorsMatchUpstream() {
        fun d(s: SystemReminder.Source) = SystemReminder.descriptor(s)
        assertEquals(SystemReminder.Channel.CURRENT_TURN, d(SystemReminder.Source.RUNTIME_MODE).channel)
        assertEquals(SystemReminder.Lifecycle.PER_CURRENT_TURN, d(SystemReminder.Source.RUNTIME_MODE).lifecycle)
        assertEquals(SystemReminder.Channel.CURRENT_TURN, d(SystemReminder.Source.PLAN_MODE_EXIT).channel)
        assertEquals(SystemReminder.Lifecycle.RUNTIME_LOCAL, d(SystemReminder.Source.PLAN_MODE_EXIT).lifecycle)
        assertEquals(SystemReminder.Channel.REAL_USER, d(SystemReminder.Source.TARGET_CONTINUATION).channel)
        assertFalse(d(SystemReminder.Source.TARGET_CONTINUATION).isMeta)
        assertEquals(SystemReminder.Channel.MID_TURN_EVENT, d(SystemReminder.Source.QUEUED_SYSTEM_NOTIFICATION).channel)
        assertEquals(SystemReminder.Channel.HISTORY_CONTINUITY, d(SystemReminder.Source.REWIND_NOTICE).channel)
        // All 27 upstream sources exist (2 prefix + 15 persisted + 10 per-request).
        assertEquals(27, SystemReminder.Source.entries.size)
        // Non-mid-conversation set mirrors upstream.
        assertFalse(SystemReminder.isMidConversation(SystemReminder.Source.CONVERSATION_FORK))
        assertFalse(SystemReminder.isMidConversation(SystemReminder.Source.TARGET_CONTINUATION))
        assertTrue(SystemReminder.isMidConversation(SystemReminder.Source.RUNTIME_MODE))
    }

    @Test
    fun dateChangeReminderFiresOnDateEdge() = runTest {
        var date = "2026-09-21"
        val llm = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<ApiMessage> =
                Result.success(ApiMessage(role = "assistant", content = "ok"))
        }
        val engine = AgentEngine(tools = emptyList(), client = llm, dateProvider = { date })
        val settings = com.andmx.settings.ProviderSettings(model = "m")
        val turn = TurnContext(
            provider = com.andmx.llm.provider.ProviderDefinition(id = "t", name = "t", baseUrl = "http://x"),
            model = "m",
        )
        engine.runTurn(settings, turn, "t1").toList()
        assertNull(
            engine.snapshotHistory().firstOrNull {
                it.content?.contains("The date has changed") == true
            },
        )
        date = "2026-09-22"
        engine.runTurn(settings, turn, "t2").toList()
        val reminders = engine.snapshotHistory().filter {
            it.role == "system" && it.content?.contains("The date has changed") == true
        }
        assertEquals(1, reminders.size)
        assertTrue(reminders[0].content!!.contains("2026-09-22"))
        // Same date → no repeat.
        engine.runTurn(settings, turn, "t3").toList()
        assertEquals(
            1,
            engine.snapshotHistory().count { it.content?.contains("The date has changed") == true },
        )
    }

    @Test
    fun injectSystemReminderAppendsWrappedSystemMessage() = runTest {
        val llm = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<ApiMessage> =
                Result.success(ApiMessage(role = "assistant", content = "ok"))
        }
        val engine = AgentEngine(tools = emptyList(), client = llm)
        engine.injectSystemReminder(
            SystemReminder.Source.QUEUED_SYSTEM_NOTIFICATION,
            "<task-notification>demo</task-notification>",
        )
        val last = engine.snapshotHistory().last()
        assertEquals("system", last.role)
        assertTrue(last.content!!.startsWith("<system-reminder>\n<task-notification>"))
    }

    @Test
    fun taskNotificationMatchesUpstreamShape() {
        val body = formatAgentTaskNotification(
            taskId = "a1",
            agentId = "a1",
            subagentType = "Explore",
            status = "completed",
            description = "scan repo",
            result = "found 3 files",
            durationMs = 1200,
        )
        assertTrue(body.startsWith("<task-notification>"))
        assertTrue(body.contains("<task-id>a1</task-id>"))
        assertTrue(body.contains("<subagent-type>Explore</subagent-type>"))
        assertTrue(body.contains("<status>completed</status>"))
        assertTrue(body.contains("<summary>Agent Explore task \"scan repo\" completed.</summary>"))
        assertTrue(body.contains("<result>found 3 files</result>"))
        assertTrue(body.endsWith("</task-notification>"))
        // XML escaping
        val esc = formatAgentTaskNotification(
            taskId = "a<2>", agentId = "a<2>", subagentType = "", status = "failed",
            description = "d", error = "bad & <worse>",
        )
        assertTrue(esc.contains("<error>bad &amp; &lt;worse&gt;</error>"))
        assertTrue(esc.contains("failed. bad &amp; &lt;worse&gt;"))
    }
}
