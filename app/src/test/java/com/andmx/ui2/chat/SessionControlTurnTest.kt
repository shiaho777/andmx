package com.andmx.ui2.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlTurnTest {

    private fun scope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> },
    )

    @Test
    fun beginTurnMarksRunningAndPublishes() {
        val control = SessionControl()
        assertFalse(control.isTurnRunning(1L))
        assertEquals(emptySet<Long>(), control.runningIds.value)

        control.beginTurn(1L)

        assertTrue(control.isTurnRunning(1L))
        assertTrue(control.runningConversations.contains(1L))
        assertEquals(setOf(1L), control.runningIds.value)
    }

    @Test
    fun endTurnClearsRunning() {
        val control = SessionControl()
        control.beginTurn(1L)
        control.beginTurn(2L)

        control.endTurn(1L)

        assertFalse(control.isTurnRunning(1L))
        assertFalse(control.runningConversations.contains(1L))
        assertTrue(control.isTurnRunning(2L))
        assertEquals(setOf(2L), control.runningIds.value)

        control.endTurn(2L)
        assertEquals(emptySet<Long>(), control.runningIds.value)
    }

    @Test
    fun isTurnRunningTrueForActiveRegisteredJob() {
        val control = SessionControl()
        val job = Job()
        control.turnJobs[1L] = job
        assertTrue(control.isTurnRunning(1L))
        assertSame(job, control.activeTurnJob(1L))

        job.complete()
        assertFalse(control.isTurnRunning(1L))
        assertNull(control.activeTurnJob(1L))
    }

    @Test
    fun startTurnJobAcquiresRunsAndCleansUp() = runBlocking {
        val control = SessionControl()
        val gate = CompletableDeferred<Unit>()
        var acquired = 0
        var released = 0
        var ran = false
        val job = control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = { acquired++ },
            release = { released++ },
            block = {
                ran = true
                gate.await()
            },
        )

        assertEquals(1, acquired)
        assertTrue(ran)
        assertTrue(control.isTurnRunning(1L))
        assertEquals(setOf(1L), control.runningIds.value)
        assertSame(job, control.turnJobs[1L])
        assertEquals(0, released)

        gate.complete(Unit)
        job.join()

        assertEquals(1, released)
        assertFalse(control.isTurnRunning(1L))
        assertEquals(emptySet<Long>(), control.runningIds.value)
        assertNull(control.turnJobs[1L])
    }

    @Test
    fun activeTurnJobGuardsConcurrentLaunch() = runBlocking {
        val control = SessionControl()
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val first = control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = {},
            release = {},
            block = {
                runs++
                gate.await()
            },
        )

        val second = control.activeTurnJob(1L) ?: control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = {},
            release = {},
            block = { runs++ },
        )

        assertSame(first, second)
        assertEquals(1, runs)

        gate.complete(Unit)
        first.join()
        assertNull(control.activeTurnJob(1L))

        val third = control.activeTurnJob(1L) ?: control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = {},
            release = {},
            block = { runs++ },
        )
        third.join()
        assertEquals(2, runs)
    }

    @Test
    fun cancelTurnJobReleasesAndClearsState() = runBlocking {
        val control = SessionControl()
        var released = 0
        val job = control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = {},
            release = { released++ },
            block = { CompletableDeferred<Unit>().await() },
        )
        assertTrue(control.isTurnRunning(1L))

        control.cancelTurnJob(1L)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, released)
        assertFalse(control.isTurnRunning(1L))
        assertFalse(control.runningConversations.contains(1L))
        assertEquals(emptySet<Long>(), control.runningIds.value)
        assertNull(control.turnJobs[1L])
    }

    @Test
    fun releaseAndEndTurnRunOnBlockFailure() = runBlocking {
        val control = SessionControl()
        var released = 0
        val job = control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = {},
            release = { released++ },
            block = { throw IllegalStateException("boom") },
        )
        job.join()

        assertTrue(job.isCompleted)
        assertEquals(1, released)
        assertFalse(control.isTurnRunning(1L))
        assertEquals(emptySet<Long>(), control.runningIds.value)
        assertNull(control.turnJobs[1L])
    }

    @Test
    fun turnsIsolatedAcrossConversations() = runBlocking {
        val control = SessionControl()
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        var releasedA = 0
        var releasedB = 0
        val jobA = control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = {},
            release = { releasedA++ },
            block = { gateA.await() },
        )
        val jobB = control.startTurnJob(
            conversationId = 2L,
            scope = scope(),
            acquire = {},
            release = { releasedB++ },
            block = { gateB.await() },
        )
        assertEquals(setOf(1L, 2L), control.runningIds.value)
        assertTrue(control.isTurnRunning(1L))
        assertTrue(control.isTurnRunning(2L))

        control.cancelTurnJob(1L)
        jobA.join()

        assertEquals(1, releasedA)
        assertEquals(0, releasedB)
        assertFalse(control.isTurnRunning(1L))
        assertTrue(control.isTurnRunning(2L))
        assertEquals(setOf(2L), control.runningIds.value)
        assertNull(control.turnJobs[1L])
        assertSame(jobB, control.turnJobs[2L])

        gateB.complete(Unit)
        jobB.join()
        assertEquals(1, releasedB)
        assertEquals(emptySet<Long>(), control.runningIds.value)
        assertNull(control.turnJobs[2L])
    }

    @Test
    fun staleCompletionDoesNotRemoveNewerJob() = runBlocking {
        val control = SessionControl()
        val gate = CompletableDeferred<Unit>()
        val old = control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = {},
            release = {},
            block = { gate.await() },
        )
        control.cancelTurnJob(1L)
        val replacement = control.startTurnJob(
            conversationId = 1L,
            scope = scope(),
            acquire = {},
            release = {},
            block = { CompletableDeferred<Unit>().await() },
        )
        old.join()

        assertSame(replacement, control.turnJobs[1L])
        assertTrue(control.isTurnRunning(1L))

        control.cancelTurnJob(1L)
        replacement.join()
        assertNull(control.turnJobs[1L])
        assertFalse(control.isTurnRunning(1L))
    }
}