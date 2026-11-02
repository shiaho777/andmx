package com.andmx.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ModelCallTracePersistenceTest {

    private lateinit var traceFile: File
    private lateinit var store: ModelCallTrace.Store

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Application>()
        traceFile = Files.createTempFile("model-traces", ".json").toFile().apply { delete() }
        store = ModelCallTrace.Store().apply { install(traceFile) }
    }

    @After
    fun tearDown() {
        store.resetInMemory()
        traceFile.delete()
    }

    private fun record(model: String = "gpt-test") {
        store.record(
            source = ModelCallTrace.Source.MAIN,
            model = model,
            inputTokens = 100,
            cachedInputTokens = 10,
            outputTokens = 20,
            finish = ModelCallTrace.Finish.STOP,
            inputPreview = "hello",
            outputPreview = "world",
            durationMs = 123,
        )
    }

    private fun decode(): List<ModelCallTrace.Call> =
        ModelCallTrace.codec.decodeFromString(traceFile.readText())

    @Test
    fun recordFlushAndReloadKeepsTheRing() = runBlocking {
        repeat(3) { record("m$it") }
        store.flush()

        assertTrue(traceFile.isFile)
        val persisted = decode()
        assertEquals(3, persisted.size)
        assertEquals("m2", persisted.last().model)
        assertEquals(120, persisted.last().totalTokens)
        assertEquals(3L, persisted.last().seq)

        val restored = ModelCallTrace.Store().apply { install(traceFile) }
        assertEquals(3, restored.calls.value.size)
        assertEquals("m2", restored.calls.value.last().model)
    }

    @Test
    fun ringIsCappedAtFiftyAndClearEmptiesTheFile() = runBlocking {
        repeat(ModelCallTrace.MAX_CALLS + 5) { record() }
        store.flush()
        assertEquals(ModelCallTrace.MAX_CALLS, store.calls.value.size)
        assertEquals(ModelCallTrace.MAX_CALLS, decode().size)

        store.clear()
        store.flush()
        assertTrue(store.calls.value.isEmpty())
        assertTrue(decode().isEmpty())
    }

    @Test
    fun corruptTraceFileFailsClosedWithoutCrashing() = runBlocking {
        traceFile.writeText("{not-json")
        val restored = ModelCallTrace.Store().apply { install(traceFile) }
        assertTrue(restored.calls.value.isEmpty())

        restored.record(
            source = ModelCallTrace.Source.MAIN,
            model = "m",
            inputTokens = 1,
            cachedInputTokens = 0,
            outputTokens = 1,
            finish = ModelCallTrace.Finish.STOP,
            inputPreview = "",
            outputPreview = "",
            durationMs = 0,
        )
        restored.flush()
        assertEquals(1L, restored.calls.value.single().seq)
        assertEquals(1, decode().size)
    }
}
