package com.andmx.agent.zcode

import com.andmx.agent.ExecutionAwareTool
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object SessionToolLogic {
    fun busyDecision(running: Boolean, queued: Int): String =
        if (running || queued > 0) "queued" else "sent"

    fun validateSessionId(tool: String, raw: String?): ToolResult? {
        if (raw == null) return ToolResult(missingParameter(tool, "sessionId"), isError = true)
        if (raw.isEmpty() || raw.length > 128) return validationError(tool)
        return null
    }

    fun validateMessage(tool: String, raw: String?): ToolResult? {
        if (raw == null) return ToolResult(missingParameter(tool, "message"), isError = true)
        if (raw.isBlank() || raw.length > 20_000) return validationError(tool)
        return null
    }

    fun validateRetryOf(tool: String, raw: String?): ToolResult? {
        if (raw != null && (raw.isEmpty() || raw.length > 128)) return validationError(tool)
        return null
    }

    fun clampWaitTimeout(raw: Long?): Long =
        (raw ?: SESSION_WAIT_DEFAULT_TIMEOUT_MS).coerceIn(0L, SESSION_WAIT_MAX_TIMEOUT_MS)

    private fun validationError(tool: String) =
        ToolResult("$tool input failed inputSchema validation", isError = true)
}

class SessionRequestLedger(private val maxEntries: Int = 512) {
    private val entries = LinkedHashMap<String, SessionSendResult>()

    @Synchronized
    fun get(key: String): SessionSendResult? = entries[key]

    @Synchronized
    fun put(key: String, result: SessionSendResult) {
        entries[key] = result
        while (entries.size > maxEntries) {
            val oldest = entries.keys.iterator().next()
            entries.remove(oldest)
        }
    }
}

