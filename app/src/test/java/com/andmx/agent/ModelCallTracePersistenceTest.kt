package com.andmx.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ModelCallTracePersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ModelCallTrace.resetForTests(context)
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
        ModelCallTrace.install(context)
        repeat(3) { record("m$it") }
        ModelCallTrace.flush()

        assertTrue(File(context.filesDir, "model-traces.json").isFile)
        assertEquals(3, ModelCallTrace.calls.value.size)
        assertEquals(3L, ModelCallTrace.calls.value.last().seq)

        ModelCallTrace.resetInMemory()
        ModelCallTrace.install(context)

        val restored = ModelCallTrace.calls.value
        assertEquals(3, restored.size)
        assertEquals("m2", restored.last().model)
        assertEquals(120, restored.last().totalTokens)

        record("after-restart")
        assertEquals(4L, ModelCallTrace.calls.value.last().seq)
    }

    @Test
    fun ringIsCappedAtFiftyAndClearEmptiesTheFile() = runBlocking {
        ModelCallTrace.install(context)
        repeat(ModelCallTrace.MAX_CALLS + 5) { record() }
        ModelCallTrace.flush()
        assertEquals(ModelCallTrace.MAX_CALLS, ModelCallTrace.calls.value.size)

        ModelCallTrace.clear()
        ModelCallTrace.flush()
        delay(20)
        assertTrue(ModelCallTrace.calls.value.isEmpty())

        ModelCallTrace.resetInMemory()
        ModelCallTrace.install(context)
        assertTrue(ModelCallTrace.calls.value.isEmpty())
    }

    @Test
    fun corruptTraceFileFailsClosedWithoutCrashing() = runBlocking {
        File(context.filesDir, "model-traces.json").writeText("{not-json")
        ModelCallTrace.install(context)

        assertTrue(ModelCallTrace.calls.value.isEmpty())
        record()
        ModelCallTrace.flush()
        assertEquals(1L, ModelCallTrace.calls.value.single().seq)
    }
}
