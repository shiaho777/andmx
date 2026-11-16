package com.andmx.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsReplCoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun timeoutDefaultsToTenSecondsAndClampsAtThirty() {
        assertEquals(10_000L, JsReplCore.clampTimeout(null))
        assertEquals(30_000L, JsReplCore.clampTimeout(120_000L))
        assertEquals(1L, JsReplCore.clampTimeout(0L))
        assertEquals(1L, JsReplCore.clampTimeout(-5L))
        assertEquals(5_000L, JsReplCore.clampTimeout(5_000L))
    }

    @Test
    fun withLogsMergesBridgeLinesIntoResultPayload() {
        val merged = json.parseToJsonElement(
            JsReplCore.withLogs("""{"result":"42"}""", listOf("hello", "world")),
        ).jsonObject
        assertEquals("42", merged["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("hello", "world"), merged["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun withLogsMergesBridgeLinesIntoErrorPayload() {
        val merged = json.parseToJsonElement(
            JsReplCore.withLogs("""{"error":{"name":"TypeError","message":"boom","stack":"at x"}}""", listOf("trace")),
        ).jsonObject
        assertEquals("TypeError", merged["error"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("trace"), merged["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun withLogsReplacesStaleEmptyLogs() {
        val merged = json.parseToJsonElement(
            JsReplCore.withLogs("""{"result":"ok","logs":[]}""", listOf("fresh")),
        ).jsonObject
        assertEquals(listOf("fresh"), merged["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun malformedPayloadBecomesErrorWithLogs() {
        val merged = json.parseToJsonElement(
            JsReplCore.withLogs("not json", listOf("a")),
        ).jsonObject
        assertEquals("ReplError", merged["error"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("a"), merged["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun asyncOutcomeDoneProducesResultAndLogs() {
        val out = JsReplCore.asyncOutcome("""{"state":"done","value":"7"}""", listOf("late log"))
        val obj = json.parseToJsonElement(out!!).jsonObject
        assertEquals("7", obj["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("late log"), obj["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun asyncOutcomeErrorKeepsNameMessageStack() {
        val out = JsReplCore.asyncOutcome(
            """{"state":"error","name":"RangeError","message":"bad","stack":"at f"}""",
            listOf("before"),
        )
        val err = json.parseToJsonElement(out!!).jsonObject["error"]!!.jsonObject
        assertEquals("RangeError", err["name"]!!.jsonPrimitive.content)
        assertEquals("bad", err["message"]!!.jsonPrimitive.content)
        assertEquals("at f", err["stack"]!!.jsonPrimitive.content)
    }

    @Test
    fun asyncOutcomePendingOrNullReturnsNull() {
        assertNull(JsReplCore.asyncOutcome("""{"state":"pending"}""", emptyList()))
        assertNull(JsReplCore.asyncOutcome("garbage", emptyList()))
    }

    @Test
    fun isAsyncDetectsMarkerOnly() {
        assertTrue(JsReplCore.isAsync("""{"async":true}"""))
        assertFalse(JsReplCore.isAsync("""{"result":"1"}"""))
        assertFalse(JsReplCore.isAsync("""{"async":false}"""))
        assertFalse(JsReplCore.isAsync("garbage"))
    }

    @Test
    fun resultPayloadQuotesValueSafely() {
        val obj = json.parseToJsonElement(JsReplCore.resultPayload("he said \"hi\"\n", emptyList())).jsonObject
        assertEquals("he said \"hi\"\n", obj["result"]!!.jsonPrimitive.content)
        assertTrue(obj["logs"]!!.jsonArray.isEmpty())
    }

    @Test
    fun errorPayloadAlwaysCarriesStackField() {
        val obj = json.parseToJsonElement(
            JsReplCore.errorPayload("TimeoutError", "timed out", "", listOf("x")),
        ).jsonObject
        assertEquals("", obj["error"]!!.jsonObject["stack"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("result"))
    }
}