package com.andmx.agent.workflow.dynamic

import com.andmx.agent.zcode.WORKFLOW_QUESTION_UNAVAILABLE
import com.andmx.agent.zcode.WorkflowTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEscalationRegistryTest {

    private fun question(qid: String, runId: String = "dwfrun-abc") = PendingWorkflowQuestion(
        qid = qid,
        runId = runId,
        actor = "actor#1@1",
        actorName = "poet",
        question = "Which file?",
        context = null,
        askedAt = 1L,
    )

    @Test
    fun mintsShortQidWithPerRunSequence() {
        val registry = WorkflowEscalationRegistry()
        assertEquals("dwfq-01234567-1", registry.mintQuestionId("dwfrun-0123456789abcdef"))
        assertEquals("dwfq-01234567-2", registry.mintQuestionId("dwfrun-0123456789abcdef"))
        assertEquals("dwfq-other-1", registry.mintQuestionId("other"))
    }

    @Test
    fun fallsBackToLongerFragmentWhenShortIsTaken() {
        val registry = WorkflowEscalationRegistry()
        registry.park(question("dwfq-01234567-1"), {})
        assertEquals("dwfq-0123456789abcdef-1", registry.mintQuestionId("dwfrun-0123456789abcdefXYZ"))
        registry.park(question("dwfq-0123456789abcdef-2"), {})
        registry.park(question("dwfq-01234567-2"), {})
        assertEquals("dwfq-0123456789abcdefXYZ-2", registry.mintQuestionId("dwfrun-0123456789abcdefXYZ"))
    }

    @Test
    fun sanitizeEscapesUnderscoreBeforeMappingSpecials() {
        assertEquals("my__actor_1", sanitizeIdSegment("my_actor#1"))
        assertEquals("my_actor_1", sanitizeIdSegment("my#actor#1"))
    }

    @Test
    fun resolveDeliversAnswerAndRetiresQid() {
        val registry = WorkflowEscalationRegistry()
        var delivered: String? = null
        registry.park(question("q1")) { delivered = it }
        val result = registry.resolve("q1", "use main.kt")
        assertEquals(ResolveQuestionResult.Delivered("q1"), result)
        assertEquals("use main.kt", delivered)
        assertTrue(registry.pendingFor("dwfrun-abc").isEmpty())
        assertTrue(registry.isTaken("q1"))
    }

    @Test
    fun resolveRejectsAlreadyResolvedWithdrawnAndUnknown() {
        val registry = WorkflowEscalationRegistry()
        registry.park(question("q1"), {})
        registry.resolve("q1", "a")
        assertEquals("already_resolved", (registry.resolve("q1", "b") as ResolveQuestionResult.Rejected).reason)

        registry.park(question("q2"), {})
        assertTrue(registry.withdraw("q2"))
        assertFalse(registry.withdraw("q2"))
        assertEquals("run_not_in_flight", (registry.resolve("q2", "b") as ResolveQuestionResult.Rejected).reason)

        assertEquals("unknown_question", (registry.resolve("nope", "b") as ResolveQuestionResult.Rejected).reason)
    }

    @Test
    fun withdrawDoesNotDowngradeResolvedQid() {
        val registry = WorkflowEscalationRegistry()
        registry.park(question("q1"), {})
        registry.resolve("q1", "a")
        assertFalse(registry.withdraw("q1"))
        assertEquals("already_resolved", (registry.resolve("q1", "b") as ResolveQuestionResult.Rejected).reason)
    }

    @Test
    fun pendingForFiltersByRun() {
        val registry = WorkflowEscalationRegistry()
        registry.park(question("q1", runId = "r1"), {})
        registry.park(question("q2", runId = "r2"), {})
        assertEquals(listOf("q1"), registry.pendingFor("r1").map { it.qid })
    }

    @Test
    fun retiredHistoryIsBounded() {
        val registry = WorkflowEscalationRegistry(retiredLimit = 2)
        listOf("q1", "q2", "q3").forEach {
            registry.park(question(it), {})
            registry.resolve(it, "a")
        }
        assertFalse(registry.isTaken("q1"))
        assertEquals("unknown_question", (registry.resolve("q1", "a") as ResolveQuestionResult.Rejected).reason)
        assertTrue(registry.isTaken("q3"))
    }

    @Test
    fun notificationCarriesQidAndEscapesXml() {
        val text = formatWorkflowEscalationNotification(
            runLabel = "build <x>",
            runId = "dwfrun-1",
            qid = "dwfq-1-1",
            actor = "poet",
            question = "a & b?",
            context = null,
        )
        assertTrue(text.startsWith("[SYSTEM NOTIFICATION - NOT USER INPUT]"))
        assertTrue(text.contains("<run>build &lt;x&gt;</run>"))
        assertTrue(text.contains("<question>a &amp; b?</question>"))
        assertFalse(text.contains("<context>"))
        assertTrue(text.contains("question_id=\"dwfq-1-1\""))
    }

    @Test
    fun resolveToolDeliversThroughRegistry() = runTest {
        val registry = WorkflowEscalationRegistry()
        var delivered: String? = null
        registry.park(question("q1")) { delivered = it }
        val tools = WorkflowTools(escalations = registry)
        val ok = tools.Resolve().execute(buildJsonObject {
            put("question_id", "q1")
            put("answer", "yes")
        })
        assertFalse(ok.isError)
        assertTrue(ok.output.contains("Answer delivered for question q1."))
        assertEquals("yes", delivered)

        val again = tools.Resolve().execute(buildJsonObject {
            put("question_id", "q1")
            put("answer", "yes")
        })
        assertTrue(again.isError)
        assertTrue(again.output.contains("already answered"))
    }

    @Test
    fun resolveToolWithoutRegistryIsUnavailable() = runTest {
        val r = WorkflowTools().Resolve().execute(buildJsonObject {
            put("question_id", "q1")
            put("answer", "yes")
        })
        assertTrue(r.isError)
        assertEquals(WORKFLOW_QUESTION_UNAVAILABLE, r.output)
    }
}
