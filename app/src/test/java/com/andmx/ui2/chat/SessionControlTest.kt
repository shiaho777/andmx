package com.andmx.ui2.chat

import com.andmx.agent.AgentEngine
import com.andmx.agent.GoalToolState
import com.andmx.agent.UpdatePlanTool
import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionControlTest {

    private fun engine(): AgentEngine = AgentEngine(
        tools = emptyList(),
        client = object : LlmApi {
            override suspend fun chat(request: ChatRequest): Result<ApiMessage> =
                Result.success(ApiMessage(role = "assistant", content = "ok"))
        },
    )

    private fun session(conversationId: Long): Session = Session(
        conversationId = conversationId,
        engine = engine(),
        providerId = "provider-$conversationId",
        model = "model-$conversationId",
        planTool = UpdatePlanTool(),
        goalState = GoalToolState(),
        approvalMode = ExecMode.BUILD,
    )

    @Test
    fun sessionsIsolatedPerConversation() {
        val control = SessionControl()
        val a = session(1L)
        val b = session(2L)
        control.sessions[1L] = a
        control.sessions[2L] = b
        assertSame(a, control.sessions[1L])
        assertSame(b, control.sessions[2L])
        assertEquals("provider-1", control.sessions[1L]?.providerId)
        assertEquals("provider-2", control.sessions[2L]?.providerId)
        control.sessions.remove(1L)
        assertNull(control.sessions[1L])
        assertSame(b, control.sessions[2L])
    }

    @Test
    fun keyedStateIsolatedPerConversation() {
        val control = SessionControl()
        control.sessionTraces[1L] = "trace-a"
        control.sessionTraces[2L] = "trace-b"
        control.dynamicActorCounts["session:run:site@0"] = 3
        control.dynamicActorCounts["session:run:site@1"] = 7
        control.automationIdsByConversation[1L] = "auto-a"
        control.automationIdsByConversation[2L] = "auto-b"
        control.runningConversations.add(1L)
        control.titleSidecarAttempted.add(2L)
        control.approvalRulesByConversation
            .computeIfAbsent(1L) { java.util.concurrent.ConcurrentHashMap() }["shell:ls"] =
            ApprovalScope.SESSION_ALLOW

        assertEquals("trace-a", control.sessionTraces[1L])
        assertEquals("trace-b", control.sessionTraces[2L])
        assertEquals(3, control.dynamicActorCounts["session:run:site@0"])
        assertEquals(7, control.dynamicActorCounts["session:run:site@1"])
        assertEquals("auto-a", control.automationIdsByConversation[1L])
        assertEquals("auto-b", control.automationIdsByConversation[2L])
        assertTrue(control.runningConversations.contains(1L))
        assertFalse(control.runningConversations.contains(2L))
        assertFalse(control.titleSidecarAttempted.contains(1L))
        assertTrue(control.titleSidecarAttempted.contains(2L))
        assertEquals(
            ApprovalScope.SESSION_ALLOW,
            control.approvalRulesByConversation[1L]?.get("shell:ls"),
        )
        assertNull(control.approvalRulesByConversation[2L])
    }

    @Test
    fun concurrentPutsKeepAllEntries() {
        val control = SessionControl()
        val threads = 8
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    val key = (t * perThread + i).toLong()
                    control.sessionTraces[key] = "trace-$key"
                    control.runningConversations.add(key)
                    control.titleSidecarAttempted.add(key)
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        val total = threads * perThread
        assertEquals(total, control.sessionTraces.size)
        assertEquals(total, control.runningConversations.size)
        assertEquals(total, control.titleSidecarAttempted.size)
        repeat(total) { i ->
            assertEquals("trace-$i", control.sessionTraces[i.toLong()])
        }
    }

    @Test
    fun sessionFieldsRoundTrip() {
        val control = SessionControl()
        val s = session(42L)
        val pending = CompletableDeferred<Boolean>()
        val pendingAnswer = CompletableDeferred<String>()
        s.approvalMode = ExecMode.PLAN
        s.providerId = "p2"
        s.model = "m2"
        s.pending = pending
        s.pendingArgs = "{\"cmd\":\"ls\"}"
        s.pendingAnswer = pendingAnswer
        s.answerSnoozed = true
        s.turnToolOutputs += "shell" to "ok"
        s.lastUserText = "user text"
        s.lastAssistantText = "assistant text"
        s.lastAssistantTurnId = "turn-9"
        control.sessions[42L] = s

        val loaded = control.sessions[42L]!!
        assertSame(s, loaded)
        assertEquals(42L, loaded.conversationId)
        assertEquals("p2", loaded.providerId)
        assertEquals("m2", loaded.model)
        assertEquals(ExecMode.PLAN, loaded.approvalMode)
        assertSame(pending, loaded.pending)
        assertEquals("{\"cmd\":\"ls\"}", loaded.pendingArgs)
        assertSame(pendingAnswer, loaded.pendingAnswer)
        assertTrue(loaded.answerSnoozed)
        assertNull(loaded.answerTimer)
        assertEquals(listOf("shell" to "ok"), loaded.turnToolOutputs)
        assertEquals("user text", loaded.lastUserText)
        assertEquals("assistant text", loaded.lastAssistantText)
        assertEquals("turn-9", loaded.lastAssistantTurnId)
        assertSame(s.engine, loaded.engine)
        assertSame(s.planTool, loaded.planTool)
        assertSame(s.goalState, loaded.goalState)

        loaded.lastUserText = "updated"
        loaded.pending = null
        assertEquals("updated", s.lastUserText)
        assertNull(s.pending)
    }

    @Test
    fun mapAccessorsReturnSameInstance() {
        val control = SessionControl()
        assertSame(control.sessions, control.sessions)
        assertSame(control.turnJobs, control.turnJobs)
        assertSame(control.sessionTraces, control.sessionTraces)
        assertSame(control.sessionAccessOrder, control.sessionAccessOrder)
        control.sessions[7L] = session(7L)
        assertEquals(1, control.sessions.size)
    }
}
