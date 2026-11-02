package com.andmx.data.session

import com.andmx.llm.ApiMessage
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object SessionEventType {
    const val SESSION_CREATED = "session_created"
    const val SESSION_RESUMED = "session_resumed"
    const val SESSION_FORKED = "session_forked"
    const val SESSION_COMPACTED = "session_compacted"
    const val SESSION_TITLE_UPDATED = "session_title_updated"
    const val SESSION_MODE_CHANGED = "session_mode_changed"
    const val SESSION_ENDED = "session_ended"
    const val TURN_STARTED = "turn_started"
    const val TURN_INPUT_RECEIVED = "turn_input_received"
    const val TURN_STEER_QUEUED = "turn_steer_queued"
    const val TURN_STEER_DELIVERY_CHANGED = "turn_steer_delivery_changed"
    const val TURN_STEER_DISPATCH_CHANGED = "turn_steer_dispatch_changed"
    const val TURN_STEER_DRAINED = "turn_steer_drained"
    const val TURN_STEER_REJECTED = "turn_steer_rejected"
    const val TURN_STEER_DISCARDED = "turn_steer_discarded"
    const val SESSION_INPUT_PROMOTED = "session_input_promoted"
    const val TURN_STEER_REORDERED = "turn_steer_reordered"
    const val QUEUE_AUTO_DRAIN_CHANGED = "queue_auto_drain_changed"
    const val FOLLOWUP_MODE_CHANGED = "followup_mode_changed"
    const val TURN_COMPLETE = "turn_complete"
    const val TURN_ERROR = "turn_error"
    const val USER_MESSAGE = "user_message"
    const val ASSISTANT_MESSAGE = "assistant_message"
    const val ASSISTANT_FEEDBACK_UPDATED = "assistant_feedback_updated"
    const val HIGHSPEED_METRICS_UPDATED = "highspeed_metrics_updated"
    const val SYSTEM_MESSAGE = "system_message"
    const val MODEL_REQUEST = "model_request"
    const val MODEL_SELECTED = "model_selected"
    const val TURN_EXECUTION_MODEL_FALLBACK = "turn_execution_model_fallback"
    const val MODEL_STREAMING = "model_streaming"
    const val STREAMING_TOOL_LEDGER_UPDATED = "streaming_tool_ledger_updated"
    const val STREAM_RECOVERY_ANCHOR_CREATED = "stream_recovery_anchor_created"
    const val STREAM_RECOVERY_STARTED = "stream_recovery_started"
    const val STREAM_RECOVERY_ANCHOR_SELECTED = "stream_recovery_anchor_selected"
    const val STREAM_RECOVERY_TAIL_DISCARDED = "stream_recovery_tail_discarded"
    const val STREAM_RECOVERY_RETRY_STARTED = "stream_recovery_retry_started"
    const val STREAM_RECOVERY_BLOCKED = "stream_recovery_blocked"
    const val MODEL_NETWORK_STATUS = "model_network_status"
    const val MODEL_ANOMALY_WARNING = "model_anomaly_warning"
    const val NETWORK_REQUEST_STATUS = "network_request_status"
    const val MODEL_COMPLETE = "model_complete"
    const val MODEL_ERROR = "model_error"
    const val TOOL_CALL_SCHEDULED = "tool_call_scheduled"
    const val TOOL_CALL_STARTED = "tool_call_started"
    const val TOOL_CALL_PROGRESS = "tool_call_progress"
    const val TOOL_CALL_RESULT = "tool_call_result"
    const val TOOL_CALL_ERROR = "tool_call_error"
    const val TOOL_BATCH_COMPLETE = "tool_batch_complete"
    const val BACKGROUND_TASK_STARTED = "background_task_started"
    const val BACKGROUND_TASK_UPDATED = "background_task_updated"
    const val BACKGROUND_TASK_COMPLETED = "background_task_completed"
    const val DYNAMIC_WORKFLOW_RUN_PROGRESS = "dynamic_workflow_run_progress"
    const val PERMISSION_REQUESTED = "permission_requested"
    const val PERMISSION_RESOLVED = "permission_resolved"
    const val PERMISSION_DENIED = "permission_denied"
    const val PLUGIN_UI_RESOURCE_UPDATED = "plugin_ui_resource_updated"
    const val PLUGIN_UI_RESOURCE_LIST_CHANGED = "plugin_ui_resource_list_changed"
    const val PLUGIN_UI_INSTANCE_CLOSED = "plugin_ui_instance_closed"
    const val PLUGIN_UI_APP_TOOL_CALL_REQUESTED = "plugin_ui_app_tool_call_requested"
    const val USER_INPUT_AUTO_RESOLUTION_UPDATED = "user_input_auto_resolution_updated"
    const val WORKSPACE_HOOK_REVIEW_REQUESTED = "workspace_hook_review_requested"
    const val WORKSPACE_HOOK_REVIEW_SETTLED = "workspace_hook_review_settled"
    const val WORKSPACE_HOOK_REVIEW_SUPERSEDED = "workspace_hook_review_superseded"
    const val WORKSPACE_HOOK_ADMISSION_UPDATED = "workspace_hook_admission_updated"
    const val HOOK_RUN_STARTED = "hook_run_started"
    const val HOOK_RUN_PROGRESS = "hook_run_progress"
    const val HOOK_RUN_COMPLETED = "hook_run_completed"
    const val HOOK_RUN_FAILED = "hook_run_failed"
    const val HOOK_RUN_BLOCKED = "hook_run_blocked"
    const val COMPACT_STARTED = "compact_started"
    const val COMPACT_COMPLETED = "compact_completed"
    const val COMPACT_FAILED = "compact_failed"
    const val COMPACT_BOUNDARY = "compact_boundary"
    const val MICROCOMPACT_BOUNDARY = "microcompact_boundary"
    const val REWIND_TRIGGERED = "rewind_triggered"
    const val CHECKPOINT_CREATED = "checkpoint_created"
    const val TARGET_CHANGED = "target_changed"
    const val TARGET_COMPLETION_VERIFICATION = "target_completion_verification"
    const val SUBAGENT_SPAWNED = "subagent_spawned"
    const val SUBAGENT_MESSAGE = "subagent_message"
    const val SUBAGENT_STOPPED = "subagent_stopped"
    const val INTERRUPT = "interrupt"
    const val CANCEL = "cancel"
    const val RESUME = "resume"
    const val ERROR = "error"
}