class ListSessionsTool(private val host: SessionControlHost) : Tool {
    override val name = "ListSessions"
    override val shouldDefer = true
    override val description = SESSION_LIST_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs = SESSION_CONTROL_TIMEOUT_MS
    override val maxOutputBytes = SESSION_CONTROL_MODEL_BYTES
    override val sideEffectScope = "none"
    override val riskLevel = "low"
    override val permissionName = "session_control.listSessions"
    override val parameters = SESSION_LIST_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull
        if (query != null && query.length > 200) {
            return ToolResult("ListSessions input failed inputSchema validation", isError = true)
        }
        val includeArchived = (args["includeArchived"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val sessions = runCatching { host.listSessions(query?.takeIf { it.isNotBlank() }, includeArchived) }
            .getOrElse { return ToolResult("ListSessions failed: ${it.message}", isError = true) }
        return ToolResult(formatSessionList(sessions))
    }
}

class ReadSessionTool(private val host: SessionControlHost) : Tool {
    override val name = "ReadSession"
    override val shouldDefer = true
    override val description = SESSION_READ_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs = READ_SESSION_TIMEOUT_MS
    override val maxOutputBytes = SESSION_CONTROL_MODEL_BYTES
    override val sideEffectScope = "none"
    override val riskLevel = "low"
    override val permissionName = "session_control.readSession"
    override val parameters = SESSION_READ_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val sessionId = sessionIdFromArgs(args)
        SessionToolLogic.validateSessionId(name, sessionId)?.let { return it }
        if (sessionId == null) return ToolResult(missingParameter(name, "sessionId"), isError = true)
        val maxTokens = (args["maxTokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: READ_SESSION_CONTEXT_DEFAULT_MAX_TOKENS
        if (maxTokens !in 1..READ_SESSION_CONTEXT_MAX_TOKENS) {
            return ToolResult("ReadSession input failed inputSchema validation", isError = true)
        }
        val read = runCatching { host.readSession(sessionId, maxTokens) }.getOrElse {
            SessionContextRead(
                status = "failed",
                source = "none",
                sessionId = sessionId,
                content = "Failed to read persisted session history.",
                error = it.message,
            )
        }
        return ToolResult(
            formatSessionRead(read, read.sessionId ?: sessionId),
            isError = read.status == "failed",
        )
    }
}

class CreateSessionTool(private val host: SessionControlHost) : Tool {
    override val name = "CreateSession"
    override val shouldDefer = true
    override val description = SESSION_CREATE_DESCRIPTION
    override val risk = ToolRisk.WRITE
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = false
    override val needsApproval = true
    override val timeoutMs = SESSION_CONTROL_TIMEOUT_MS
    override val maxOutputBytes = SESSION_CONTROL_MODEL_BYTES
    override val sideEffectScope = "system"
    override val riskLevel = "medium"
    override val permissionName = "session_control.createSession"
    override val parameters = SESSION_CREATE_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = (args["title"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (title == null) return ToolResult(missingParameter(name, "title"), isError = true)
        if (title.isEmpty() || title.length > 200) {
            return ToolResult("CreateSession input failed inputSchema validation", isError = true)
        }
        val task = (args["task"] as? JsonPrimitive)?.contentOrNull
        if (task != null && (task.isBlank() || task.length > 20_000)) {
            return ToolResult("CreateSession input failed inputSchema validation", isError = true)
        }
        val workspace = (args["workspace"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (workspace != null && workspace.length > 512) {
            return ToolResult("CreateSession input failed inputSchema validation", isError = true)
        }
        val created = runCatching { host.createSession(title, task, workspace) }
            .getOrElse { return ToolResult("CreateSession failed: ${it.message}", isError = true) }
        return ToolResult(formatSessionCreated(created))
    }
}

class SendToSessionTool(
    private val host: SessionControlHost,
    private val ledger: SessionRequestLedger,
) : Tool, ExecutionAwareTool {
    override val name = "SendToSession"
    override val shouldDefer = true
    override val description = SESSION_SEND_DESCRIPTION
    override val risk = ToolRisk.WRITE
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = false
    override val needsApproval = true
    override val timeoutMs = SESSION_CONTROL_TIMEOUT_MS
    override val maxOutputBytes = SESSION_CONTROL_MODEL_BYTES
    override val sideEffectScope = "system"
    override val riskLevel = "medium"
    override val permissionName = "session_control.sendToSession"
    override val parameters = SESSION_SEND_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult = execute("", args)

    override suspend fun execute(callId: String, args: JsonObject): ToolResult {
        val sessionId = sessionIdFromArgs(args)
        SessionToolLogic.validateSessionId(name, sessionId)?.let { return it }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull
        SessionToolLogic.validateMessage(name, message)?.let { return it }
        if (sessionId == null || message == null) {
            return ToolResult(missingParameter(name, "sessionId"), isError = true)
        }
        val retryOf = (args["retryOf"] as? JsonPrimitive)?.contentOrNull?.trim()
        SessionToolLogic.validateRetryOf(name, retryOf)?.let { return it }
        val requestId = requestIdentity(callId, retryOf)
        val key = "$sessionId:$requestId"
        ledger.get(key)?.let { return ToolResult(formatSessionSend(it)) }
        val result = runCatching { host.sendToSession(sessionId, message, requestId) }.getOrElse {
            SessionSendResult(
                requestId = requestId,
                sessionId = sessionId,
                status = "failed",
                error = it.message,
            )
        }
        ledger.put(key, result)
        return ToolResult(formatSessionSend(result), isError = result.status == "failed")
    }
}

class WaitSessionsTool(private val host: SessionControlHost) : Tool {
    override val name = "WaitSessions"
    override val shouldDefer = true
    override val description = SESSION_WAIT_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val maxOutputBytes = SESSION_CONTROL_MODEL_BYTES
    override val sideEffectScope = "none"
    override val riskLevel = "low"
    override val permissionName = "session_control.waitSessions"
    override val parameters = SESSION_WAIT_PARAMETERS
    override val timeoutMs = SESSION_WAIT_TIMEOUT_MS

    override fun resolveTimeoutMs(args: JsonObject): Long? {
        val requested = (args["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        return SessionToolLogic.clampWaitTimeout(requested) + 10_000L
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val arr = args["sessionIds"] as? JsonArray
            ?: return ToolResult(missingParameter(name, "sessionIds"), isError = true)
        if (arr.isEmpty() || arr.size > 16) {
            return ToolResult("WaitSessions input failed inputSchema validation", isError = true)
        }
        val ids = arr.map { element ->
            val id = (element as? JsonPrimitive)?.contentOrNull?.trim()
            if (id.isNullOrEmpty() || id.length > 128) {
                return ToolResult("WaitSessions input failed inputSchema validation", isError = true)
            }
            id
        }.distinct()
        val timeoutMs = SessionToolLogic.clampWaitTimeout(
            (args["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
        )
        var remaining = timeoutMs
        var waitedMs = 0L
        var states = ids.associateWith { runCatching { host.waitState(it) }.getOrNull() }
        var timedOut = false
        while (states.values.any { it != null && (it.running || it.queued > 0) }) {
            if (remaining <= 0L) {
                timedOut = true
                break
            }
            val step = minOf(SESSION_WAIT_POLL_MS, remaining)
            delay(step)
            remaining -= step
            waitedMs += step
            states = ids.associateWith { runCatching { host.waitState(it) }.getOrNull() }
        }
        return ToolResult(formatSessionWait(ids, states, timedOut, waitedMs))
    }
}

class SessionTools(private val host: SessionControlHost) {
    private val ledger = SessionRequestLedger()

    fun all(): List<Tool> = listOf(
        ListSessionsTool(host),
        ReadSessionTool(host),
        CreateSessionTool(host),
        SendToSessionTool(host, ledger),
        WaitSessionsTool(host),
    )
}