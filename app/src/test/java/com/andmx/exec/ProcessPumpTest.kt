package com.andmx.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProcessPumpTest {

    @Test
    fun largeStderrDoesNotDeadlockAndBothStreamsAreComplete() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<PumpedProcess> {
                runProcessPumped(
                    argv = listOf("sh", "-c", "yes x | head -c 200000 >&2; echo done"),
                    timeoutMs = 10_000L,
                )
            }
            val result = future.get(5, TimeUnit.SECONDS)
            assertFalse(result.timedOut)
            assertEquals(0, result.exitCode)
            assertEquals("done\n", result.stdout)
            assertEquals(200_000, result.stderr.length)
            assertTrue(result.stderr.all { it == 'x' || it == '\n' })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun largeStdoutAndStderrInterleavedAreBothCaptured() {
        val result = runProcessPumped(
            argv = listOf(
                "sh", "-c",
                "yes o | head -c 150000; yes e | head -c 150000 >&2",
            ),
            timeoutMs = 10_000L,
        )
        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals(150_000, result.stdout.length)
        assertEquals(150_000, result.stderr.length)
    }

    @Test
    fun redirectErrorStreamMergesStderrIntoStdout() {
        val result = runProcessPumped(
            argv = listOf("sh", "-c", "echo out; echo err >&2"),
            redirectErrorStream = true,
            timeoutMs = 5_000L,
        )
        assertEquals(0, result.exitCode)
        assertEquals("", result.stderr)
        assertTrue(result.stdout.contains("out"))
        assertTrue(result.stdout.contains("err"))
    }

    @Test
    fun sleepingCommandIsKilledOnTimeoutWithExit124() {
        val started = System.nanoTime()
        val result = runProcessPumped(
            argv = listOf("sh", "-c", "sleep 30"),
            timeoutMs = 500L,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.timedOut)
        assertEquals(124, result.exitCode)
        assertTrue("expected kill within 5s, took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun timeoutKeepsOutputProducedBeforeTheKill() {
        val result = runProcessPumped(
            argv = listOf("sh", "-c", "echo started; sleep 30"),
            timeoutMs = 1_000L,
        )
        assertTrue(result.timedOut)
        assertEquals(124, result.exitCode)
        assertTrue(result.stdout.contains("started"))
    }

    @Test
    fun nonZeroExitIsPreserved() {
        val result = runProcessPumped(
            argv = listOf("sh", "-c", "echo boom >&2; exit 3"),
            timeoutMs = 5_000L,
        )
        assertFalse(result.timedOut)
        assertEquals(3, result.exitCode)
        assertEquals("boom\n", result.stderr)
    }

    @Test
    fun stdinIsDeliveredToTheProcess() {
        val result = runProcessPumped(
            argv = listOf("sh", "-c", "cat"),
            stdin = "hello stdin",
            timeoutMs = 5_000L,
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello stdin", result.stdout)
    }

    @Test
    fun envOverridesAreVisibleToTheProcess() {
        val result = runProcessPumped(
            argv = listOf("sh", "-c", "printf '%s' \"\$ANDMX_PUMP_TEST\""),
            timeoutMs = 5_000L,
            env = mapOf("ANDMX_PUMP_TEST" to "pumped"),
        )
        assertEquals(0, result.exitCode)
        assertEquals("pumped", result.stdout)
    }
}
