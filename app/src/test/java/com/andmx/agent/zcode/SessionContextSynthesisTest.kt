package com.andmx.agent.zcode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContextSynthesisTest {

    private fun msg(role: String, content: String, toolName: String? = null) =
        SessionContextSynthesis.Message(role, content, toolName)

    @Test
    fun charBudgetClampsTokens() {
        assertEquals(4, SessionContextSynthesis.charBudget(0))
        assertEquals(400, SessionContextSynthesis.charBudget(100))
        assertEquals(48_000, SessionContextSynthesis.charBudget(20_000))
    }

    @Test
    fun coarseSelectFiltersByQuerySubstring() {
        val messages = listOf(
            msg("user", "fix the login bug"),
            msg("assistant", "unrelated chatter"),
            msg("assistant", "patched login flow", toolName = "Edit"),
        )
        val selected = SessionContextSynthesis.coarseSelect(messages, "login")
        assertEquals(listOf("fix the login bug", "patched login flow"), selected.map { it.content })
    }

    @Test
    fun coarseSelectMatchesToolName() {
        val messages = listOf(
            msg("assistant", "done", toolName = "ReadSessionContext"),
            msg("user", "hello"),
        )
        val selected = SessionContextSynthesis.coarseSelect(messages, "sessioncontext")
        assertEquals(1, selected.size)
        assertEquals("ReadSessionContext", selected[0].toolName)
    }

    @Test
    fun coarseSelectFallsBackToTailWhenNoHit() {
        val messages = listOf(msg("user", "alpha"), msg("assistant", "beta"))
        assertEquals(messages, SessionContextSynthesis.coarseSelect(messages, "zzz"))
        assertEquals(messages, SessionContextSynthesis.coarseSelect(messages, ""))
    }

    @Test
    fun coarseSelectKeepsOnlyTailForty() {
        val messages = (1..60).map { msg("user", "m$it") }
        val selected = SessionContextSynthesis.coarseSelect(messages, "")
        assertEquals(40, selected.size)
        assertEquals("m21", selected.first().content)
        assertEquals("m60", selected.last().content)
    }

    @Test
    fun renderTranscriptClipsEachMessage() {
        val long = "x".repeat(2_000)
        val rendered = SessionContextSynthesis.renderTranscript(listOf(msg("user", long)))
        assertEquals("[user] " + "x".repeat(800), rendered)
    }

    @Test
    fun handoffPromptEmbedsQueryTranscriptAndSchema() {
        val prompt = SessionContextSynthesis.handoffPrompt("continue login fix", "[user] hello")
        assertTrue(prompt.contains("continue login fix"))
        assertTrue(prompt.contains("[user] hello"))
        assertTrue(prompt.contains("\"objective\""))
        assertTrue(prompt.contains("\"decisions\""))
        assertTrue(prompt.contains("\"filesTouched\""))
        assertTrue(prompt.contains("\"openQuestions\""))
        assertTrue(prompt.contains("\"nextSteps\""))
    }

    @Test
    fun parseCapsuleReadsPlainJson() {
        val capsule = SessionContextSynthesis.parseCapsule(
            """{"objective":"ship login","decisions":["use oauth"],"filesTouched":["Login.kt"],"openQuestions":[],"nextSteps":["write tests"]}""",
        )!!
        assertEquals("ship login", capsule.objective)
        assertEquals(listOf("use oauth"), capsule.decisions)
        assertEquals(listOf("Login.kt"), capsule.filesTouched)
        assertEquals(emptyList<String>(), capsule.openQuestions)
        assertEquals(listOf("write tests"), capsule.nextSteps)
    }

    @Test
    fun parseCapsuleSurvivesFencesAndProse() {
        val raw = "Here you go:\n```json\n{\"objective\":\"x\",\"nextSteps\":[\"a\",\" \"]}\n```\nDone."
        val capsule = SessionContextSynthesis.parseCapsule(raw)!!
        assertEquals("x", capsule.objective)
        assertEquals(listOf("a"), capsule.nextSteps)
    }

    @Test
    fun parseCapsuleRejectsGarbageAndEmpty() {
        assertNull(SessionContextSynthesis.parseCapsule("no json here"))
        assertNull(SessionContextSynthesis.parseCapsule("{broken"))
        assertNull(SessionContextSynthesis.parseCapsule("{}"))
        assertNull(SessionContextSynthesis.parseCapsule("""{"objective":"","decisions":[]}"""))
    }

    @Test
    fun handoffUsesModelCapsuleWhenAvailable() = runBlocking {
        var calls = 0
        val result = SessionContextSynthesis.handoff(
            query = "resume",
            messages = listOf(msg("user", "start task"), msg("assistant", "half done")),
            maxTokens = 2_000,
            model = { prompt ->
                calls++
                assertTrue(prompt.contains("start task"))
                """{"objective":"finish task","nextSteps":["run tests"]}"""
            },
        )
        assertEquals(1, calls)
        assertFalse(result.fallback)
        assertTrue(result.content.contains("\"objective\":\"finish task\""))
        assertFalse(result.content.contains(SessionContextSynthesis.FALLBACK_NOTE))
    }

    @Test
    fun handoffFallsBackWhenModelUnavailable() = runBlocking {
        val result = SessionContextSynthesis.handoff(
            query = "",
            messages = listOf(msg("user", "hello world")),
            maxTokens = 2_000,
            model = { null },
        )
        assertTrue(result.fallback)
        assertTrue(result.content.startsWith(SessionContextSynthesis.FALLBACK_NOTE))
        assertTrue(result.content.contains("[user] hello world"))
    }

    @Test
    fun handoffFallsBackWhenModelThrowsOrReturnsGarbage() = runBlocking {
        val thrown = SessionContextSynthesis.handoff(
            query = "",
            messages = listOf(msg("user", "payload")),
            maxTokens = 2_000,
            model = { error("boom") },
        )
        assertTrue(thrown.fallback)
        assertTrue(thrown.content.contains("payload"))

        val garbage = SessionContextSynthesis.handoff(
            query = "",
            messages = listOf(msg("user", "payload")),
            maxTokens = 2_000,
            model = { "I cannot comply" },
        )
        assertTrue(garbage.fallback)
    }

    @Test
    fun handoffRespectsBudgetAndFlagsTruncation() = runBlocking {
        val result = SessionContextSynthesis.handoff(
            query = "",
            messages = listOf(msg("user", "y".repeat(2_000))),
            maxTokens = 10,
            model = { null },
        )
        assertEquals(40, result.content.length)
        assertTrue(result.truncated)
    }

    @Test
    fun relevantSummarizesWithModelByDefault() = runBlocking {
        val result = SessionContextSynthesis.relevant(
            query = "login",
            messages = listOf(msg("user", "login broke"), msg("assistant", "chatter")),
            maxTokens = 2_000,
            model = { "summary: login broke" },
        )
        assertFalse(result.fallback)
        assertEquals("summary: login broke", result.content)
    }

    @Test
    fun relevantSkipsModelWhenSummarizeDisabled() = runBlocking {
        var calls = 0
        val result = SessionContextSynthesis.relevant(
            query = "",
            messages = listOf(msg("user", "alpha"), msg("assistant", "beta")),
            maxTokens = 2_000,
            summarize = false,
            model = { calls++; "should not happen" },
        )
        assertEquals(0, calls)
        assertFalse(result.fallback)
        assertEquals("[user] alpha\n[assistant] beta", result.content)
    }

    @Test
    fun relevantFallsBackWithNoteWhenModelFails() = runBlocking {
        val result = SessionContextSynthesis.relevant(
            query = "",
            messages = listOf(msg("user", "alpha")),
            maxTokens = 2_000,
            model = { null },
        )
        assertTrue(result.fallback)
        assertTrue(result.content.startsWith(SessionContextSynthesis.FALLBACK_NOTE))
        assertTrue(result.content.contains("[user] alpha"))
    }

    @Test
    fun relevantClipsSummaryToBudget() = runBlocking {
        val result = SessionContextSynthesis.relevant(
            query = "",
            messages = listOf(msg("user", "alpha")),
            maxTokens = 5,
            model = { "z".repeat(500) },
        )
        assertEquals(20, result.content.length)
        assertTrue(result.truncated)
    }

    @Test
    fun emptyTranscriptSkipsModel() = runBlocking {
        var calls = 0
        val handoff = SessionContextSynthesis.handoff("", emptyList(), 100) { calls++; "x" }
        val relevant = SessionContextSynthesis.relevant("", emptyList(), 100) { calls++; "x" }
        assertEquals(0, calls)
        assertEquals("", handoff.content)
        assertEquals("", relevant.content)
        assertFalse(handoff.fallback)
        assertFalse(relevant.fallback)
    }
}