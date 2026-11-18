package com.andmx.ui2.chat

import com.andmx.agent.AgentEngine
import com.andmx.agent.AllowedPrompts
import com.andmx.agent.ApprovalOutcome
import com.andmx.agent.ApprovalRuleStore
import com.andmx.agent.GoalToolState
import com.andmx.agent.Tool
import com.andmx.agent.ToolArgs
import com.andmx.agent.ToolRisk
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.multi.SubAgentOrchestrator
import com.andmx.agent.zcode.AskQuestion
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.data.rollout.RolloutWriter
import com.andmx.llm.TokenUsageTracker
import com.andmx.ui2.settings.tr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

data class ApprovalRequest(
    val conversationId: Long,
    val toolName: String,
    val risk: ToolRisk,
    val summary: String,
    val modeLabel: String,
    val id: String = "appr-${System.currentTimeMillis()}",
    val createdAt: Long = System.currentTimeMillis(),
    val kind: String = "tool",
    val questions: List<AskQuestion> = emptyList(),
    val planText: String = "",
    val autoDeadlineAt: Long? = null,
    val countdownVisibleAt: Long? = null,
    /** 原始输入预览（命令/路径/URL 单行 mono 展示，上游 previewPermissionInput）。 */
    val inputPreview: String = "",
    /** 选择「允许本会话/本项目」时将持久化的规则描述（上游 approvalPermissionScopes）。 */
    val rulePreview: String = "",
    /** 审批原因（plan 模式拒绝等场景的描述字段）。 */
    val reason: String = "",
    val suggestions: List<com.andmx.agent.permission.PermissionSuggestion> = emptyList(),
)

/** 审批作用域（ZCode chat.permission 对齐）。 */
enum class ApprovalScope { ONCE, SESSION_ALLOW, SESSION_DENY, PROJECT_ALLOW, PROJECT_DENY, PROJECT_ASK }

internal class SessionControl {
    val rolloutWriters = ConcurrentHashMap<Long, RolloutWriter>()
    val tokenTrackers = ConcurrentHashMap<Long, TokenUsageTracker>()
    val allowedPromptsByConversation = ConcurrentHashMap<Long, AllowedPrompts.Grants>()
    val orchestrators = ConcurrentHashMap<Long, SubAgentOrchestrator>()
    val backgroundTasksByConversation = ConcurrentHashMap<Long, com.andmx.agent.BackgroundTasks>()
    val readFileStates = ConcurrentHashMap<Long, com.andmx.agent.ReadFileState>()
    val toolSearchRegistries = ConcurrentHashMap<Long, com.andmx.agent.ToolSearchRegistry>()
    val shellWatchJobs = ConcurrentHashMap<Long, Job>()
    val titleSidecarAttempted = ConcurrentHashMap.newKeySet<Long>()

    val sessions = ConcurrentHashMap<Long, Session>()
    val sessionAccessOrder = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val lastAccessAt = ConcurrentHashMap<Long, Long>()
    var focusedConversationIdProvider: () -> Long = { 0L }
    var queuedConversationIdsProvider: () -> Set<Long> = { emptySet() }

    fun touchAccess(conversationId: Long, now: Long = System.currentTimeMillis()) {
        lastAccessAt[conversationId] = now
    }

    fun protectedFacts(conversationId: Long): Set<String> {
        val facts = mutableSetOf<String>()
        if (isTurnRunning(conversationId)) facts += "running"
        if (pendingApprovalState.value?.conversationId == conversationId) facts += "interaction"
        val session = sessions[conversationId]
        if (session?.pending != null || session?.pendingAnswer != null) facts += "interaction"
        if (conversationId in queuedConversationIdsProvider()) facts += "queued"
        if (backgroundTasksByConversation[conversationId]?.list()
                ?.any { it.state == com.andmx.agent.BackgroundTasks.TaskState.RUNNING } == true
        ) facts += "background"
        if (orchestrators[conversationId]?.listAgents()?.any { (_, state) ->
                state == SubAgentOrchestrator.AgentState.RUNNING ||
                    state == SubAgentOrchestrator.AgentState.WAITING
            } == true
        ) facts += "subagent"
        if ((liveChatEvents[conversationId]?.subscriptionCount?.value ?: 0) > 0) facts += "subscriber"
        if (focusedConversationIdProvider() == conversationId) facts += "focused"
        return facts
    }

