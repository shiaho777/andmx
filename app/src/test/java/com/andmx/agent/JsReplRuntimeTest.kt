package com.andmx.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class JsReplRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private var engineAvailable = false

    @Before
    fun probeEngine() {
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        val thread = Thread {
            result.complete(
                runCatching {
                    runBlocking {
                        withTimeout(8_000) {
                            val out = JsReplRuntime.eval("probe-session", context, "1+1", 3_000)
                            json.parseToJsonElement(out).jsonObject["result"]?.jsonPrimitive?.content == "2"
                        }
                    }
                }.getOrDefault(false),
            )
        }
        thread.isDaemon = true
        thread.start()
        engineAvailable = runCatching {
            result.get(12, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false)
        JsReplRuntime.dispose("probe-session")
    }

    @After
    fun tearDown() {
        JsReplRuntime.dispose("test-session")
        JsReplRuntime.dispose("test-session-2")
    }

    @Test
    fun bridgeCollectsAndDrainsLines() {
        val bridge = JsReplRuntime.LogBridge()
        bridge.log("first")
        bridge.log("second")
        assertEquals(listOf("first", "second"), bridge.drain())
        assertTrue(bridge.drain().isEmpty())
    }

    @Test
    fun evalCapturesConsoleLogs() = runBlocking {
        assumeTrue(engineAvailable)
        val out = JsReplRuntime.eval("test-session", context, "console.log('hi'); 40+2", 10_000)
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("42", obj["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("hi"), obj["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun windowStatePersistsAcrossCalls() = runBlocking {
        assumeTrue(engineAvailable)
        JsReplRuntime.eval("test-session", context, "window.__kept = 123; function addOne(x){return x+1}; 'ok'", 10_000)
        val out = JsReplRuntime.eval("test-session", context, "addOne(window.__kept)", 10_000)
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("124", obj["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun resetClearsWindowState() = runBlocking {
        assumeTrue(engineAvailable)
        JsReplRuntime.eval("test-session", context, "window.__kept = 123; 'ok'", 10_000)
        JsReplRuntime.reset("test-session")
        val out = JsReplRuntime.eval("test-session", context, "String(window.__kept)", 10_000)
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("undefined", obj["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun sessionsAreIsolated() = runBlocking {
        assumeTrue(engineAvailable)
        JsReplRuntime.eval("test-session", context, "window.__iso = 'a'; 'ok'", 10_000)
        val out = JsReplRuntime.eval("test-session-2", context, "String(window.__iso)", 10_000)
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("undefined", obj["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun exceptionsReturnNameMessageStackAndLogs() = runBlocking {
        assumeTrue(engineAvailable)
        val out = JsReplRuntime.eval("test-session", context, "console.warn('before'); throw new TypeError('boom')", 10_000)
        val obj = json.parseToJsonElement(out).jsonObject
        val err = obj["error"]!!.jsonObject
        assertEquals("TypeError", err["name"]!!.jsonPrimitive.content)
        assertEquals("boom", err["message"]!!.jsonPrimitive.content)
        assertTrue(err.containsKey("stack"))
        assertEquals(listOf("before"), obj["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun circularObjectsSerializeSafely() = runBlocking {
        assumeTrue(engineAvailable)
        val out = JsReplRuntime.eval(
            "test-session",
            context,
            "var a = {}; a.self = a; a",
            10_000,
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertTrue(obj["result"]!!.jsonPrimitive.content.contains("[Circular]"))
    }

    @Test
    fun promiseResultAndLogsAreCaptured() = runBlocking {
        assumeTrue(engineAvailable)
        val out = JsReplRuntime.eval(
            "test-session",
            context,
            "console.log('sync'); Promise.resolve().then(function(){console.log('micro'); return 7})",
            10_000,
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("7", obj["result"]!!.jsonPrimitive.content)
        val logs = obj["logs"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(logs.contains("sync"))
        assertTrue(logs.contains("micro"))
    }

    @Test
    fun timerLogsAreCapturedWithinGrace() = runBlocking {
        assumeTrue(engineAvailable)
        val out = JsReplRuntime.eval(
            "test-session",
            context,
            "setTimeout(function(){console.log('timer fired')}, 50); 'scheduled'",
            10_000,
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("scheduled", obj["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("timer fired"), obj["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}