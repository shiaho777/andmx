package com.andmx.agent

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.LlmStreamEvent
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Events streamed out of an agent turn for the UI to render. */
sealed interface AgentEvent {
    /** Incremental assistant text chunk (streaming). */
    data class AssistantDelta(val text: String) : AgentEvent
    /** Incremental model thinking / reasoning chunk. */
    data class ReasoningDelta(val text: String) : AgentEvent
    /** Thinking block finished for the current model step. */
    data object ReasoningDone : AgentEvent
    /** A fully-committed assistant message (final answer for a turn). */
    data class Assistant(val text: String) : AgentEvent
    data class ToolCallArgsDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsSoFar: String,
    ) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val arguments: String) : AgentEvent
    data class ToolFinished(
        val id: String, val name: String, val output: String, val isError: Boolean,
        /** Image data-urls produced by the tool (e.g. computer-use screenshots). */
        val imageUrls: List<String>? = null,
    ) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object Done : AgentEvent
}

/**
 * What the agent needs to know about the active backend for a turn: the bound
 * [provider] (for capabilities/retry already baked into the client) and the
 * model id the user selected, plus that model's metadata (if known) so the
 * loop can decide reasoning effort and compaction thresholds.
 */
data class TurnContext(
    val provider: ProviderDefinition,
    val model: String,
) {
    val modelMeta: ModelDefinition? get() = provider.models[model]
}

/**
 * Provider-agnostic agent loop: ask the model, run any tool calls inside the
 * sandbox, feed results back, repeat until the model answers in plain text.
 */
