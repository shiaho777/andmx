package com.andmx.agent

import android.app.Application
import android.content.Context
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

    private lateinit var context: Context
    private lateinit var traceFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        traceFile = Files.createTempFile("model-traces", ".json").toFile().apply { delete() }
        ModelCallTrace.resetForTests(context)
        ModelCallTrace.installFile(traceFile)
    }

    @After
    fun tearDown() {
        ModelCallTrace.resetInMemory()
        traceFile.delete()
    }

    private fun record(model: String = "gpt-test") {
        ModelCallTrace.record(
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

    @Test
    fun recordFlushAndReloadKeepsTheRing() = runBlocking {
        repeat(3) { record("m$it") }
        ModelCallTrace.flush()

        assertTrue(traceFile.isFile)
        assertEquals(3, ModelCallTrace.calls.value.size)
        assertEquals(3L, ModelCallTrace.calls.value.last().seq)

        ModelCallTrace.resetInMemory()
        ModelCallTrace.installFile(traceFile)

        val restored = ModelCallTrace.calls.value
        assertEquals(3, restored.size)
        assertEquals("m2", restored.last().model)
        assertEquals(120, restored.last().totalTokens)

        record("after-restart")
        assertEquals(4L, ModelCallTrace.calls.value.last().seq)
    }

    @Test
    fun ringIsCappedAtFiftyAndClearEmptiesTheFile() = runBlocking {
        repeat(ModelCallTrace.MAX_CALLS + 5) { record() }
        ModelCallTrace.flush()
        assertEquals(ModelCallTrace.MAX_CALLS, ModelCallTrace.calls.value.size)

        ModelCallTrace.clear()
        ModelCallTrace.flush()
        assertTrue(ModelCallTrace.calls.value.isEmpty())

        ModelCallTrace.resetInMemory()
        ModelCallTrace.installFile(traceFile)
        assertTrue(ModelCallTrace.calls.value.isEmpty())
    }

    @Test
    fun corruptTraceFileFailsClosedWithoutCrashing() = runBlocking {
        traceFile.writeText("{not-json")
        ModelCallTrace.resetInMemory()
        ModelCallTrace.installFile(traceFile)

        assertTrue(ModelCallTrace.calls.value.isEmpty())
        record()
        ModelCallTrace.flush()
        assertEquals(1L, ModelCallTrace.calls.value.single().seq)
    }
}
