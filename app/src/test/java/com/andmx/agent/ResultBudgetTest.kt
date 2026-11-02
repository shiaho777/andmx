package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultBudgetTest {
    @Test
    fun shortOutputIsUnchanged() {
        assertEquals("ok", ResultBudget.apply("ok", 100))
    }

    @Test
    fun truncationUsesResultBudgetSuffix() {
        val text = "x".repeat(2_000)
        val out = ResultBudget.apply(text, 400)
        assertTrue(out.contains("Tool output truncated by resultBudget"))
        assertTrue(out.contains("originalBytes=2000"))
        assertTrue(out.contains("maxModelBytes=400"))
        assertTrue(out.contains("strategy=truncate"))
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 400)
    }

    @Test
    fun emptyToolOutputUsesCompletedPlaceholder() {
        assertEquals("(Read completed with no output)", ResultBudget.emptyOutput("Read"))
    }

    @Test
    fun persistedEnvelopeMatchesUpstreamTags() {
        val body = ResultBudget.persistedEnvelope("hello\nworld", 11, "/tmp/out.txt")
        assertTrue(body.startsWith("<persisted-output>"))
        assertTrue(body.contains("Output too large (11 B). Full output saved to: /tmp/out.txt"))
        assertTrue(body.contains("Preview (first 2 KB):"))
        assertTrue(body.contains("hello\nworld"))
        assertTrue(body.trimEnd().endsWith("</persisted-output>"))
    }
}
