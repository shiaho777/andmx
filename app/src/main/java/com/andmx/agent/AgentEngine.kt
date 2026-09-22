package com.andmx.agent

import com.andmx.agent.zcode.ZCodePrompts
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Events streamed out of an agent turn for the UI to render. */
sealed interface AgentEvent {
    /** Incremental assistant text chunk (streaming). */
    data class AssistantDelta(val text: String) : AgentEvent
    /**
     * 本 step 请求发出（TTFT 起点）。turn 由引擎递增计数。
     */
    data class StepStarted(val turn: Int, val step: Int, val startedAtMs: Long) : AgentEvent
    /** 本 step 首个可见内容到达（TTFT 终点 / 解码起点）。 */
    data class FirstToken(val turn: Int, val step: Int, val atMs: Long) : AgentEvent
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
    /** 模型请求即将重试（上游 network-events attempt 显示对齐）。 */
    data class Retrying(val attempt: Int, val maxAttempts: Int, val delayMs: Long) : AgentEvent
    /** 目标完成度验证开始（ZCode goalVerifier 对齐）。 */
    data class GoalVerifying(val iteration: Int) : AgentEvent
    /** 目标完成度验证结果：passed=false 时引擎会注入 continuation 续跑。 */
    data class GoalVerified(
        val iteration: Int,
        val passed: Boolean,
        val reason: String,
        val nextAction: String,
    ) : AgentEvent
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
    /** Sub-agent `$level` override from the spawn model spec; wins over settings.reasoningEffort. */
    val reasoningOverride: String? = null,
    /** Rollout/transcript file path; surfaced in the compact summary so the model can re-read pre-compaction detail. */
    val transcriptPath: String? = null,
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
    private val compactor: ContextCompactor = ContextCompactor(
        client = TracedLlm(client, ModelCallTrace.Source.COMPACT),
    ),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /**
     * ZCode-style split system prompt: [cliPrefix, stable, dynamic] blocks kept
     * as separate leading system messages so provider-side prompt caches keyed
     * on the stable prefix survive dynamic-section churn. Empty = legacy
     * single-block [systemPrompt].
     */
    private var systemPromptBlocks: List<String> = emptyList(),
    private val historyToolOutputLimit: Int = 8_000,
    private val maxSteps: Int = 50,
    /** Steps granted after maxSteps to let the model wrap up; if it still hasn't converged, we fail. */
    private val graceSteps: Int = 3,
    /** Optional hook system; PRE/POST_TOOL_USE hooks run around each tool call. */
    private val hooks: com.andmx.agent.hooks.HookSystem? = null,
    /** Gate consulted before running each tool. Only [ApprovalOutcome.AllowedOnce] runs it. */
    private val approve: ApprovalGate = { _, _ -> ApprovalOutcome.AllowedOnce },
    /** Advisory guard against a model re-issuing one identical call forever. */
    private val repeatGuard: RepeatCallGuard = RepeatCallGuard(),
    /** ZCode ReadFileStateMap：会话内已读文件状态，seed 时重建、压缩后回放。 */
    private val readFileState: ReadFileState? = null,
    /** 上游 shell_environment_change 对齐：提供当前 shell 工作目录，跨回合变化时提示。 */
    private val shellEnvProvider: (() -> String)? = null,
    /** 上游 plan-file-continuity：压缩后返回已批准 plan 的引用提示体（无 plan 返回 null）。 */
    private val planFileReminderProvider: (suspend () -> String?)? = null,
    /**
     * When set, an ACTIVE goal turns the engine into ZCode's autonomous
     * delivery loop: each final answer triggers a goal-completion verifier
     * call; a failing verdict injects a continuation message and the loop
     * keeps working until the verifier passes, the token budget runs out, or
     * [maxGoalIterations] is hit. The engine also owns goal token accounting
     * in that mode ([managesGoalTokens]).
     */
    private val goalState: GoalToolState? = null,
    /** Safety cap on verifier-driven continuations for a single runTurn call. */
    private val maxGoalIterations: Int = 20,
    /** Local date source for the date_change reminder edge (test hook). */
    private val dateProvider: () -> String = { java.time.LocalDate.now().toString() },
) {
    private val history = mutableListOf<ApiMessage>().also { h ->
        baseSystemBlocks().forEach { h += ApiMessage(role = "system", content = it) }
    }
    private var extraTools: List<Tool> = emptyList()
    private val allTools get() = tools + extraTools
    private val toolsByName get() = allTools.associateBy { it.name }
    private var systemSuffix: String = ""
    private var persona: String = ""
    /** Meta-user context (agentsMd/skills/date); injected into the FIRST user message of a session. */
    private var metaUserContext: String = ""
    private var todoItemsProvider: (() -> String?)? = null
    private var lastAssistantCompletedAtMs: Long? = null
    private var turnCount: Int = 0
    /** Live read of plan-mode state; reminder injection keys off this. */
    private var planModeProvider: (() -> Boolean)? = null
    private var planReminderCount = 0
    private var humanTurnsSincePlanReminder = 0
    private var planWasEnabled = false
    /** 最近一次已知的本地日期（date_change reminder 的边沿检测）。 */
    private var lastLocalDate: String? = null
    private val goalVerifier by lazy {
        GoalVerifier(TracedLlm(client, ModelCallTrace.Source.GOAL_VERIFY), json)
    }
    /** Stream-observed tokens not yet committed to [GoalToolState]. */
    private var pendingGoalTokens: Int = 0

    /**
     * True when the engine owns goal token accounting (a [goalState] was
     * injected): every stream's usage — including continuation and verifier
     * calls — is folded into the goal directly. Callers must not add turn
     * usage again on top.
     */
    val managesGoalTokens: Boolean get() = goalState != null

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
        val e = ctx.reasoningOverride ?: settings.reasoningEffort
        if (e.isBlank()) return null
        val reasoning = ctx.modelMeta?.reasoning ?: return null
        if (reasoning.levels.isNotEmpty()) {
            // Data-driven catalog: pass the user value through (or the model's
            // default); the adapters' ReasoningRulesApplier resolves it onto
            // the wire, and "off" strips everything downstream.
            return if (e == "off") "off" else reasoning.resolveLevelId(e) ?: e
        }
        if (e == "off") return null
        return when (reasoning.style) {
            com.andmx.llm.provider.ReasoningStyle.NONE -> null
            com.andmx.llm.provider.ReasoningStyle.EFFORT ->
                if (e in reasoning.effortLevels) e else reasoning.defaultEffort
            com.andmx.llm.provider.ReasoningStyle.THINKING -> e
        }
    }

    private fun baseSystemBlocks(): List<String> =
        systemPromptBlocks.ifEmpty { listOf(systemPrompt) }

    private fun systemMessages(): List<ApiMessage> {
        val blocks = baseSystemBlocks().toMutableList()
        val extras = buildString {
            if (persona.isNotBlank()) append("\n\n# 语气\n以「$persona」的风格回应。")
            if (systemSuffix.isNotBlank()) append("\n\n# 用户自定义指令\n$systemSuffix")
        }
        if (extras.isNotBlank()) blocks[blocks.lastIndex] = blocks.last() + extras
        return blocks.map { ApiMessage(role = "system", content = it) }
    }

    private fun rewriteSystemPrefix() {
        var prefix = 0
        while (prefix < history.size && history[prefix].role == "system") prefix++
        repeat(prefix) { history.removeAt(0) }
        history.addAll(0, systemMessages())
    }

    /** Replace the split system-prompt blocks (settings/env refresh). */
    fun setSystemBlocks(blocks: List<String>) {
        systemPromptBlocks = blocks
        rewriteSystemPrefix()
    }

    /** Source of truth for plan-mode reminders (PlanModeState.active). */
    fun setPlanModeProvider(provider: (() -> Boolean)?) {
        planModeProvider = provider
    }

    /** Append project/custom instructions to the system prompt. */
    fun setCustomInstructions(text: String) {
        systemSuffix = text.trim()
        rewriteSystemPrefix()
    }

    /** Set the assistant persona/tone. */
    fun setPersona(p: String) {
        persona = p.trim()
        rewriteSystemPrefix()
    }

    /**
     * Set ZCode-style meta-user context (agentsMd, skills listing, current
     * date). Injected wrapped in <system-reminder> ahead of the user's text on
     * the first user turn only — later turns keep the system prefix stable.
     */
    fun setMetaUserContext(text: String) {
        metaUserContext = text.trim()
    }

    fun setTodoItemsProvider(provider: (() -> String?)?) {
        todoItemsProvider = provider
    }

    private fun composedSystem(): String =
        systemMessages().joinToString("\n\n") { it.content.orEmpty() }

    /** Public access to the composed system prompt (for rollout recording). */
    fun composedSystemPrompt(): String = composedSystem()

    /** Rebuild plan-reminder cadence counters after restoring a history. */
    private fun rescanPlanReminderState() {
        var count = 0
        var lastReminderIdx = -1
        history.forEachIndexed { i, m ->
            if (m.role == "system" && ZCodePrompts.isPlanModeReminder(m.content)) {
                count += 1
                lastReminderIdx = i
            }
        }
        planReminderCount = count
        humanTurnsSincePlanReminder = history.drop(lastReminderIdx + 1).count { it.role == "user" }
    }

    /** Reset the conversation history (used when loading a saved conversation). */
    fun seed(messages: List<ApiMessage>) {
        history.clear()
        history += systemMessages()
        val firstUser = messages.indexOfFirst { it.role == "user" }
        val injectMeta = metaUserContext.isNotBlank() && firstUser >= 0 &&
            messages.none { it.content?.contains(META_USER_REMINDER_PREFIX) == true }
        if (injectMeta) {
            history += messages.take(firstUser)
            history += ApiMessage(
                role = "system",
                content = SystemReminder.wrap(
                    SystemReminder.Source.CONTEXT_PREFIX,
                    metaUserContext,
                ).trimEnd(),
            )
            history += messages.drop(firstUser)
        } else {
            history += messages
        }
        rescanPlanReminderState()
        readFileState?.hydrate(messages)
        val goal = goalState?.goal
        if (messages.isNotEmpty() && goal != null && goal.isActivelyPursued) {
            lastGoalSig = goalSignature(goal)
            injectSystemReminder(
                SystemReminder.Source.RESUME_GOAL_STATE,
                "Resumed with an active goal: ${goal.text}" +
                    (goal.nextAction.takeIf { it.isNotBlank() }?.let { " Next: $it" } ?: ""),
            )
        } else {
            lastGoalSig = goal?.let { goalSignature(it) }
        }
    }

    /** Snapshot of the current history (used to preserve state across engine rebuilds). */
    fun snapshotHistory(): List<ApiMessage> = history.toList()

    /**
     * turnSteer: append a user message to the in-flight turn's history. The next
     * model step sees it alongside tool results, steering the current work
     * without waiting for the turn to end (ZCode 对齐).
     */
    fun injectUserMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        history += ApiMessage(
            role = "user",
            content = SystemReminder.wrap(SystemReminder.Source.INCOMING_MESSAGE, trimmed).trimEnd(),
        )
    }

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
        val isFirstUserTurn = history.none { it.role == "user" }
        val effectiveInput = if (isFirstUserTurn && metaUserContext.isNotBlank()) {
            SystemReminder.wrap(SystemReminder.Source.CONTEXT_PREFIX, metaUserContext)
                .trimEnd() + "\n\n$userInput"
        } else {
            userInput
        }
        history += ApiMessage(role = "user", content = effectiveInput, imageUrls = images.ifEmpty { null })
        turnCount += 1
        humanTurnsSincePlanReminder += 1
        injectDateChangeReminder()
        injectPlanModeReminder()
        injectGoalStateChangeReminder()
        injectShellEnvironmentReminder()
        loop(settings, turn)
    }

    private var lastGoalSig: String? = null
    private var lastShellEnv: String? = null

    private fun goalSignature(goal: com.andmx.agent.ConversationGoal): String =
        "${goal.text}|${goal.status}|${goal.nextAction}|${goal.goalIteration}"

    /** ZCode goal_state_change：目标objective/status/nextAction 变化时提示一次。 */
    private fun injectGoalStateChangeReminder() {
        val goal = goalState?.goal
        val sig = goal?.let { goalSignature(it) }
        if (sig == lastGoalSig) return
        val prev = lastGoalSig
        lastGoalSig = sig
        if (prev == null || goal == null) return
        injectSystemReminder(
            SystemReminder.Source.GOAL_STATE_CHANGE,
            "Goal state changed: ${goal.text} [${goal.status}]" +
                (goal.nextAction.takeIf { it.isNotBlank() }?.let { " — next: $it" } ?: ""),
        )
    }

    /** ZCode shell_environment_change：工作目录跨回合变化时提示一次。 */
    private fun injectShellEnvironmentReminder() {
        val current = shellEnvProvider?.invoke() ?: return
        val prev = lastShellEnv
        lastShellEnv = current
        if (prev == null || prev == current) return
        injectSystemReminder(
            SystemReminder.Source.SHELL_ENVIRONMENT_CHANGE,
            "Shell working directory changed from $prev to $current.",
        )
    }

    /**
     * ZCode runtime_mode reminder: while plan mode is active, re-attach the
     * plan contract every ≥5 real user turns (full text on the 1st and every
     * 5th attachment, sparse otherwise); on the plan→build edge emit the exit
     * reminder once. Delivered as a <system-reminder> after the user message.
     */
    private fun injectPlanModeReminder() {
        val planEnabled = planModeProvider?.invoke() == true
        if (planEnabled) {
            val due = planReminderCount == 0 ||
                humanTurnsSincePlanReminder >= ZCodePrompts.PLAN_MODE_REMINDER_TURNS_BETWEEN
            if (due) {
                planReminderCount += 1
                humanTurnsSincePlanReminder = 0
                val body = if (planReminderCount % ZCodePrompts.PLAN_MODE_FULL_REMINDER_EVERY_N == 1) {
                    ZCodePrompts.PLAN_MODE_FULL_REMINDER
                } else {
                    ZCodePrompts.PLAN_MODE_SPARSE_REMINDER
                }
                history += ApiMessage(
                    role = "system",
                    content = SystemReminder.wrap(SystemReminder.Source.RUNTIME_MODE, body),
                )
            }
        } else if (planWasEnabled) {
            history += ApiMessage(
                role = "system",
                content = SystemReminder.wrap(
                    SystemReminder.Source.PLAN_MODE_EXIT,
                    ZCodePrompts.PLAN_MODE_EXIT_REMINDER,
                ),
            )
        }
        planWasEnabled = planEnabled
    }

    /**
     * ZCode date_change reminder: 跨天的会话在每个新回合提示一次新日期
     * （runtime_local 生命周期——上游不落盘，AndMX 随 history 持久化）。
     */
    private fun injectDateChangeReminder() {
        val today = dateProvider()
        val prev = lastLocalDate
        lastLocalDate = today
        if (prev == null || prev == today) return
        history += ApiMessage(
            role = "system",
            content = SystemReminder.wrap(
                SystemReminder.Source.DATE_CHANGE,
                SystemReminder.buildDateChangeBody(today),
            ),
        )
    }

    /**
     * 任意来源的系统提示注入入口（子代理完成通知、环境变更等 mid_turn_event
     * 类）。写入 history，下一个模型步的 ChatRequest 自然携带。
     */
    fun injectSystemReminder(source: SystemReminder.Source, body: String) {
        if (body.isBlank()) return
        history += ApiMessage(role = "system", content = SystemReminder.wrap(source, body))
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

        val contextWindow = turn.modelMeta?.contextWindow?.takeIf { it > 0 }
            ?: ContextCompactor.DEFAULT_CONTEXT_WINDOW
        val maxOutputTokens = turn.modelMeta?.maxOutputTokens ?: 0
        val hardLimit = maxSteps + graceSteps

        // ZCode goal loop: while an ACTIVE goal exists, every final answer is
        // audited by the completion verifier; a failing verdict injects a
        // continuation message and a fresh step budget instead of ending.
        var continuations = 0
        var outputTokenContinuations = 0
        goalLoop@ while (true) {
        // A fresh user turn (or goal continuation) starts a fresh repeat
        // chain: an instruction that legitimately re-runs the previous
        // command is not a stuck loop.
        repeatGuard.reset()
        var convergenceHinted = false
        var step = 0
        while (step++ < hardLimit) {
            // ── Context management: local microcompact → soft compact → hard-limit fallback ──
            Microcompact.maybeMicrocompact(
                messages = history,
                estimatedTokens = compactor.estimateTokens(history),
                thresholdTokens = compactor.microcompactThresholdTokens(contextWindow, maxOutputTokens),
                lastAssistantCompletedAtMs = lastAssistantCompletedAtMs,
                nowMs = System.currentTimeMillis(),
            )?.let { cleared ->
                emit(
                    AgentEvent.AssistantDelta(
                        "\n_(已清理 ${cleared.clearedCount} 条旧工具结果释放上下文: ${cleared.tokensBefore}→${cleared.tokensAfter} tokens)_\n",
                    ),
                )
            }

            val overHardLimit = compactor.isContextWindowExceeded(history, contextWindow, maxOutputTokens)
            val compactDecision = compactor.autoCompactDecision(history, contextWindow, maxOutputTokens)
            if (overHardLimit || compactDecision.shouldCompact) {
                hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.PRE_COMPACT)
                val result = compactor.compact(history, settings, turn)
                if (result != null) {
                    compactor.noteAutoCompactOutcome(success = true)
                    val preservedReadPaths = ReadFileState.collectReadPaths(result.compacted, json)
                    val readReminders = readFileState?.postCompactReminders(preservedReadPaths).orEmpty()
                    val planReminder = planFileReminderProvider?.invoke()?.let {
                        SystemReminder.wrap(SystemReminder.Source.PLAN_FILE_REFERENCE, it).trimEnd()
                    }
                    readFileState?.clear()
                    history.clear()
                    history += result.compacted
                    readReminders.forEach { history += ApiMessage(role = "user", content = it) }
                    planReminder?.let { history += ApiMessage(role = "user", content = it) }
                    hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.POST_COMPACT)
                    emit(AgentEvent.AssistantDelta("\n_(上下文已自动压缩: 移除 ${result.removedCount} 条历史消息)_\n"))
                } else {
                    compactor.noteAutoCompactOutcome(success = false)
                    if (overHardLimit) {
                        // Compaction failed while over the hard limit — fall back to
                        // dropping the oldest messages instead of looping forever.
                        val dropped = dropOldestNonSystem(keepRecent = 8)
                        if (dropped > 0) {
                            emit(AgentEvent.AssistantDelta("\n_(压缩失败,已丢弃 $dropped 条旧消息以释放上下文)_\n"))
                        }
                    }
                }
            }

            // ── Step budget: nudge the model to converge as it runs out ──
            if (step == maxSteps && !convergenceHinted) {
                convergenceHinted = true
                history += ApiMessage(
                    role = "system",
                    content = SystemReminder.wrap(
                        SystemReminder.Source.MODEL_ANOMALY,
                        "步数即将用尽。请立即总结当前进度,完成收尾,不要再发起新的工具调用。",
                    ),
                )
            }

            if (TodoReminder.shouldRemind(history)) {
                history += ApiMessage(
                    role = "system",
                    content = SystemReminder.wrap(
                        SystemReminder.Source.TODO_REMINDER,
                        TodoReminder.reminderText(todoItemsProvider?.invoke()),
                    ),
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
            var firstTokenSent = false
            emit(AgentEvent.StepStarted(turnCount, step, System.currentTimeMillis()))
            val toolArgBuf = sortedMapOf<Int, StringBuilder>()
            val toolMeta = sortedMapOf<Int, Pair<String?, String?>>()
            val msg = streamWithRetry(
                request,
                onContent = {
                    if (!firstTokenSent) {
                        firstTokenSent = true
                        emit(AgentEvent.FirstToken(turnCount, step, System.currentTimeMillis()))
                    }
                    emit(AgentEvent.AssistantDelta(it))
                },
                onReasoning = {
                    sawReasoning = true
                    if (!firstTokenSent) {
                        firstTokenSent = true
                        emit(AgentEvent.FirstToken(turnCount, step, System.currentTimeMillis()))
                    }
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
            lastAssistantCompletedAtMs = System.currentTimeMillis()
            history += msg

            val calls = msg.toolCalls
            if (calls.isNullOrEmpty()) {
                // Output-token truncation (ZCode turn-output-token-continuation):
                // a reply cut at max tokens resumes in-place with a fixed
                // nudge, up to 3 times per turn; replies carrying tool calls
                // proceed normally since the calls are the continuation.
                if (msg.finishReason == "length") {
                    if (outputTokenContinuations < MAX_OUTPUT_TOKEN_CONTINUATIONS) {
                        outputTokenContinuations += 1
                        msg.content?.takeIf { it.isNotBlank() }?.let { emit(AgentEvent.Assistant(it)) }
                        history += ApiMessage(role = "user", content = OUTPUT_TOKEN_CONTINUE_PROMPT)
                        continue
                    }
                    msg.content?.takeIf { it.isNotBlank() }?.let { emit(AgentEvent.Assistant(it)) }
                    emit(AgentEvent.Failed("模型回复超出输出 token 上限"))
                    emit(AgentEvent.Done)
                    return
                }
                outputTokenContinuations = 0
                // Final answer — commit the text, then let an active goal's
                // completion verifier decide whether the turn may end.
                msg.content?.takeIf { it.isNotBlank() }?.let { emit(AgentEvent.Assistant(it)) }
                if (verifyGoalAndMaybeContinue(settings, turn, continuations)) {
                    continuations += 1
                    continue@goalLoop
                }
                flushGoalTokens()
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
                    noteRepeat(call)
                }
            } else {
                // Emit all ToolStarted first (serial, on the flow coroutine).
                calls.forEach { call ->
                    emit(AgentEvent.ToolStarted(call.id, call.function.name, call.function.arguments))
                }
                // ZCode tool scheduler: only concurrency-safe calls share a
                // wave; state-mutating/destructive calls form serial barriers
                // that preserve the model's requested order.
                val groups = mutableListOf<List<ApiToolCall>>()
                var pending = mutableListOf<ApiToolCall>()
                for (call in calls) {
                    if (toolsByName[call.function.name]?.concurrentSafe == true) {
                        pending += call
                    } else {
                        if (pending.isNotEmpty()) {
                            groups += pending
                            pending = mutableListOf()
                        }
                        groups += listOf(call)
                    }
                }
                if (pending.isNotEmpty()) groups += pending
                // Execute — NO emit inside async.
                val results = mutableListOf<Pair<ApiToolCall, ToolResult>>()
                for (group in groups) {
                    if (group.size == 1) {
                        results += group[0] to executeToolCall(group[0])
                    } else {
                        for (wave in group.chunked(MAX_PARALLEL_TOOL_CALLS)) {
                            coroutineScope {
                                results += wave.map { call ->
                                    async { call to executeToolCall(call) }
                                }.map { it.await() }
                            }
                        }
                    }
                }
                // Emit ToolFinished in order (serial, on the flow coroutine).
                for ((call, result) in results) {
                    emit(AgentEvent.ToolFinished(call.id, call.function.name, result.output, result.isError, result.imageUrls))
                    history += ApiMessage(role = "tool", content = trimToolOutput(result.output), toolCallId = call.id, name = call.function.name, imageUrls = result.imageUrls)
                    noteRepeat(call)
                }
            }
        }
        flushGoalTokens()
        emit(AgentEvent.Failed("已达最大步数 ($maxSteps) + 收敛宽限 ($graceSteps),任务未能完成"))
        emit(AgentEvent.Done)
        return
        }
    }

    /**
     * ZCode `target_completion_verification` + `goal-continuation` 对齐：
     * 对刚结束的回合跑一次独立 verifier 调用。
     * 返回 true 表示已注入续跑消息、外层 goalLoop 应继续；false 表示回合
     * 可结束（无活动目标 / 验证通过 / 预算耗尽 / 迭代上限 / 验证调用失败）。
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.verifyGoalAndMaybeContinue(
        settings: ProviderSettings,
        turn: TurnContext,
        continuations: Int,
    ): Boolean {
        val gs = goalState ?: return false
        val goal = gs.goal
        if (!goal.isActivelyPursued) return false
        val iteration = goal.goalIteration + 1
        emit(AgentEvent.GoalVerifying(iteration))
        val commandFailure = runGoalValidationCommands(goal, iteration)
        val result = if (commandFailure != null) GoalVerifier.Result(commandFailure, 0) else try {
            goalVerifier.verify(history.toList(), goal, turn, settings)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            emit(AgentEvent.Failed("目标验证调用失败: ${t.message ?: "未知错误"}"))
            return false
        }
        pendingGoalTokens += result.tokensUsed
        if (gs.goal != goal) {
            flushGoalTokens()
            return false
        }
        val now = System.currentTimeMillis()
        val verified = goal.copy(
            goalIteration = iteration,
            tokensUsed = goal.tokensUsed + pendingGoalTokens,
            timeUsedSeconds = if (goal.startedAt > 0) {
                ((now - goal.startedAt) / 1000L).coerceAtLeast(goal.timeUsedSeconds)
            } else {
                goal.timeUsedSeconds
            },
            lastVerifyReason = result.verdict.reason,
            nextAction = result.verdict.nextAction,
            updatedAt = now,
        )
        pendingGoalTokens = 0
        emit(
            AgentEvent.GoalVerified(
                iteration,
                result.verdict.passed,
                result.verdict.reason,
                result.verdict.nextAction,
            ),
        )
        if (result.verdict.passed) {
            gs.setGoal(
                verified.copy(
                    status = GoalStatus.COMPLETE,
                    phase = GoalStatus.COMPLETE.toPhase(),
                    nextAction = "",
                ),
            )
            return false
        }
        if (verified.isBudgetExhausted) {
            gs.setGoal(
                verified.copy(
                    status = GoalStatus.BUDGET_LIMITED,
                    phase = GoalStatus.BUDGET_LIMITED.toPhase(),
                ),
            )
            return false
        }
        if (continuations + 1 >= maxGoalIterations) {
            gs.setGoal(verified)
            return false
        }
        gs.setGoal(verified)
        history += ApiMessage(
            role = "user",
            content = SystemReminder.wrap(
                SystemReminder.Source.TARGET_CONTINUATION,
                goalVerifier.continuationPrompt(verified, result.verdict),
            ).trimEnd(),
        )
        return true
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.runGoalValidationCommands(
        goal: ConversationGoal,
        iteration: Int,
    ): GoalVerifier.Verdict? {
        if (goal.validationCommands.isEmpty()) return null
        if (goal.validationCommands.size > ValidationCommandsArg.MAX_COMMANDS ||
            goal.validationCommands.any { it.isBlank() || it.length > ValidationCommandsArg.MAX_COMMAND_LENGTH }) {
            return GoalVerifier.Verdict(false, "Invalid goal validation command configuration.", "Ask the user to correct the validation commands.")
        }
        if (goal.tokenBudget > 0 && goal.tokensUsed.toLong() + pendingGoalTokens >= goal.tokenBudget) {
            return GoalVerifier.Verdict(false, "Goal budget exhausted before command validation.", "Increase the goal budget to run validation.")
        }
        for ((index, command) in goal.validationCommands.withIndex()) {
            val call = com.andmx.llm.ApiToolCall(
                id = "goal-validation-${java.util.UUID.randomUUID()}",
                function = com.andmx.llm.ApiFunctionCall("run_shell", buildJsonObject {
                    put("command", command)
                    put("timeout_ms", 120_000)
                    put("max_output_chars", 8_000)
                    put("strict_cwd", true)
                }.toString()),
            )
            history += ApiMessage(role = "assistant", toolCalls = listOf(call))
            emit(AgentEvent.ToolStarted(call.id, call.function.name, call.function.arguments))
            val result = executeToolCall(call, validation = true).let {
                it.copy(output = it.output.take(8_000), imageUrls = null)
            }
            emit(AgentEvent.ToolFinished(call.id, call.function.name, result.output, result.isError))
            history += ApiMessage(role = "tool", content = result.output, toolCallId = call.id, name = call.function.name)
            if (result.isError) {
                return GoalVerifier.Verdict(
                    false,
                    "Validation command ${index + 1} failed in verification $iteration: ${result.output}",
                    "Resolve the validation command failure before completing the goal.",
                )
            }
            if (goalState?.goal != goal) return GoalVerifier.Verdict(false, "Goal changed during validation.", "Verify the updated goal.")
        }
        return null
    }

    /** Commit stream-observed tokens into the goal (engine-managed accounting). */
    private fun flushGoalTokens() {
        val gs = goalState ?: return
        val pending = pendingGoalTokens
        pendingGoalTokens = 0
        if (pending <= 0) return
        val g = gs.goal
        if (!g.hasGoal) return
        gs.setGoal(g.copy(tokensUsed = g.tokensUsed + pending, updatedAt = System.currentTimeMillis()))
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
            var streamUsage: com.andmx.llm.TokenUsage? = null
            val gotContent = try {
                client.chatStream(request).collect { ev ->
                    when (ev) {
                        is LlmStreamEvent.Content -> onContent(ev.delta)
                        is LlmStreamEvent.Reasoning -> onReasoning(ev.delta)
                        is LlmStreamEvent.ToolCallDelta -> onToolCall(ev.index, ev.id, ev.name, ev.argumentsDelta)
                        is LlmStreamEvent.Completed -> message = ev.message
                        is LlmStreamEvent.UsageUpdate -> {
                            streamUsage = ev.usage
                            pendingGoalTokens += ev.usage.totalTokens.takeIf { it > 0 }
                                ?: (ev.usage.inputTokens + ev.usage.outputTokens)
                        }
                    }
                }
                true
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastError = t.message ?: "请求失败"
                false
            }
            if (gotContent && message != null) {
                return streamUsage?.let { u -> message!!.copy(tokenUsage = u) } ?: message
            }
            // Empty reply on first attempt: retry once (possibly a lost first chunk).
            if (gotContent && message == null && attempt == 0) continue
            if (attempt < maxRetries) {
                val delayMs = 1000L shl attempt
                emit(AgentEvent.Retrying(attempt + 1, maxRetries, delayMs))
                kotlinx.coroutines.delay(delayMs)
            }
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
     * Cap a tool output fed back into history, keeping head and tail around an
     * elision marker when it exceeds [historyToolOutputLimit].
     */
    private fun trimToolOutput(output: String): String {
        if (output.length <= historyToolOutputLimit) return output
        val trimmed = TextTrimming.elide(output, historyToolOutputLimit) { omitted ->
            "\n…[截断 " + omitted + " 字符]…\n"
        }
        return trimmed + SystemReminder.wrap(
            SystemReminder.Source.TOOL_RESULT_WARNING,
            "Tool output was truncated to fit context. Re-run with narrower input or read the file with offset/limit if you need the omitted portion.",
        ).trimEnd()
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
    private suspend fun executeToolCall(call: com.andmx.llm.ApiToolCall, validation: Boolean = false): ToolResult {
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

            if (validation && effectiveArgs != parseArgs(call.function.arguments)) {
                return ToolResult("Validation command arguments were changed by a hook; verification refused.", isError = true)
            }
            val outcome = approve(tool, effectiveArgs)
            if (outcome !is ApprovalOutcome.AllowedOnce) {
                ToolResult(ApprovalOutcome.denialText(outcome), isError = true)
            } else {
                val raw = if (validation) {
                    kotlinx.coroutines.withTimeoutOrNull(120_000L) { invokeTool(tool, call, effectiveArgs) }
                        ?: ToolResult("Validation command timed out after 120000ms", isError = true)
                } else invokeTool(tool, call, effectiveArgs)
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

    /**
     * Run the tool body under the deadline the tool declares for itself.
     *
     * A tool that throws becomes an error result, but a cancellation that did
     * not come from this deadline propagates: reporting a stopped turn as a
     * tool failure would let the loop keep stepping after the user asked it to
     * stop, and would look to the model like a command error it should retry.
     */
    private suspend fun invokeTool(tool: Tool, call: com.andmx.llm.ApiToolCall, args: JsonObject): ToolResult =
        ToolTimeout.withDeadline(
            timeoutMs = tool.timeoutMs,
            onTimeout = { ToolResult(ToolTimeout.errorText(it), isError = true) },
        ) {
            try {
                if (tool is ExecutionAwareTool) tool.execute(call.id, args)
                else tool.execute(args)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                ToolResult("工具异常: ${t.message}", isError = true)
            }
        }

    /**
     * Feed one settled call to the repeat guard. Denied and failed calls reach
     * this too, which is the point: repeating a call that keeps being refused
     * is the loop most worth breaking.
     *
     * The reminder is a system message, so it corrects the model without adding
     * another card to a phone-sized transcript.
     */
    private fun noteRepeat(call: com.andmx.llm.ApiToolCall) {
        val reminder = repeatGuard.onCall(call.function.name, call.function.arguments) ?: return
        history += ApiMessage(role = "system", content = reminder)
    }

    companion object {
        const val META_USER_REMINDER_PREFIX = "<system-reminder>"

        /** Upstream tool scheduler cap on concurrent calls per wave. */
        const val MAX_PARALLEL_TOOL_CALLS = 10

        /** Upstream turn-output-token-continuation: max in-place resumes per turn. */
        const val MAX_OUTPUT_TOKEN_CONTINUATIONS = 3
        const val OUTPUT_TOKEN_CONTINUE_PROMPT =
            "Output token limit hit. Resume directly — no apology, no recap of " +
                "what you were doing. Pick up mid-thought if that is where the " +
                "cut happened. Break remaining work into smaller pieces."

        val DEFAULT_SYSTEM_PROMPT: String =
            com.andmx.agent.zcode.ZCodePrompts.IDENTITY + "\n\n" +
                com.andmx.agent.zcode.ZCodePrompts.CORE + "\n\n" +
                com.andmx.agent.zcode.ZCodePrompts.CRAFT
    }
}