class AgentEngine(
    private val tools: List<Tool>,
    private val client: LlmApi,
    private val compactor: ContextCompactor = ContextCompactor(client = client),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val historyToolOutputLimit: Int = 8_000,
    private val maxSteps: Int = 50,
    /** Steps granted after maxSteps to let the model wrap up; if it still hasn't converged, we fail. */
    private val graceSteps: Int = 3,
    /** Optional hook system; PRE/POST_TOOL_USE hooks run around each tool call. */
    private val hooks: com.andmx.agent.hooks.HookSystem? = null,
    /** Gate consulted before running each tool; return false to refuse. */
    private val approve: suspend (Tool, JsonObject) -> Boolean = { _, _ -> true },
) {
    private val history = mutableListOf(ApiMessage(role = "system", content = systemPrompt))
    private var extraTools: List<Tool> = emptyList()
    private val allTools get() = tools + extraTools
    private val toolsByName get() = allTools.associateBy { it.name }
    private var systemSuffix: String = ""
    private var persona: String = ""

    /** Register additional tools at runtime (e.g. from MCP servers). */
    fun addTools(more: List<Tool>) { extraTools = extraTools + more }

    /** (name, description) of all currently-registered tools, for the plugins page. */
    fun listTools(): List<Pair<String, String>> = allTools.map { it.name to it.description }

    /**
     * Resolve the reasoning effort to send for this turn, driven by the model's
     * declared [com.andmx.llm.provider.ReasoningConfig]:
     * - NONE style (or unknown model) → null (send nothing; DeepSeek-style CoT
     *   is not adjustable, gpt-4o doesn't support it)
     * - EFFORT style → the user's level only if it's one the model accepts,
     *   else the model's default effort
     * - THINKING style → the user's value (a budget number or "enabled"); the
     *   adapter clamps it to the spec range
     */
    private fun reasoningFor(settings: ProviderSettings, ctx: TurnContext): String? {
        val e = settings.reasoningEffort
        if (e.isBlank() || e == "off") return null
        val reasoning = ctx.modelMeta?.reasoning ?: return null
        return when (reasoning.style) {
            com.andmx.llm.provider.ReasoningStyle.NONE -> null
            com.andmx.llm.provider.ReasoningStyle.EFFORT ->
                if (e in reasoning.effortLevels) e else reasoning.defaultEffort
            com.andmx.llm.provider.ReasoningStyle.THINKING -> e
        }
    }

    /** Append project/custom instructions to the system prompt. */
    fun setCustomInstructions(text: String) {
        systemSuffix = text.trim()
        history[0] = ApiMessage(role = "system", content = composedSystem())
    }

    /** Set the assistant persona/tone. */
    fun setPersona(p: String) {
        persona = p.trim()
        history[0] = ApiMessage(role = "system", content = composedSystem())
    }

    private fun composedSystem(): String = buildString {
        append(systemPrompt)
        if (persona.isNotBlank()) append("\n\n# 语气\n以「").append(persona).append("」的风格回应。")
        if (systemSuffix.isNotBlank()) append("\n\n# 用户自定义指令\n").append(systemSuffix)
    }

    /** Public access to the composed system prompt (for rollout recording). */
    fun composedSystemPrompt(): String = composedSystem()

    /** Reset the conversation history (used when loading a saved conversation). */
    fun seed(messages: List<ApiMessage>) {
        history.clear()
        history += ApiMessage(role = "system", content = composedSystem())
        history += messages
    }

    /** Snapshot of the current history (used to preserve state across engine rebuilds). */
    fun snapshotHistory(): List<ApiMessage> = history.toList()

    suspend fun compactNow(settings: ProviderSettings, turn: TurnContext): String? {
        hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.PRE_COMPACT)
        val result = compactor.compact(history, settings, turn) ?: return null
        history.clear()
        history += result.compacted
        hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.POST_COMPACT)
        return "已压缩：移除 ${result.removedCount} 条历史 · ${result.tokensBefore}→${result.tokensAfter} tokens"
    }

    suspend fun checkpointNow(settings: ProviderSettings, turn: TurnContext, goal: String = ""): String? {
        val result = compactor.createCheckpoint(history, turn, goal) ?: return null
        history.clear()
        history += result.compacted
        return result.summary
    }

    fun runTurn(settings: ProviderSettings, turn: TurnContext, userInput: String, images: List<String> = emptyList()): Flow<AgentEvent> = flow {
        history += ApiMessage(role = "user", content = userInput, imageUrls = images.ifEmpty { null })
        loop(settings, turn)
    }

    /**
     * Re-run the model on the current history without adding a new user message,
     * after discarding any trailing assistant/tool messages (regenerate).
     */
    fun regenerate(settings: ProviderSettings, turn: TurnContext): Flow<AgentEvent> = flow {
        while (history.size > 1 && history.last().role != "user") history.removeAt(history.lastIndex)
        if (history.none { it.role == "user" }) { emit(AgentEvent.Done); return@flow }
        loop(settings, turn)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.loop(settings: ProviderSettings, turn: TurnContext) {
        // Repair history damaged by an interrupted turn: if the tail is an
        // assistant message with tool_calls but no matching tool results,
        // synthesize "interrupted" results so the model isn't confused.
        cleanupOrphanToolCalls()

        val contextWindow = turn.modelMeta?.contextWindow?.takeIf { it > 0 } ?: 128_000
        var convergenceHinted = false
        var step = 0
        val hardLimit = maxSteps + graceSteps
        while (step++ < hardLimit) {
            // ── Context management: soft compact, then hard-limit fallback ──
            val overHardLimit = compactor.isContextWindowExceeded(history, contextWindow)
            if (overHardLimit || compactor.needsCompaction(history, contextWindow)) {
                hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.PRE_COMPACT)
                val result = compactor.compact(history, settings, turn)
                if (result != null) {
                    history.clear()
                    history += result.compacted
                    hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.POST_COMPACT)
                    emit(AgentEvent.AssistantDelta("\n_(上下文已自动压缩: 移除 ${result.removedCount} 条历史消息)_\n"))
                } else if (overHardLimit) {
                    // Compaction failed while over the hard limit — fall back to
                    // dropping the oldest messages instead of looping forever.
                    val dropped = dropOldestNonSystem(keepRecent = 8)
                    if (dropped > 0) {
                        emit(AgentEvent.AssistantDelta("\n_(压缩失败,已丢弃 $dropped 条旧消息以释放上下文)_\n"))
                    }
                }
            }

            // ── Step budget: nudge the model to converge as it runs out ──
            if (step == maxSteps && !convergenceHinted) {
                convergenceHinted = true
                history += ApiMessage(
                    role = "system",
                    content = "步数即将用尽。请立即总结当前进度,完成收尾,不要再发起新的工具调用。",
                )
            }

            val request = ChatRequest(
                model = turn.model,
                messages = history.toList(),
                tools = allTools.map { it.toApiTool() },
                reasoningEffort = reasoningFor(settings, turn),
            )

            // Stream with retry: a transient stream break / empty reply shouldn't
            // kill the whole turn. Retry up to 2 times with backoff.
            var sawReasoning = false
            val toolArgBuf = sortedMapOf<Int, StringBuilder>()
            val toolMeta = sortedMapOf<Int, Pair<String?, String?>>()
            val msg = streamWithRetry(
                request,
                onContent = { emit(AgentEvent.AssistantDelta(it)) },
                onReasoning = {
                    sawReasoning = true
                    emit(AgentEvent.ReasoningDelta(it))
                },
                onToolCall = { index, id, name, argDelta ->
                    val meta = toolMeta[index]
                    val nextId = id ?: meta?.first
                    val nextName = name ?: meta?.second
                    toolMeta[index] = nextId to nextName
                    if (argDelta.isNotEmpty()) {
                        toolArgBuf.getOrPut(index) { StringBuilder() }.append(argDelta)
                    } else {
                        toolArgBuf.getOrPut(index) { StringBuilder() }
                    }
                    emit(
                        AgentEvent.ToolCallArgsDelta(
                            index = index,
                            id = nextId,
                            name = nextName,
                            argumentsSoFar = toolArgBuf[index].toString(),
                        ),
                    )
                },
            )
            if (sawReasoning) emit(AgentEvent.ReasoningDone)
            if (msg == null) {
                emit(AgentEvent.Failed("多次重试后仍无响应"))
                emit(AgentEvent.Done)
                return
            }
            history += msg

            val calls = msg.toolCalls
            if (calls.isNullOrEmpty()) {
                // Final answer — commit the text and finish.
                msg.content?.takeIf { it.isNotBlank() }?.let { emit(AgentEvent.Assistant(it)) }
                emit(AgentEvent.Done)
                return
            }

            // Tool call ahead — commit any intermediate text so it's not lost
            // on pause/restart. Codex shows these as agent_message items between
            // tool calls, narrating the work process.
            msg.content?.takeIf { it.isNotBlank() }?.let { emit(AgentEvent.Assistant(it)) }

            // Run each requested tool. Multiple calls execute concurrently, but
            // emissions must stay on the flow's owner coroutine (FlowCollector
            // is not thread-safe), so we emit ToolStarted up front, run the
            // tools in parallel without emitting, then emit ToolFinished in order.
            if (calls.size <= 1) {
                for (call in calls) {
                    emit(AgentEvent.ToolStarted(call.id, call.function.name, call.function.arguments))
                    val result = executeToolCall(call)
                    emit(AgentEvent.ToolFinished(call.id, call.function.name, result.output, result.isError, result.imageUrls))
                    history += ApiMessage(role = "tool", content = trimToolOutput(result.output), toolCallId = call.id, name = call.function.name, imageUrls = result.imageUrls)
                }
            } else {
                // Emit all ToolStarted first (serial, on the flow coroutine).
                calls.forEach { call ->
                    emit(AgentEvent.ToolStarted(call.id, call.function.name, call.function.arguments))
                }
                // Execute tools in parallel — NO emit inside async.
                val results = coroutineScope {
                    calls.map { call ->
                        async { call to executeToolCall(call) }
                    }.map { it.await() }
                }
                // Emit ToolFinished in order (serial, on the flow coroutine).
                for ((call, result) in results) {
                    emit(AgentEvent.ToolFinished(call.id, call.function.name, result.output, result.isError, result.imageUrls))
                    history += ApiMessage(role = "tool", content = trimToolOutput(result.output), toolCallId = call.id, name = call.function.name, imageUrls = result.imageUrls)
                }
            }
        }
        emit(AgentEvent.Failed("已达最大步数 ($maxSteps) + 收敛宽限 ($graceSteps),任务未能完成"))
        emit(AgentEvent.Done)
    }

    /**
     * Stream a request with up to [maxRetries] retries on failure or empty reply.
     * Returns the assembled assistant message, or null if all attempts fail.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.streamWithRetry(
        request: ChatRequest,
        onContent: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
        onToolCall: suspend (index: Int, id: String?, name: String?, argumentsDelta: String) -> Unit = { _, _, _, _ -> },
    ): ApiMessage? {
        val maxRetries = 2
        var lastError: String? = null
        for (attempt in 0..maxRetries) {
            var message: ApiMessage? = null
            val gotContent = try {
                client.chatStream(request).collect { ev ->
                    when (ev) {
                        is LlmStreamEvent.Content -> onContent(ev.delta)
                        is LlmStreamEvent.Reasoning -> onReasoning(ev.delta)
                        is LlmStreamEvent.ToolCallDelta -> onToolCall(ev.index, ev.id, ev.name, ev.argumentsDelta)
                        is LlmStreamEvent.Completed -> message = ev.message
                        is LlmStreamEvent.UsageUpdate -> { /* tracked by LlmClient */ }
                    }
                }
                true
            } catch (t: Throwable) {
                lastError = t.message ?: "请求失败"
                false
            }
            if (gotContent && message != null) return message
            // Empty reply on first attempt: retry once (possibly a lost first chunk).
            if (gotContent && message == null && attempt == 0) continue
            if (attempt < maxRetries) kotlinx.coroutines.delay(1000L shl attempt)
        }
        lastError?.let { emit(AgentEvent.Failed(it)) }
        return null
    }

    /**
     * Drop the oldest non-system messages (keeping the most recent [keepRecent])
     * as a last-resort context release when compaction fails. Returns the count
     * removed. Never touches system messages or the recent window.
     */
    private fun dropOldestNonSystem(keepRecent: Int): Int {
        if (history.size <= keepRecent + 1) return 0
        val systemEnd = history.indexOfLast { it.role == "system" } + 1
        val removable = history.subList(systemEnd, history.size - keepRecent)
        val count = removable.size
        if (count <= 0) return 0
        removable.clear()
        return count
    }

    /**
     * Repair a history left mid-tool by an interrupted turn: any trailing
     * assistant tool_calls without matching tool results get synthesized
     * "interrupted" results so the next model call isn't confused.
     */
    private fun trimToolOutput(output: String): String {
        if (output.length <= historyToolOutputLimit) return output
        val head = historyToolOutputLimit / 2
        val tail = historyToolOutputLimit - head - 32
        val omitted = output.length - historyToolOutputLimit
        return output.take(head) + "\n…[截断 " + omitted + " 字符]…\n" + output.takeLast(tail.coerceAtLeast(0))
    }

    private fun cleanupOrphanToolCalls() {
        if (history.isEmpty()) return
        val last = history.last()
        val orphanCalls = last.toolCalls.orEmpty().filter { call ->
            history.none { it.role == "tool" && it.toolCallId == call.id }
        }
        if (orphanCalls.isNotEmpty()) {
            orphanCalls.forEach { call ->
                history += ApiMessage(
                    role = "tool",
                    content = "_[执行被中断,未拿到结果]_",
                    toolCallId = call.id,
                    name = call.function.name,
                )
            }
        }
    }

    private fun parseArgs(raw: String): JsonObject = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }

    /** Execute a single tool call: PRE_TOOL_USE hook → approval → run → POST_TOOL_USE hook. */
    private suspend fun executeToolCall(call: com.andmx.llm.ApiToolCall): ToolResult {
        val tool = toolsByName[call.function.name]
        return if (tool == null) {
            ToolResult("未知工具: ${call.function.name}", isError = true)
        } else {
            // ── PRE_TOOL_USE: hooks may block or modify args ──
            val preCtx = com.andmx.agent.hooks.HookSystem.HookContext(
                toolName = call.function.name,
                toolArgs = call.function.arguments,
            )
            val pre = hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.PRE_TOOL_USE, preCtx)
                ?: com.andmx.agent.hooks.HookSystem.HookResult(com.andmx.agent.hooks.HookSystem.HookDecision.CONTINUE)
            if (pre.decision == com.andmx.agent.hooks.HookSystem.HookDecision.BLOCK) {
                return ToolResult("被 hook 拦截: ${pre.message.orEmpty()}", isError = true)
            }
            val effectiveArgs = if (pre.decision == com.andmx.agent.hooks.HookSystem.HookDecision.MODIFY && pre.modifiedInput != null) {
                parseArgs(pre.modifiedInput!!)
            } else {
                parseArgs(call.function.arguments)
            }

            if (!approve(tool, effectiveArgs)) {
                ToolResult("已被用户拒绝执行", isError = true)
            } else {
                val raw = runCatching {
                    if (tool is ExecutionAwareTool) tool.execute(call.id, effectiveArgs)
                    else tool.execute(effectiveArgs)
                }
                    .getOrElse { ToolResult("工具异常: ${it.message}", isError = true) }
                // ── POST_TOOL_USE: hooks may rewrite the output ──
                val postCtx = com.andmx.agent.hooks.HookSystem.HookContext(
                    toolName = call.function.name,
                    toolArgs = call.function.arguments,
                    toolOutput = raw.output,
                )
                val post = hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.POST_TOOL_USE, postCtx)
                if (post?.decision == com.andmx.agent.hooks.HookSystem.HookDecision.MODIFY && post.modifiedOutput != null) {
                    raw.copy(output = post.modifiedOutput!!)
                } else {
                    raw
                }
            }
        }
    }

    companion object {
        val DEFAULT_SYSTEM_PROMPT: String =
            com.andmx.agent.zcode.ZCodePrompts.IDENTITY + "\n\n" +
                com.andmx.agent.zcode.ZCodePrompts.CORE + "\n\n" +
                com.andmx.agent.zcode.ZCodePrompts.CRAFT
    }
}
