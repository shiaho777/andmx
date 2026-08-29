package com.andmx.agent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deadline is the tool's own budget, and only the deadline armed by the
 * engine may produce a timeout. A cancellation from anywhere else — the user
 * stopping the turn — has to keep travelling as a cancellation, or a stopped
 * turn gets reported to the model as a slow command it should retry.
 */
class ToolTimeoutTest {

    @Test
    fun deadlineThatWinsProducesATimeoutResult() = runTest {
        val out = ToolTimeout.withDeadline(50, { "TIMEOUT:$it" }) { delay(10_000); "done" }
        assertEquals("TIMEOUT:50", out)
    }

    @Test
    fun workThatFinishesInTimePassesThrough() = runTest {
        val out = ToolTimeout.withDeadline(10_000, { "TIMEOUT" }) { delay(10); "done" }
        assertEquals("done", out)
    }

    @Test
    fun noDeclaredBudgetMeansNoDeadline() = runTest {
        val out = ToolTimeout.withDeadline(null, { "TIMEOUT" }) { delay(10_000); "done" }
        assertEquals("done", out)
    }

    @Test
    fun nonPositiveBudgetDelegatesRatherThanArmingAnUnmeetableDeadline() = runTest {
        val out = ToolTimeout.withDeadline(0, { "TIMEOUT" }) { "done" }
        assertEquals("done", out)
    }

    @Test
    fun outerCancellationIsNotReportedAsATimeout() = runTest {
        val deferred = async {
            ToolTimeout.withDeadline(60_000, { "TIMEOUT" }) { delay(10_000); "done" }
        }
        deferred.cancel()
        val error = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue("外部取消必须继续作为取消传播: $error", error !is TimeoutCancellationException)
    }

    @Test
    fun errorTextNamesTheBudget() {
        assertEquals("Error: tool call timed out after 30000ms", ToolTimeout.errorText(30_000))
    }
}