    val subAgentWatchJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    val approvalRulesByConversation = ConcurrentHashMap<Long, ConcurrentHashMap<String, ApprovalScope>>()
    val liveChatEvents = ConcurrentHashMap<Long, MutableSharedFlow<ChatEvent>>()
    val runningConversations = ConcurrentHashMap.newKeySet<Long>()
    val turnJobs = ConcurrentHashMap<Long, Job>()
    val sessionToolQueues = ConcurrentHashMap<Long, MutableList<String>>()
    val sessionToolDrainJobs = ConcurrentHashMap<Long, Job>()
    val sessionSendLedger = com.andmx.agent.zcode.SessionRequestLedger()

    private val _runningIds = MutableStateFlow<Set<Long>>(emptySet())
    val runningIds: StateFlow<Set<Long>> = _runningIds.asStateFlow()

    val automationTurnIds = ConcurrentHashMap.newKeySet<Long>()
    val offPeakTurnIds = ConcurrentHashMap.newKeySet<Long>()
    val automationIdsByConversation = ConcurrentHashMap<Long, String>()

    val dynamicActorHistoryCache = ConcurrentHashMap<String, String>()
    val dynamicActorCounts = ConcurrentHashMap<String, Int>()
    val dynamicAskJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    val dynamicActorPersonas = ConcurrentHashMap<String, String>()

    val sessionTraces = ConcurrentHashMap<Long, String>()

    val pendingApprovalState = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = pendingApprovalState.asStateFlow()

    fun isTurnRunning(conversationId: Long): Boolean =
        turnJobs[conversationId]?.isActive == true || runningConversations.contains(conversationId)

    fun activeTurnJob(conversationId: Long): Job? =
        turnJobs[conversationId]?.takeIf { it.isActive }

    private fun publishRunning() {
        _runningIds.value = runningConversations.toSet()
    }

    fun beginTurn(conversationId: Long) {
        runningConversations += conversationId
        touchAccess(conversationId)
        publishRunning()
    }

    fun endTurn(conversationId: Long) {
        runningConversations -= conversationId
        touchAccess(conversationId)
        publishRunning()
    }

    fun cancelTurnJob(conversationId: Long) {
        turnJobs[conversationId]?.cancel()
    }

