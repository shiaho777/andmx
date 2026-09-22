package com.andmx.ui2.chat

sealed class ChatEvent {
    data class UserMessage(val text: String) : ChatEvent()
    data class AssistantChunk(val text: String) : ChatEvent()
    data class ReasoningChunk(val text: String) : ChatEvent()
    data object ReasoningDone : ChatEvent()
    data class AssistantComplete(val fullText: String) : ChatEvent()
    data class ToolCallArgsDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val args: String,
    ) : ChatEvent()
    data class ToolCallStarted(val id: String, val name: String, val args: String) : ChatEvent()
    data class ToolCallFinished(
        val id: String,
        val output: String,
        val isError: Boolean,
        val imageUrls: List<String>? = null,
        val durationMs: Long = 0,
    ) : ChatEvent()
    data class PlanUpdated(val steps: List<PlanStepUi>) : ChatEvent()
    data class ApprovalRequested(
        val id: String,
        val toolName: String,
        val summary: String,
        val modeLabel: String,
    ) : ChatEvent()
    data class ApprovalResolved(val id: String, val allowed: Boolean) : ChatEvent()
    data class SubAgentStarted(val agentId: String, val task: String) : ChatEvent()
    data class SubAgentDelta(val agentId: String, val text: String) : ChatEvent()
    data class SubAgentToolActivity(
        val agentId: String,
        val toolName: String,
        val detail: String,
        val running: Boolean,
    ) : ChatEvent()
    data class SubAgentCompleted(val agentId: String, val result: String) : ChatEvent()
    data class SubAgentFailed(val agentId: String, val error: String) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
    /** 模型请求重试中（上游 network attempt 行）。 */
    data class Retrying(val attempt: Int, val maxAttempts: Int, val delayMs: Long) : ChatEvent()

    /** Goal 完成度验证开始（第 [iteration] 轮）。 */
    data class GoalVerifying(val iteration: Int) : ChatEvent()

    /** Goal 完成度验证结果：未通过时引擎注入续跑消息继续工作。 */
    data class GoalVerified(
        val iteration: Int,
        val passed: Boolean,
        val reason: String,
        val nextAction: String,
    ) : ChatEvent()
    data object Done : ChatEvent()

    /** step 首包/首 token 时间戳（dsh StatsLine 的 TTFT/吞吐指标来源）。 */
    data class StepStarted(val turn: Int, val step: Int, val startedAtMs: Long) : ChatEvent()
    data class FirstToken(val turn: Int, val step: Int, val atMs: Long) : ChatEvent()
}

data class PlanStepUi(
    val content: String,
    val status: String,
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val sortKey: Long = id,
    val isProcess: Boolean = false,
    val createdAt: Long = sortKey,
    val completedAt: Long = 0L,
    /** 0 无, 1 赞, -1 踩（上游 assistant-feedback）。 */
    val feedback: Int = 0,
)

data class ToolCall(
    val id: String,
    val name: String,
    val args: String,
    val output: String? = null,
    val isRunning: Boolean = true,
    val isError: Boolean = false,
    val sortKey: Long = System.currentTimeMillis(),
    val imageUrls: List<String>? = null,
    val durationMs: Long = 0,
)

data class ApprovalItem(
    val id: String,
    val toolName: String,
    val summary: String,
    val modeLabel: String,
    val status: String = "pending",
    val sortKey: Long = System.currentTimeMillis(),
)


data class ReasoningItem(
    val id: String,
    val content: String,
    val isStreaming: Boolean = true,
    val sortKey: Long = System.currentTimeMillis(),
)

/** 一次 goal 完成度验证的时间线条目。 */
data class GoalVerifyItem(
    val iteration: Int,
    val passed: Boolean? = null,
    val reason: String = "",
    val nextAction: String = "",
    val sortKey: Long = System.currentTimeMillis(),
)
