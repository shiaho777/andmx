package com.andmx.agent

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Context compaction — mirrors Codex's auto_compact / compact_remote_v2 mechanism.
 *
 * When the conversation history exceeds the token budget, this module:
 * 1. Identifies the oldest messages that can be compressed
 * 2. Preserves critical context: tool calls with their results, file changes,
 *    user goals, and error patterns
 * 3. Sends a compaction request to the LLM asking for a structured summary
 *    (or a "context checkpoint handoff" for cross-session resume)
 * 4. Replaces the old messages with the summary + recent context
 *
 * Key design decisions (learned from Codex):
 * - Tool call + tool result pairs are kept together (never split)
 * - The last assistant message with tool_calls is always kept (so the LLM
 *   doesn't try to re-issue calls it already made)
 * - System messages are never compacted
 * - The compaction prompt explicitly asks for structured output
 * - Supports both "auto_compact" (in-flight) and "checkpoint" (handoff) modes
 * - Token limit is configurable via [autoCompactTokenLimit]; 0 = auto-detect from model
 */
class ContextCompactor(
    private val client: LlmApi,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** Trigger compaction when estimated tokens exceed this fraction of the context window. */
    private val compactThreshold: Float = 0.75f,
    /** Keep this many recent messages uncompressed. */
    private val keepRecentMessages: Int = 8,
    /** Maximum messages to include in the compaction prompt. */
    private val maxMessagesToCompact: Int = 30,
    /**
     * Auto-compact token limit. If > 0, overrides the context-window-derived threshold.
     * Mirrors Codex's `model_auto_compact_token_limit` config field.
     */
    private val autoCompactTokenLimit: Int = 0,
    /** Optional custom compaction prompt file content (mirrors Codex's experimental_compact_prompt_file). */
    private val customCompactPrompt: String = "",
) {
    /** Compaction events for telemetry/UI. */
    private val _compactionEvents = MutableStateFlow<List<CompactionEvent>>(emptyList())
    val compactionEvents: StateFlow<List<CompactionEvent>> = _compactionEvents

    data class CompactionEvent(
        val type: String,        // "auto_compact" | "checkpoint" | "context_window_exceeded"
        val timestamp: Long,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val removedCount: Int,
        val model: String,
    )

    data class CompactionResult(
        val compacted: List<ApiMessage>,
        val removedCount: Int,
        val summary: String,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val isCheckpoint: Boolean = false,
    )

    /** Estimate if compaction is needed based on token count. */
    fun needsCompaction(history: List<ApiMessage>, contextWindow: Int = 128_000): Boolean {
        val tokens = estimateTokens(history)
        val limit = if (autoCompactTokenLimit > 0) {
            autoCompactTokenLimit
        } else {
            (contextWindow * compactThreshold).toInt()
        }
        return tokens > limit
    }

    /** Check if the context window has been hard-exceeded (mirrors Codex's context_window_exceeded). */
    fun isContextWindowExceeded(history: List<ApiMessage>, contextWindow: Int = 128_000): Boolean {
        return estimateTokens(history) > contextWindow
    }

    /** Estimate total tokens in the conversation history. */
    fun estimateTokens(history: List<ApiMessage>): Int {
        var total = 0
        for (msg in history) {
            total += estimateMessageTokens(msg)
        }
        return total + 2000 // overhead for tool definitions, etc.
    }

    private fun estimateMessageTokens(msg: ApiMessage): Int {
        var tokens = 4
        msg.content?.let { tokens += TokenEstimate.estimate(it) }
        msg.toolCalls?.forEach { call ->
            tokens += TokenEstimate.estimate(call.function.name)
            tokens += TokenEstimate.estimate(call.function.arguments)
        }
        return tokens
    }

    /**
     * Compact the conversation history by summarizing old messages.
     *
     * Algorithm:
     * 1. Keep system messages (index 0)
     * 2. Keep the last [keepRecentMessages] messages (preserving tool call pairs)
     * 3. Compact everything in between
     * 4. Ensure we don't split a tool_call from its tool_result
     */
    suspend fun compact(
        history: List<ApiMessage>,
        @Suppress("UNUSED_PARAMETER") settings: ProviderSettings,
        turn: TurnContext,
    ): CompactionResult? {
        if (history.size <= keepRecentMessages + 1) return null

        val tokensBefore = estimateTokens(history)

        // Find the system message(s) at the start
        val systemEnd = history.indexOfLast { it.role == "system" } + 1
        val systemMsgs = history.take(systemEnd)
        val nonSystem = history.drop(systemEnd)

        if (nonSystem.size <= keepRecentMessages) return null

        // Find a safe split point: don't cut in the middle of a tool_call → tool_result pair
        var keepCount = keepRecentMessages
        val splitIndex = nonSystem.size - keepCount
        // If the message at splitIndex is a tool_result, move the boundary back to include
        // the corresponding assistant message with tool_calls
        var adjustedSplit = splitIndex
        while (adjustedSplit > 0 && nonSystem[adjustedSplit].role == "tool") {
            adjustedSplit--
        }
        // Also check if the message before is an assistant with tool_calls
        if (adjustedSplit > 0 && nonSystem[adjustedSplit - 1].toolCalls?.isNotEmpty() == true) {
            adjustedSplit--
        }

        val toCompact = nonSystem.take(adjustedSplit).take(maxMessagesToCompact)
        val toKeep = nonSystem.drop(adjustedSplit)

        if (toCompact.isEmpty()) return null

        // Build a structured compaction prompt
        val compactPrompt = buildCompactionPrompt(toCompact)
        val systemPrompt = if (customCompactPrompt.isNotBlank()) customCompactPrompt else COMPACTION_SYSTEM_PROMPT

        val request = ChatRequest(
            model = turn.model,
            messages = listOf(
                ApiMessage(role = "system", content = systemPrompt),
                ApiMessage(role = "user", content = compactPrompt),
            ),
        )

        return runCatching {
            val result = client.chat(request)
            val summary = result.getOrNull()?.content ?: return null

            val compacted = systemMsgs +
                ApiMessage(role = "user", content = "[上下文摘要] 以下是之前对话的关键信息:\n\n$summary") +
                toKeep

            val tokensAfter = estimateTokens(compacted)
            recordEvent("auto_compact", tokensBefore, tokensAfter, toCompact.size, turn.model)
            CompactionResult(compacted, toCompact.size, summary, tokensBefore, tokensAfter)
        }.getOrNull()
    }

    /**
     * Create a "context checkpoint handoff" — a comprehensive summary designed
     * for another LLM to resume the task. Mirrors Codex's CONTEXT CHECKPOINT COMPACTION.
     *
     * Unlike [compact] which is in-flight and keeps recent messages, this produces
     * a full handoff document that replaces the entire conversation history.
     * Used when:
     * - The session is being suspended and will be resumed later
     * - The context window is hard-exceeded and aggressive compaction is needed
     * - A sub-agent needs to inherit the full context
     */
    suspend fun createCheckpoint(
        history: List<ApiMessage>,
        turn: TurnContext,
        goal: String = "",
    ): CompactionResult? {
        val tokensBefore = estimateTokens(history)
        val systemEnd = history.indexOfLast { it.role == "system" } + 1
        val systemMsgs = history.take(systemEnd)
        val nonSystem = history.drop(systemEnd)

        if (nonSystem.isEmpty()) return null

        val checkpointPrompt = buildCheckpointPrompt(nonSystem, goal)
        val request = ChatRequest(
            model = turn.model,
            messages = listOf(
                ApiMessage(role = "system", content = CHECKPOINT_SYSTEM_PROMPT),
                ApiMessage(role = "user", content = checkpointPrompt),
            ),
        )

        return runCatching {
            val result = client.chat(request)
            val summary = result.getOrNull()?.content ?: return null

            val compacted = systemMsgs +
                ApiMessage(role = "user", content = "[上下文检查点] 以下是完整的任务交接摘要:\n\n$summary")

            val tokensAfter = estimateTokens(compacted)
            recordEvent("checkpoint", tokensBefore, tokensAfter, nonSystem.size, turn.model)
            CompactionResult(compacted, nonSystem.size, summary, tokensBefore, tokensAfter, isCheckpoint = true)
        }.getOrNull()
    }

    private fun recordEvent(type: String, tokensBefore: Int, tokensAfter: Int, removed: Int, model: String) {
        _compactionEvents.value = (_compactionEvents.value + CompactionEvent(type, System.currentTimeMillis(), tokensBefore, tokensAfter, removed, model)).takeLast(20)
    }

    private fun buildCompactionPrompt(messages: List<ApiMessage>): String = buildString {
        appendLine("请将以下对话历史压缩为结构化摘要。")
        appendLine()
        appendLine("必须保留:")
        appendLine("1. 用户的目标、约束和偏好")
        appendLine("2. 关键发现和决策（包括为什么做某个决定）")
        appendLine("3. 已完成的文件变更（文件名 + 变更类型）")
        appendLine("4. 工具调用的重要结果（特别是错误和修复方案）")
        appendLine("5. 待完成的任务和下一步")
        appendLine("6. 已验证的事实验证结果")
        appendLine()
        appendLine("可以丢弃:")
        appendLine("- 冗长的工具输出（只保留关键信息）")
        appendLine("- 重复的上下文和中间思考过程")
        appendLine("- 已被后续信息覆盖的过时内容")
        appendLine()
        appendLine("输出格式:")
        appendLine("## 目标")
        appendLine("## 关键发现")
        appendLine("## 文件变更")
        appendLine("## 工具结果摘要")
        appendLine("## 待办事项")
        appendLine()
        appendLine("--- 对话历史 ---")
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    appendLine("[用户]")
                    msg.content?.let { appendLine(it.take(300)) }
                }
                "assistant" -> {
                    if (msg.toolCalls?.isNotEmpty() == true) {
                        appendLine("[助手 - 工具调用]")
                        for (call in msg.toolCalls) {
                            appendLine("  → ${call.function.name}(${call.function.arguments.take(150)})")
                        }
                    }
                    msg.content?.let {
                        if (it.isNotBlank()) {
                            appendLine("[助手]")
                            appendLine(it.take(300))
                        }
                    }
                }
                "tool" -> {
                    appendLine("[工具结果]")
                    msg.content?.let { appendLine(it.take(200)) }
                }
            }
            appendLine()
        }
    }

    private fun buildCheckpointPrompt(messages: List<ApiMessage>, goal: String): String = buildString {
        appendLine("你正在执行一个 CONTEXT CHECKPOINT COMPACTION（上下文检查点压缩）。")
        appendLine("为另一个将接手此任务的 LLM 创建一份交接摘要。")
        appendLine()
        if (goal.isNotBlank()) {
            appendLine("## 当前目标")
            appendLine(goal)
            appendLine()
        }
        appendLine("交接摘要必须包含:")
        appendLine("1. **项目状态**: 当前工作目录、已初始化的环境、依赖状态")
        appendLine("2. **目标与进展**: 原始目标、已完成的部分、当前进度")
        appendLine("3. **关键决策**: 做了哪些重要决策及原因")
        appendLine("4. **文件变更清单**: 每个变更文件的路径、变更类型和摘要")
        appendLine("5. **验证结果**: 运行过的测试/构建/lint 及其结果")
        appendLine("6. **已知问题**: 已发现但未修复的问题")
        appendLine("7. **下一步**: 建议的继续方向和优先级")
        appendLine("8. **上下文指针**: 关键文件路径、行号、函数名等精确定位信息")
        appendLine()
        appendLine("摘要应该足够详细，使接手的 LLM 无需重新阅读对话历史即可继续工作。")
        appendLine()
        appendLine("--- 完整对话历史 ---")
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    appendLine("[用户]")
                    msg.content?.let { appendLine(it.take(500)) }
                }
                "assistant" -> {
                    if (msg.toolCalls?.isNotEmpty() == true) {
                        appendLine("[助手 - 工具调用]")
                        for (call in msg.toolCalls) {
                            appendLine("  → ${call.function.name}(${call.function.arguments.take(200)})")
                        }
                    }
                    msg.content?.let {
                        if (it.isNotBlank()) {
                            appendLine("[助手]")
                            appendLine(it.take(500))
                        }
                    }
                }
                "tool" -> {
                    appendLine("[工具结果]")
                    msg.content?.let { appendLine(it.take(300)) }
                }
            }
            appendLine()
        }
    }

    companion object {
        const val COMPACTION_SYSTEM_PROMPT =
            "你是一个对话压缩助手。将冗长的对话历史压缩为结构化摘要，" +
                "保留关键信息（目标、发现、变更、错误、待办），丢弃冗余内容。" +
                "按指定格式输出。"

        const val CHECKPOINT_SYSTEM_PROMPT =
            "你正在执行一个 CONTEXT CHECKPOINT COMPACTION。" +
                "为另一个将接手此任务的 LLM 创建一份详细的交接摘要。" +
                "摘要必须足够完整，使接手者无需阅读原始对话即可继续工作。" +
                "保留所有关键的技术细节、文件路径、错误信息和验证结果。"
    }
}