    fun startTurnJob(
        conversationId: Long,
        scope: CoroutineScope,
        acquire: () -> Unit,
        release: () -> Unit,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            beginTurn(conversationId)
            acquire()
            try {
                block()
            } finally {
                release()
                endTurn(conversationId)
            }
        }
        turnJobs[conversationId] = job
        job.invokeOnCompletion {
            if (turnJobs[conversationId] === job) turnJobs.remove(conversationId)
        }
        job.start()
        return job
    }

    fun liveBus(conversationId: Long): MutableSharedFlow<ChatEvent> =
        liveChatEvents.getOrPut(conversationId) {
            MutableSharedFlow(extraBufferCapacity = 64)
        }

    fun sideEvents(conversationId: Long): SharedFlow<ChatEvent> =
        liveBus(conversationId)

    fun emitLiveEvent(conversationId: Long, event: ChatEvent): Boolean =
        liveBus(conversationId).tryEmit(event)

    fun clearLiveEvents(conversationId: Long) {
        liveChatEvents.remove(conversationId)
    }

    fun addAllowedPrompts(conversationId: Long, entries: List<AllowedPrompts.Entry>) {
        allowedPromptsByConversation.getOrPut(conversationId) { AllowedPrompts.Grants() }
            .addAll(entries)
    }

    fun bashGrantRules(conversationId: Long, command: String): List<com.andmx.agent.permission.PermissionRuleValue> {
        val grants = allowedPromptsByConversation[conversationId]
        return if (grants != null && AllowedPrompts.grantsFor(command, grants.promptsFor("Bash"))) {
            listOf(com.andmx.agent.permission.PermissionRuleValue("Bash", command))
        } else {
            emptyList()
        }
    }

    /** 审批面板展示用：本会话/项目级允许将持久化的规则描述（上游 scope 行）。 */
    fun approvalRulePreview(toolName: String, args: String): String {
        val key = approvalRuleKey(toolName, args) ?: return ""
        return when {
            key.endsWith(":any") -> "所有 ${ToolArgs.canonical(toolName)} 调用"
            key.contains(":prefix:") -> "命令前缀: ${key.substringAfter(":prefix:")} …"
            key.contains(":file:") -> "文件: ${key.substringAfter(":file:")}"
            else -> key
        }
    }

    /** 规则键：命令类按前缀（首个 token），文件/其它按工具名 + 摘要 key。 */
    fun approvalRuleKey(toolName: String, args: String): String? {
        val canonical = ToolArgs.canonical(toolName)
        return when (canonical) {
            "shell", "git" -> {
                val command = ToolArgs.shellCommand(toolName, args)
                if (command.isBlank()) null
                else "$canonical:prefix:${command.trim().split(Regex("\\s+")).first().lowercase()}"
            }
            "read", "write", "edit", "multiedit", "patch", "list", "grep", "glob" -> {
                val path = ToolArgs.filePath(toolName, args)
                if (path.isBlank()) null
                else "$canonical:file:${path.trimEnd('*')}"
            }
            else -> "$canonical:any"
        }
    }

    fun sessionRuleFor(conversationId: Long, toolName: String, args: String): ApprovalScope? {
        val rule = approvalRuleKey(toolName, args) ?: return null
        val scoped = approvalRulesByConversation[conversationId]?.get(rule)
        if (scoped != null && scoped != ApprovalScope.ONCE) return scoped
        // 文件规则同时命中同路径其它编辑操作；命令前缀规则在 approvalRuleKey 已归一
        return null
    }

    /** 权限规则的匹配主体：命令原文 / 文件路径 / url，与 ruleContent 对齐比较。 */
    private fun ruleSubject(toolName: String, args: JsonObject): String {
        val canonical = ToolArgs.canonical(toolName)
        return when (canonical) {
            "shell", "git" -> {
                val desc = args["description"]?.jsonPrimitive?.content.orEmpty()
                val cmd = ToolArgs.shellCommand(toolName, args.toString()).ifBlank {
                    args["command"]?.jsonPrimitive?.content.orEmpty()
                }
                if (desc.isNotBlank()) "$desc\n$cmd" else cmd
            }
            else -> ToolArgs.filePath(toolName, args.toString()).ifBlank {
                args["url"]?.jsonPrimitive?.content
                    ?: args["path"]?.jsonPrimitive?.content
                    ?: args["file_path"]?.jsonPrimitive?.content
                    ?: args["pattern"]?.jsonPrimitive?.content
                    ?: canonical
            }
        }
    }

    private fun buildApprovalSummary(tool: Tool, args: JsonObject): String =
        ChatControllerLogic.approvalSummary(
            toolName = tool.name,
            preview = runCatching {
                com.andmx.agent.ToolArgs.preview(tool.name, args.toString())
            }.getOrDefault(""),
            rawArgs = args.toString(),
        )

    suspend fun prompt(
        conversationId: Long,
        tool: Tool,
        args: JsonObject,
        mode: ExecMode,
        reason: String? = null,
        notify: suspend (Long, String, String) -> Unit,
        launchPermissionHook: (String, String) -> Unit,
    ): ApprovalOutcome {
        // No live session means no UI can answer the question. That is
        // not a refusal, and the model can only tell the difference if
        // the two are reported differently.
        val session = sessions[conversationId]
            ?: return ApprovalOutcome.Unavailable(tr("会话不可用"))
        val deferred = CompletableDeferred<Boolean>()
        session.pending = deferred
        session.pendingArgs = args.toString()
        touchAccess(conversationId)
        val summary = buildApprovalSummary(tool, args) + (reason?.let { "（$it）" } ?: "")
        pendingApprovalState.value = ApprovalRequest(
            conversationId = conversationId,
            toolName = tool.name,
            risk = tool.risk,
            summary = summary,
            modeLabel = mode.label,
            inputPreview = ruleSubject(tool.name, args).take(160),
            rulePreview = approvalRulePreview(tool.name, args.toString()),
            reason = reason.orEmpty(),
            suggestions = runCatching {
                com.andmx.agent.permission.PermissionSuggestions.suggest(tool.name, args)
            }.getOrDefault(emptyList()),
        )
        notify(conversationId, tr("等待确认"), summary)
        // 上游 PermissionRequest hook：与审批 UI 并行触发（外部审批桥接语义），不阻塞 deferred
        launchPermissionHook(tool.name, args.toString())
        return if (deferred.await()) ApprovalOutcome.AllowedOnce else ApprovalOutcome.Rejected()
    }

    /**
     * 带作用域的审批：本会话允许/拒绝会为该会话登记一条 grant/deny 规则，
     * 后续相同 (tool, key) 的请求不再弹窗；PROJECT_ALLOW 额外写入持久存储
     * （ZCode allowForProject 对齐），跨会话生效。
     */
    fun resolveApprovalScoped(
        allow: Boolean,
        scope: ApprovalScope,
        persistProjectRule: (String, String, ApprovalRuleStore.RuleBehavior) -> Unit,
        persistGrants: (Long) -> Unit,
    ) {
        val req = pendingApprovalState.value
        pendingApprovalState.value = null
        req?.let { touchAccess(it.conversationId) }
        val session = req?.let { sessions[it.conversationId] }
        val allowed = allow && scope != ApprovalScope.SESSION_DENY && scope != ApprovalScope.PROJECT_DENY
        if (req != null && session != null && scope != ApprovalScope.ONCE) {
            val rule = approvalRuleKey(req.toolName, session.pendingArgs.orEmpty())
            if (rule != null) {
                approvalRulesByConversation
                    .computeIfAbsent(req.conversationId) { ConcurrentHashMap() }[rule] = scope
                val behavior = when (scope) {
                    ApprovalScope.PROJECT_ALLOW -> ApprovalRuleStore.RuleBehavior.ALLOW
                    ApprovalScope.PROJECT_DENY -> ApprovalRuleStore.RuleBehavior.DENY
                    ApprovalScope.PROJECT_ASK -> ApprovalRuleStore.RuleBehavior.ASK
                    else -> null
                }
                if (behavior != null) {
                    val key = rule.split(':', limit = 2)
                    persistProjectRule(key.first(), rule, behavior)
                }
                if (scope == ApprovalScope.SESSION_ALLOW) {
                    val parts = rule.split(':', limit = 2)
                    session.permissions.grantSessionAllow(
                        com.andmx.agent.ApprovalRuleStore.wireToolName(parts.first()),
                        com.andmx.agent.ApprovalRuleStore.ruleContentOf(
                            com.andmx.agent.ApprovalRuleStore.Rule(parts.first(), rule),
                        ),
                    )
                }
                if (scope == ApprovalScope.SESSION_DENY) {
                    val parts = rule.split(':', limit = 2)
                    session.permissions.denyForSession(
                        com.andmx.agent.ApprovalRuleStore.wireToolName(parts.first()),
                        com.andmx.agent.ApprovalRuleStore.ruleContentOf(
                            com.andmx.agent.ApprovalRuleStore.Rule(parts.first(), rule),
                        ),
                    )
                }
                if (scope == ApprovalScope.SESSION_ALLOW || scope == ApprovalScope.SESSION_DENY) {
                    persistGrants(req.conversationId)
                }
            }
        }
        session?.pending?.complete(allowed)
        session?.pending = null
        session?.pendingArgs = null
        if (session?.pendingAnswer != null) {
            session.pendingAnswer?.complete(
                if (allowed) """{"answers":{"__default__":"approved"}}""" else """{"answers":{"__default__":"rejected"}}"""
            )
            session.pendingAnswer = null
            clearAnswerTimer(session)
        }
    }

    fun resolveUserQuestion(answersJson: String) {
        val req = pendingApprovalState.value
        pendingApprovalState.value = null
        req?.let { touchAccess(it.conversationId) }
        val session = req?.let { sessions[it.conversationId] }
        session?.pendingAnswer?.complete(answersJson)
        session?.pendingAnswer = null
        clearAnswerTimer(session)
        session?.pending?.complete(true)
        session?.pending = null
    }

    /** 用户点「稍后」：停掉倒计时，问题无限期挂起直到作答或取消。 */
    fun snoozeUserQuestion() {
        val req = pendingApprovalState.value ?: return
        if (req.kind != "ask_user") return
        val session = sessions[req.conversationId] ?: return
        snoozeAnswerTimer(session)
        pendingApprovalState.value = req.copy(autoDeadlineAt = null, countdownVisibleAt = null)
    }

    fun snoozeAllAnswerTimers() {
        sessions.values.forEach { snoozeAnswerTimer(it) }
        val cur = pendingApprovalState.value
        if (cur?.kind == "ask_user" && cur.autoDeadlineAt != null) {
            pendingApprovalState.value = cur.copy(autoDeadlineAt = null, countdownVisibleAt = null)
        }
    }

    private fun snoozeAnswerTimer(session: Session) {
        session.answerSnoozed = true
        session.answerTimer?.cancel()
        session.answerTimer = null
    }

    fun clearAnswerTimer(session: Session?) {
        session?.answerTimer?.cancel()
        session?.answerTimer = null
        session?.answerSnoozed = false
    }

    fun beginAskApproval(
        session: Session,
        questions: List<AskQuestion>,
        autoResolve: Boolean,
        hiddenGraceMs: Long,
        autoResolveMs: Long,
    ): Pair<CompletableDeferred<String>, String> {
        val deferred = CompletableDeferred<String>()
        session.pendingAnswer = deferred
        session.answerSnoozed = false
        touchAccess(session.conversationId)
        val summary = questions.joinToString(" · ") { q ->
            q.header.ifBlank { q.question.take(24) }
        }.ifBlank { tr("需要你的决定") }
        val startedAt = System.currentTimeMillis()
        pendingApprovalState.value = ApprovalRequest(
            conversationId = session.conversationId,
            toolName = "AskUserQuestion",
            risk = ToolRisk.READ,
            summary = summary,
            modeLabel = session.approvalMode.label,
            kind = "ask_user",
            questions = questions,
            autoDeadlineAt = if (autoResolve) startedAt + autoResolveMs else null,
            countdownVisibleAt = if (autoResolve) startedAt + hiddenGraceMs else null,
        )
        return deferred to summary
    }

    suspend fun autoResolveAnswer(
        conversationId: Long,
        session: Session,
        deferred: CompletableDeferred<String>,
        delayMs: Long,
    ) {
        delay(delayMs)
        if (!session.answerSnoozed && deferred.isActive) {
            deferred.complete("""{"answers":{}}""")
            val cur = pendingApprovalState.value
            if (cur?.kind == "ask_user" && cur.conversationId == conversationId) {
                pendingApprovalState.value = null
            }
        }
    }

    fun beginPlanApproval(
        session: Session,
        toolName: String,
        summary: String,
        kind: String,
        planText: String = "",
    ): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        session.pending = deferred
        touchAccess(session.conversationId)
        pendingApprovalState.value = ApprovalRequest(
            conversationId = session.conversationId,
            toolName = toolName,
            risk = ToolRisk.READ,
            summary = summary,
            modeLabel = session.approvalMode.label,
            kind = kind,
            planText = planText,
        )
        return deferred
    }

    fun beginWorkflowEscalationApproval(
        session: Session,
        runId: String,
        question: String,
    ): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        session.pendingAnswer = deferred
        touchAccess(session.conversationId)
        pendingApprovalState.value = ApprovalRequest(
            conversationId = session.conversationId,
            toolName = "escalate",
            risk = ToolRisk.READ,
            summary = "workflow $runId: $question",
            modeLabel = session.approvalMode.label,
            kind = "ask_user",
            questions = listOf(
                AskQuestion(
                    header = "workflow",
                    question = question,
                    options = emptyList(),
                ),
            ),
        )
        return deferred
    }

    fun cancelPendingApproval(conversationId: Long, session: Session) {
        if (pendingApprovalState.value?.conversationId == conversationId) {
            pendingApprovalState.value = null
            session.pending?.complete(false)
            session.pending = null
        }
    }

    fun clearPendingApprovalFor(conversationId: Long) {
        if (pendingApprovalState.value?.conversationId == conversationId) {
            pendingApprovalState.value = null
        }
    }

    fun cancelSessionApproval(session: Session?) {
        session?.pending?.complete(false)
        session?.pending = null
        session?.pendingAnswer?.complete("""{"answers":{"__default__":"cancelled"}}""")
        session?.pendingAnswer = null
        clearAnswerTimer(session)
    }
}

internal class Session(
    val conversationId: Long,
    var engine: AgentEngine,
    var providerId: String,
    var model: String,
    val planTool: UpdatePlanTool,
    val goalState: GoalToolState,
    val todoState: TodoState = TodoState(),
    val planModeState: PlanModeState = PlanModeState(),
    var approvalMode: ExecMode,
    val permissions: com.andmx.agent.permission.PermissionService = com.andmx.agent.permission.PermissionService(),
    var pending: CompletableDeferred<Boolean>? = null,
    var pendingArgs: String? = null,
    var pendingAnswer: CompletableDeferred<String>? = null,
    var answerTimer: Job? = null,
    var answerSnoozed: Boolean = false,
    val turnToolOutputs: MutableList<Pair<String, String>> = mutableListOf(),
    var lastUserText: String = "",
    var lastAssistantText: String = "",
    var lastAssistantTurnId: String? = null,
)
