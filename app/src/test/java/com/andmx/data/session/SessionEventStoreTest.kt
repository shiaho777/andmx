package com.andmx.data.session

import com.andmx.llm.ApiMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventStoreTest {
    @Test
    fun transientEventsStayOutOfColdStorageAndSequencesStayContiguous() {
        val store = store()
        store.append(1, "trace", SessionEventType.USER_MESSAGE, payload("text" to "hello"))
        val streaming = store.append(1, "trace", SessionEventType.MODEL_STREAMING, payload("text" to "tok"))
        store.append(1, "trace", SessionEventType.ASSISTANT_MESSAGE, payload("text" to "world"))
        assertEquals(0L, streaming.sequenceNumber)
        val durable = store.durable(1)
        assertEquals(listOf(1L, 2L), durable.map { it.sequenceNumber })
        assertTrue(durable.none { it.type == SessionEventType.MODEL_STREAMING })
        assertFalse(store.file(1).readText().contains("model_streaming"))
    }

    @Test
    fun replayingTwiceYieldsTheSameProjection() {
        val store = store()
        store.append(7, "trace", SessionEventType.USER_MESSAGE, payload("text" to "ping"))
        store.append(7, "trace", SessionEventType.ASSISTANT_MESSAGE, payload("text" to "pong"))
        val first = store.replayMessages(7)
        val second = SessionEventStore(store.file(7).parentFile).replayMessages(7)
        assertEquals(first, second)
        assertEquals(listOf("user", "assistant"), first.map { it.role })
        assertEquals(listOf("ping", "pong"), first.map { it.content })
    }

    @Test
    fun compactBoundaryHidesEarlierToolOutputFromTheModelAndStaysVisibleToTheUser() {
        val store = store()
        store.append(3, "trace", SessionEventType.USER_MESSAGE, payload("text" to "before"))
        store.append(3, "trace", SessionEventType.TOOL_CALL_RESULT, payload("id" to "t1", "name" to "Bash", "output" to "SECRET_TOOL_OUTPUT"))
        store.append(3, "trace", SessionEventType.COMPACT_BOUNDARY, payload("summary" to "summarized work"))
        store.append(3, "trace", SessionEventType.USER_MESSAGE, payload("text" to "after"))
        val model = store.replayMessages(3).map { it.content }
        assertEquals(listOf("summarized work", "after"), model)
        assertTrue(model.none { it?.contains("SECRET_TOOL_OUTPUT") == true })
        val user = store.userView(3).map { it.content }
        assertTrue(user.contains("before"))
        assertTrue(user.contains("SECRET_TOOL_OUTPUT"))
        assertTrue(user.contains("summarized work"))
        assertTrue(user.contains("after"))
    }

    @Test
    fun rewindHidesEventsAfterTheCheckpointAndWritesRewindTriggered() {
        val store = store()
        store.append(4, "trace", SessionEventType.USER_MESSAGE, payload("text" to "keep"))
        val checkpoint = store.read(4).last().sequenceNumber
        store.append(4, "trace", SessionEventType.USER_MESSAGE, payload("text" to "drop"))
        store.append(4, "trace", SessionEventType.TOOL_CALL_RESULT, payload("output" to "later-tool"))
        val rewind = store.rewind(4, "trace", checkpoint)
        store.append(4, "trace", SessionEventType.USER_MESSAGE, payload("text" to "continue"))
        assertEquals(SessionEventType.REWIND_TRIGGERED, rewind.type)
        val texts = store.userView(4).map { it.content }
        assertTrue(texts.contains("keep"))
        assertTrue(texts.contains("rewind_triggered"))
        assertTrue(texts.contains("continue"))
        assertFalse(texts.contains("drop"))
        assertFalse(texts.contains("later-tool"))
        val again = SessionEventStore(store.file(4).parentFile).userView(4).map { it.content }
        assertEquals(texts, again)
    }

    @Test
    fun retentionWindowSealsATurnAndEvictsItOnTheNextTurn() {
        val policy = createSessionEventRetentionPolicy("turn-window")
        assertEquals(emptyList<String>(), policy.onAppend(SessionEventType.TURN_STARTED, "t1", 0))
        assertEquals(emptyList<String>(), policy.onAppend(SessionEventType.MODEL_STREAMING, "t1", 0))
        assertEquals(emptyList<String>(), policy.onAppend(SessionEventType.TURN_COMPLETE, "t1", 0))
        assertEquals(listOf("t1"), policy.onAppend(SessionEventType.TURN_STARTED, "t2", 1))
        assertEquals(emptyList<String>(), policy.onAppend(SessionEventType.REWIND_TRIGGERED, "t2", 2))
        assertEquals(emptyList<String>(), policy.collectExpired(500, 120_000))
        val sealed = createSessionEventRetentionPolicy("turn-window")
        sealed.onAppend(SessionEventType.TURN_STARTED, "child", 0)
        sealed.onAppend(SessionEventType.TURN_COMPLETE, "child", 10_000)
        assertEquals(emptyList<String>(), sealed.collectExpired(60_000, 120_000))
        assertEquals(listOf("child"), sealed.collectExpired(130_000, 120_000))
        assertEquals(emptyList<String>(), sealed.collectExpired(200_000, 120_000))
        val unbounded = createSessionEventRetentionPolicy("unbounded")
        unbounded.onAppend(SessionEventType.TURN_STARTED, "t1", 0)
        unbounded.onAppend(SessionEventType.TURN_COMPLETE, "t1", 1)
        assertEquals(emptyList<String>(), unbounded.onAppend(SessionEventType.TURN_STARTED, "t2", 2))
        assertEquals(emptyList<String>(), unbounded.collectExpired(1_000_000, 0))
        assertTrue(isTransientSessionEvent(SessionEventType.MODEL_STREAMING))
        assertFalse(isTransientSessionEvent(SessionEventType.MODEL_COMPLETE))
    }

    @Test
    fun importIsReadOnceAndReplayMatches() {
        val store = store()
        store.importMessages(
            9,
            "trace",
            listOf(
                ApiMessage(role = "system", content = "hidden"),
                ApiMessage(role = "user", content = "hi"),
                ApiMessage(role = "assistant", content = "yo"),
            ),
        )
        store.importMessages(9, "trace", listOf(ApiMessage(role = "user", content = "again")))
        val replay = store.replayMessages(9)
        assertEquals(listOf("hi", "yo"), replay.map { it.content })
    }

    private fun store(): SessionEventStore {
        val dir = File.createTempFile("andmx-events", "").apply {
            delete()
            mkdirs()
        }
        return SessionEventStore(dir)
    }

    private fun payload(vararg pairs: Pair<String, String>) = kotlinx.serialization.json.buildJsonObject {
        pairs.forEach { (key, value) -> put(key, kotlinx.serialization.json.JsonPrimitive(value)) }
    }
}
