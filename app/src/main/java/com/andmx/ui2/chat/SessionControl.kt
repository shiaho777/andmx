package com.andmx.ui2.chat

import com.andmx.agent.AgentEngine
import com.andmx.agent.AllowedPrompts
import com.andmx.agent.GoalToolState
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.multi.SubAgentOrchestrator
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.data.rollout.RolloutWriter
import com.andmx.llm.TokenUsageTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

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

    val subAgentWatchJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    val approvalRulesByConversation = ConcurrentHashMap<Long, ConcurrentHashMap<String, ChatController.ApprovalScope>>()
    val liveChatEvents = ConcurrentHashMap<Long, MutableSharedFlow<ChatEvent>>()
    val runningConversations = ConcurrentHashMap.newKeySet<Long>()
    val turnJobs = ConcurrentHashMap<Long, Job>()

    val automationTurnIds = ConcurrentHashMap.newKeySet<Long>()
    val offPeakTurnIds = ConcurrentHashMap.newKeySet<Long>()
    val automationIdsByConversation = ConcurrentHashMap<Long, String>()

    val dynamicActorHistoryCache = ConcurrentHashMap<String, String>()
    val dynamicActorCounts = ConcurrentHashMap<String, Int>()
    val dynamicAskJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    val dynamicActorPersonas = ConcurrentHashMap<String, String>()

    val sessionTraces = ConcurrentHashMap<Long, String>()
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