val TRANSIENT_SESSION_EVENT_TYPES = setOf(
    SessionEventType.MODEL_STREAMING,
    SessionEventType.TOOL_CALL_PROGRESS,
    SessionEventType.STREAMING_TOOL_LEDGER_UPDATED,
    SessionEventType.MODEL_NETWORK_STATUS,
)

data class SessionEvent(
    val type: String,
    val sequenceNumber: Long,
    val traceId: String,
    val timestampMs: Long,
    val payload: JsonObject,
)

object ConversationProjection {
    fun userMessages(events: List<SessionEvent>): List<ApiMessage> =
        applyRewind(durable(events)).mapNotNull { toUserMessage(it) }

    fun modelMessages(events: List<SessionEvent>): List<ApiMessage> {
        val visible = applyRewind(durable(events))
        val boundary = visible.indexOfLast { it.type == SessionEventType.COMPACT_BOUNDARY }
        if (boundary < 0) return visible.mapNotNull { toModelMessage(it) }
        val summary = visible[boundary].payload["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: "compact_boundary"
        val tail = visible.drop(boundary + 1).mapNotNull { toModelMessage(it) }
        return listOf(ApiMessage(role = "user", content = summary)) + tail
    }

    fun messages(events: List<SessionEvent>): List<ApiMessage> = modelMessages(events)

    private fun durable(events: List<SessionEvent>): List<SessionEvent> =
        events.filter { it.type !in TRANSIENT_SESSION_EVENT_TYPES && it.type !in LIVE_ONLY_SESSION_EVENT_TYPES }

    private fun applyRewind(events: List<SessionEvent>): List<SessionEvent> {
        val visible = mutableListOf<SessionEvent>()
        for (event in events) {
            if (event.type == SessionEventType.REWIND_TRIGGERED) {
                val checkpoint = event.payload["checkpointSequence"]?.jsonPrimitive?.longOrNull
                if (checkpoint != null) {
                    visible.removeAll { it.sequenceNumber > checkpoint }
                }
            }
            visible += event
        }
        return visible
    }

    private fun toUserMessage(event: SessionEvent): ApiMessage? = when (event.type) {
        SessionEventType.COMPACT_BOUNDARY -> ApiMessage(
            role = "system",
            content = event.payload["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "compact_boundary",
        )
        SessionEventType.REWIND_TRIGGERED -> ApiMessage(role = "system", content = "rewind_triggered")
        else -> toModelMessage(event)
    }

    private fun toModelMessage(event: SessionEvent): ApiMessage? {
        val text = event.payload["text"]?.jsonPrimitive?.content
            ?: event.payload["output"]?.jsonPrimitive?.content
            ?: ""
        return when (event.type) {
            SessionEventType.USER_MESSAGE -> ApiMessage(role = "user", content = text)
            SessionEventType.ASSISTANT_MESSAGE -> ApiMessage(role = "assistant", content = text)
            SessionEventType.TOOL_CALL_RESULT, SessionEventType.TOOL_CALL_ERROR -> ApiMessage(
                role = "tool",
                content = text,
                toolCallId = event.payload["id"]?.jsonPrimitive?.content,
                name = event.payload["name"]?.jsonPrimitive?.content,
            )
            else -> null
        }
    }
}

val LIVE_ONLY_SESSION_EVENT_TYPES = setOf(
    SessionEventType.PLUGIN_UI_RESOURCE_UPDATED,
    SessionEventType.PLUGIN_UI_RESOURCE_LIST_CHANGED,
    SessionEventType.PLUGIN_UI_INSTANCE_CLOSED,
    SessionEventType.PLUGIN_UI_APP_TOOL_CALL_REQUESTED,
)

class SessionEventStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sequences = java.util.concurrent.ConcurrentHashMap<Long, AtomicLong>()

    fun file(conversationId: Long): File = File(dir, "$conversationId.jsonl")

    fun append(conversationId: Long, traceId: String, type: String, payload: JsonObject): SessionEvent {
        if (type in LIVE_ONLY_SESSION_EVENT_TYPES || type in TRANSIENT_SESSION_EVENT_TYPES) {
            return SessionEvent(type, 0, traceId, System.currentTimeMillis(), payload)
        }
        val seq = sequences.computeIfAbsent(conversationId) { AtomicLong(read(conversationId).lastOrNull()?.sequenceNumber ?: 0L) }
            .incrementAndGet()
        val event = SessionEvent(type, seq, traceId, System.currentTimeMillis(), payload)
        val line = buildJsonObject {
            put("type", event.type)
            put("sequenceNumber", event.sequenceNumber)
            put("traceId", event.traceId)
            put("timestampMs", event.timestampMs)
            put("payload", event.payload)
        }.toString()
        dir.mkdirs()
        file(conversationId).appendText(line + "\n")
        return event
    }

    fun read(conversationId: Long): List<SessionEvent> {
        val file = file(conversationId)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching {
                val obj = json.parseToJsonElement(line).jsonObject
                SessionEvent(
                    type = obj.getValue("type").jsonPrimitive.content,
                    sequenceNumber = obj.getValue("sequenceNumber").jsonPrimitive.content.toLong(),
                    traceId = obj.getValue("traceId").jsonPrimitive.content,
                    timestampMs = obj.getValue("timestampMs").jsonPrimitive.content.toLong(),
                    payload = obj["payload"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }.getOrNull()
        }
    }

    fun durable(conversationId: Long): List<SessionEvent> =
        read(conversationId).filter { it.type !in TRANSIENT_SESSION_EVENT_TYPES }

    fun replayMessages(conversationId: Long): List<ApiMessage> =
        ConversationProjection.modelMessages(read(conversationId))

    fun userView(conversationId: Long): List<ApiMessage> =
        ConversationProjection.userMessages(read(conversationId))

    fun rewind(conversationId: Long, traceId: String, checkpointSequence: Long): SessionEvent =
        append(
            conversationId,
            traceId,
            SessionEventType.REWIND_TRIGGERED,
            buildJsonObject { put("checkpointSequence", checkpointSequence) },
        )

    fun importMessages(conversationId: Long, traceId: String, messages: List<ApiMessage>) {
        if (read(conversationId).isNotEmpty()) return
        for (message in messages) {
            val type = when (message.role) {
                "user" -> SessionEventType.USER_MESSAGE
                "assistant" -> SessionEventType.ASSISTANT_MESSAGE
                "tool" -> SessionEventType.TOOL_CALL_RESULT
                else -> continue
            }
            append(
                conversationId,
                traceId,
                type,
                buildJsonObject {
                    put("text", message.content.orEmpty())
                    put("output", message.content.orEmpty())
                    message.toolCallId?.let { put("id", it) }
                    message.name?.let { put("name", it) }
                },
            )
        }
    }
}
