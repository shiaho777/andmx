package com.andmx.agent.workflow.dynamic

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetEvalResultTest {

    @Test
    fun completedCarriesArtifactAndLogs() {
        val outcome = decodeSnippetResult(
            """{"kind":"completed","artifact":{"n":2},"logs":["a","b"],"logsTruncated":true}""",
        )
        outcome as SnippetEvalOutcome.Completed
        assertEquals(buildJsonObject { put("n", 2) }, outcome.artifact)
        assertEquals(listOf("a", "b"), outcome.logs)
        assertTrue(outcome.logsTruncated)
    }

    @Test
    fun completedWithoutReturnValueHasNoArtifact() {
        val outcome = decodeSnippetResult("""{"kind":"completed","logs":[],"logsTruncated":false}""")
        outcome as SnippetEvalOutcome.Completed
        assertNull(outcome.artifact)
        assertFalse(outcome.logsTruncated)
    }

    @Test
    fun failedCarriesErrorCodeAndMessage() {
        val outcome = decodeSnippetResult(
            """{"kind":"failed","error":{"code":"ScriptError","message":"boom"},"logs":["x"],"logsTruncated":false}""",
        )
        outcome as SnippetEvalOutcome.Failed
        assertEquals("ScriptError", outcome.code)
        assertEquals("boom", outcome.message)
        assertEquals(listOf("x"), outcome.logs)
    }

    @Test
    fun unreadableResultIsDriverError() {
        val outcome = decodeSnippetResult("not json")
        outcome as SnippetEvalOutcome.Failed
        assertEquals("DriverError", outcome.code)
        assertTrue(outcome.logs.isEmpty())
    }

    @Test
    fun serializeMatchesUpstreamRules() {
        assertNull(serializeWorkflowArtifact(null))
        assertEquals("plain", serializeWorkflowArtifact(JsonPrimitive("plain")))
        assertEquals("42", serializeWorkflowArtifact(JsonPrimitive(42)))
        assertEquals("null", serializeWorkflowArtifact(JsonNull))
        assertEquals(
            "{\n  \"a\": [\n    1,\n    2\n  ]\n}",
            serializeWorkflowArtifact(buildJsonObject { put("a", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) }) }),
        )
    }
}
