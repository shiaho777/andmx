package com.andmx.telemetry

import android.util.Log
import com.andmx.data.ConversationRepository
import com.andmx.data.LogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Lightweight telemetry sink — mirrors Codex's tracing infrastructure.
 *
 * Instead of pulling in OpenTelemetry SDK, this provides a minimal tracing
 * interface that records spans and events to the Room logs table.
 * Spans are nested via parent IDs and support timing.
 */
class TelemetrySink(
    private val repository: ConversationRepository? = null,
) {
    companion object {
        private const val TAG = "TelemetrySink"
    }

    private val _activeSpans = MutableStateFlow<Map<String, Span>>(emptyMap())
    val activeSpans: StateFlow<Map<String, Span>> = _activeSpans.asStateFlow()

    private val processUuid = UUID.randomUUID().toString()

    /** Start a new span. Returns the span ID. */
    fun startSpan(
        name: String,
        parentId: String? = null,
        conversationId: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ): String {
        val spanId = UUID.randomUUID().toString().take(8)
        val span = Span(
            id = spanId,
            name = name,
            parentId = parentId,
            conversationId = conversationId,
            startedAt = System.currentTimeMillis(),
            attributes = attributes.toMutableMap(),
        )
        _activeSpans.value = _activeSpans.value + (spanId to span)
        return spanId
    }

    /** End a span and record it. */
    suspend fun endSpan(spanId: String, status: SpanStatus = SpanStatus.OK) {
        val span = _activeSpans.value[spanId] ?: return
        val ended = span.copy(
            endedAt = System.currentTimeMillis(),
            status = status,
        )
        _activeSpans.value = _activeSpans.value - spanId

        val durationMs = ended.endedAt - ended.startedAt
        val logContent = buildString {
            append("[${ended.name}] ")
            append("duration=${durationMs}ms ")
            append("status=${status.name.lowercase()}")
            if (ended.attributes.isNotEmpty()) {
                append(" attrs={")
                append(ended.attributes.entries.joinToString(", ") { "${it.key}=${it.value}" })
                append("}")
            }
            if (ended.parentId != null) append(" parent=${ended.parentId}")
        }

        val level = when (status) {
            SpanStatus.OK -> "info"
            SpanStatus.ERROR -> "error"
        }

        ended.conversationId?.let { cid ->
            repository?.addLog(cid, logContent, level, processUuid)
        }
        Log.d(TAG, logContent)
    }

    /** Record an event (not a span — just a point-in-time log). */
    suspend fun recordEvent(
        name: String,
        conversationId: Long? = null,
        level: String = "info",
        attributes: Map<String, String> = emptyMap(),
    ) {
        val content = buildString {
            append("[$name]")
            if (attributes.isNotEmpty()) {
                append(" ")
                append(attributes.entries.joinToString(", ") { "${it.key}=${it.value}" })
            }
        }
        conversationId?.let { repository?.addLog(it, content, level, processUuid) }
        Log.d(TAG, content)
    }

    /** Execute [block] within a span, automatically timing and recording. */
    suspend fun <T> withSpan(
        name: String,
        conversationId: Long? = null,
        attributes: Map<String, String> = emptyMap(),
        block: suspend () -> T,
    ): T {
        val spanId = startSpan(name, null, conversationId, attributes)
        return try {
            val result = block()
            endSpan(spanId, SpanStatus.OK)
            result
        } catch (t: Throwable) {
            endSpan(spanId, SpanStatus.ERROR)
            throw t
        }
    }

    data class Span(
        val id: String,
        val name: String,
        val parentId: String? = null,
        val conversationId: Long? = null,
        val startedAt: Long,
        val endedAt: Long = 0L,
        val status: SpanStatus = SpanStatus.OK,
        val attributes: MutableMap<String, String> = mutableMapOf(),
    )

    enum class SpanStatus { OK, ERROR }
}
