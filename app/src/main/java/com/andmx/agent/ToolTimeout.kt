package com.andmx.agent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Deadline arming for one tool call.
 *
 * The engine owns the wiring and the error text; each [Tool] owns whether it
 * has a budget at all ([Tool.timeoutMs]) and how it terminates when cancelled.
 *
 * Only the deadline armed here produces a timeout result. A cancellation that
 * arrives from anywhere else — the user stopping the turn, the scope shutting
 * down — throws a plain [kotlinx.coroutines.CancellationException] and
 * propagates, so a stopped turn is never misreported to the model as a slow
 * tool.
 */
object ToolTimeout {

    /** Model-facing text for a call whose own deadline won. */
    fun errorText(timeoutMs: Long): String = "Error: tool call timed out after ${timeoutMs}ms"

    /**
     * Run [body] under a deadline when the tool declares one.
     *
     * [timeoutMs] <= 0 delegates untouched rather than arming a deadline that
     * could never be met.
     */
    suspend fun <T> withDeadline(timeoutMs: Long?, onTimeout: (Long) -> T, body: suspend () -> T): T {
        val limit = timeoutMs ?: return body()
        if (limit <= 0) return body()
        return try {
            withTimeout(limit) { body() }
        } catch (e: TimeoutCancellationException) {
            onTimeout(limit)
        }
    }
}
