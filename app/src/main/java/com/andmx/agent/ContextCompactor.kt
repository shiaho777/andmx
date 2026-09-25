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

    /**
     * Estimate if compaction is needed based on token count, using ZCode's
     * current preflight budget math: effective window = context − min(output
     * reserve, 21K); threshold = effective − 13K buffer.
     */
    data class AutoCompactDecision(
        val shouldCompact: Boolean,
        val reason: String,
        val tokenCount: Int,
        val tokenSource: String,
        val estimatedTokenCount: Int,
        val threshold: Int,
    )

    private var consecutiveAutoCompactFailures = 0

    fun noteAutoCompactOutcome(success: Boolean) {
        consecutiveAutoCompactFailures = if (success) 0 else consecutiveAutoCompactFailures + 1
    }

    /** ZCode hasEnoughMessagesToCompact：≥2 个 assistant 起始回合 + ≥1 条 assistant。 */
    fun hasEnoughMessagesToCompact(history: List<ApiMessage>): Boolean {
        val nonSystem = history.dropWhile { it.role == "system" }
        return nonSystem.count { it.role == "assistant" } >= 2
    }

    private fun providerUsageTokenOverride(history: List<ApiMessage>): Pair<Int, Int>? {
        val idx = history.indexOfLast { it.role == "assistant" && it.tokenUsage != null && it.tokenUsage!!.inputTokens > 0 }
        if (idx < 0) return null
        val base = history[idx].tokenUsage!!.inputTokens
        val incremental = estimateTokens(history.subList(idx, history.size))
        return base + incremental to base
    }

    /** estimateCurrentModelInputTokens：usage 基线 + 增量估算，无 usage 时纯估算。 */
    fun currentInputTokens(history: List<ApiMessage>): Int =
        providerUsageTokenOverride(history)?.first ?: estimateTokens(history)

    /**
     * ZCode shouldAutoCompact 对齐：provider usage 覆盖 → 门槛判定前先做
     * 消息量门槛与连败熔断。reason: disabled|not_enough_messages|
     * circuit_breaker|below_threshold|above_threshold。
     */
    fun autoCompactDecision(
        history: List<ApiMessage>,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
        maxOutputTokens: Int = 0,
    ): AutoCompactDecision {
        val estimated = estimateTokens(history)
        val tokenCount = currentInputTokens(history)
        val source = if (providerUsageTokenOverride(history) != null) "provider_usage" else "estimate"
        val threshold = if (autoCompactTokenLimit > 0) autoCompactTokenLimit
        else autoCompactThresholdTokens(effectiveContextWindow(contextWindow, maxOutputTokens))
        fun decision(should: Boolean, reason: String) = AutoCompactDecision(
            shouldCompact = should, reason = reason, tokenCount = tokenCount,
            tokenSource = source, estimatedTokenCount = estimated, threshold = threshold,
        )
        if (!hasEnoughMessagesToCompact(history)) return decision(false, "not_enough_messages")
        if (consecutiveAutoCompactFailures >= MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES) {
            return decision(false, "circuit_breaker")
        }
        return if (tokenCount >= threshold) decision(true, "above_threshold")
        else decision(false, "below_threshold")
    }

    fun needsCompaction(
        history: List<ApiMessage>,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
        maxOutputTokens: Int = 0,
    ): Boolean = autoCompactDecision(history, contextWindow, maxOutputTokens).shouldCompact

    fun isContextWindowExceeded(
        history: List<ApiMessage>,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
        maxOutputTokens: Int = 0,
    ): Boolean = currentInputTokens(history) > effectiveContextWindow(contextWindow, maxOutputTokens)

    fun effectiveContextWindow(contextWindow: Int, maxOutputTokens: Int = 0): Int =
        (contextWindow.toLong() - outputReserveTokens(contextWindow, maxOutputTokens)).toInt().coerceAtLeast(0)

    private fun outputReserveTokens(contextWindow: Int, maxOutputTokens: Int): Long {
        val declared = maxOutputTokens.takeIf { it > 0 } ?: OUTPUT_RESERVE_TOKENS
        return minOf(declared, PREFLIGHT_OUTPUT_RESERVE_TOKENS, contextWindow).toLong()
    }

    fun autoCompactThresholdTokens(effectiveWindow: Int): Int =
        (effectiveWindow - BUFFER_TOKENS).coerceAtLeast(0)

    fun microcompactThresholdTokens(contextWindow: Int, maxOutputTokens: Int = 0): Int =
        Microcompact.thresholdTokens(
            autoCompactThresholdTokens(effectiveContextWindow(contextWindow, maxOutputTokens)),
        )

    /** Estimate total tokens in the conversation history. */
    fun estimateTokens(history: List<ApiMessage>): Int = TokenEstimate.forCompaction(history)

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
        instructions: String = "",
    ): CompactionResult? {
        if (history.size <= keepRecentMessages + 1) return null

        val tokensBefore = estimateTokens(history)

        // Find the system message(s) at the start — contiguous prefix only;
        // mid-history system reminders must not be mistaken for the prefix.
        val systemMsgs = history.takeWhile { it.role == "system" }
        val nonSystem = history.drop(systemMsgs.size)

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
        val compactPrompt = buildCompactionPrompt(toCompact) +
            if (instructions.isNotBlank()) {
                "\n\n--- Additional summary instructions from the user ---\n$instructions"
            } else {
                ""
            }
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
            val raw = result.getOrNull()?.content ?: return null
            val summary = extractSummary(raw)

            val compacted = systemMsgs +
                ApiMessage(
                    role = "user",
                    content = buildCompactSummaryMessage(
                        summary,
                        recentMessagesPreserved = true,
                        suppressFollowup = true,
                        transcriptPath = turn.transcriptPath,
                    ),
                ) +
                toKeep

            val tokensAfter = estimateTokens(compacted)
            recordEvent("auto_compact", tokensBefore, tokensAfter, toCompact.size, turn.model)
            CompactionResult(compacted, toCompact.size, summary, tokensBefore, tokensAfter)
        }.getOrNull()
    }

    private fun extractSummary(raw: String): String {
        var formatted = raw.trim()
        if (formatted.isEmpty()) return ""
        formatted = formatted.replaceFirst(ANALYSIS_REGEX, "")
        val match = SUMMARY_REGEX.find(formatted)
        if (match != null) {
            formatted = formatted.replaceFirst(SUMMARY_REGEX, "Summary:\n" + match.groupValues[1].trim())
        }
        return formatted.replace(Regex("\n\n+"), "\n\n").trim()
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
        val systemMsgs = history.takeWhile { it.role == "system" }
        val nonSystem = history.drop(systemMsgs.size)

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
        appendLine("--- Conversation history so far ---")
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    appendLine("[User]")
                    msg.content?.let { appendLine(it.take(600)) }
                }
                "assistant" -> {
                    if (msg.toolCalls?.isNotEmpty() == true) {
                        appendLine("[Assistant - tool calls]")
                        for (call in msg.toolCalls) {
                            appendLine("  → ${call.function.name}(${call.function.arguments.take(200)})")
                        }
                    }
                    msg.content?.let {
                        if (it.isNotBlank()) {
                            appendLine("[Assistant]")
                            appendLine(it.take(400))
                        }
                    }
                }
                "tool" -> {
                    appendLine("[Tool result${msg.name.orEmpty().takeIf { n -> n.isNotBlank() }?.let { " · $it" }.orEmpty()}]")
                    msg.content?.let { appendLine(it.take(300)) }
                }
            }
            appendLine()
        }
        appendLine()
        append(COMPACT_REMINDER)
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
        const val DEFAULT_CONTEXT_WINDOW = 200_000
        const val OUTPUT_RESERVE_TOKENS = 32_000
        /** Upstream caps the preflight output reserve at 21K for threshold math. */
        const val PREFLIGHT_OUTPUT_RESERVE_TOKENS = 21_000
        const val BUFFER_TOKENS = 13_000
        const val MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3

        private val SUMMARY_REGEX = Regex(
            "<summary>\\s*([\\s\\S]*?)\\s*</summary>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val ANALYSIS_REGEX = Regex(
            "<analysis>\\s*[\\s\\S]*?\\s*</analysis>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun buildCompactSummaryMessage(
            summary: String,
            recentMessagesPreserved: Boolean = false,
            suppressFollowup: Boolean = false,
            transcriptPath: String? = null,
        ): String {
            var message = "This session is being continued from a previous conversation that ran out of context. " +
                "The summary below covers the earlier portion of the conversation.\n\n$summary"
            if (recentMessagesPreserved) {
                message += "\n\nRecent messages are preserved verbatim."
            }
            if (!transcriptPath.isNullOrBlank()) {
                message += "\n\nIf you need specific details from before compaction (like exact code snippets, " +
                    "error messages, or content you generated), read the full transcript at: $transcriptPath"
            }
            if (suppressFollowup) {
                message += "\nContinue the conversation from where it left off without asking the user any further questions. " +
                    "Resume directly — do not acknowledge the summary, do not recap what was happening, " +
                    "do not preface with \"I'll continue\" or similar. Pick up the last task as if the break never happened."
            }
            return message
        }

        private const val COMPACT_REMINDER =
            "\n\nREMINDER: Do NOT call any tools. Respond with plain text only — an <analysis> block " +
                "followed by a <summary> block. Tool calls will be rejected and you will fail the task."

        const val CHECKPOINT_SYSTEM_PROMPT =
            "你正在执行一个 CONTEXT CHECKPOINT COMPACTION。" +
                "为另一个将接手此任务的 LLM 创建一份详细的交接摘要。" +
                "摘要必须足够完整，使接手者无需阅读原始对话即可继续工作。" +
                "保留所有关键的技术细节、文件路径、错误信息和验证结果。"

        private const val NO_TOOLS_PREAMBLE = """CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.

- Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.
- You already have all the context you need in the conversation above.
- Tool calls will be REJECTED and will waste your only turn — you will fail the task.
- Your entire response must be plain text: an <analysis> block followed by a <summary> block.

"""

        /** Verbatim from upstream compact/prompt.ts buildCompactPrompt: 9-section summary contract. */
        val COMPACTION_SYSTEM_PROMPT = NO_TOOLS_PREAMBLE + """
Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
This summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.

Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:

1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
   - The user's explicit requests and intents
   - Your approach to addressing the user's requests
   - Key decisions, technical concepts and code patterns
   - Specific details like:
     - file names
     - full code snippets
     - function signatures
     - file edits
   - Errors that you ran into and how you fixed them
   - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
   - Note any security-relevant instructions or constraints the user stated (e.g., sensitive files or data to avoid, operations that must not be performed, credential or secret handling rules). These MUST be preserved verbatim in the summary so they continue to apply after compaction.
2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.

Your summary should include the following sections:

1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail
2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.
3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Pay special attention to the most recent messages and include full code snippets where applicable and include a summary of why this file read or edit is important.
4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
6. All user messages: List ALL user messages that are not tool results. These are critical for understanding the users' feedback and changing intent. Preserve any security-relevant instructions or constraints verbatim so they remain in effect after compaction.
7. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.
8. Current Work: Describe in detail precisely what was being worked on immediately before this summary request, paying special attention to the most recent messages from both user and assistant. Include file names and code snippets where applicable.
9. Optional Next Step: List the next step that you will take that is related to the most recent work you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent explicit requests, and the task you were working on immediately before this summary request. If your last task was concluded, then only list next steps if they are explicitly in line with the users request. Do not start on tangential requests or really old requests that were already completed without confirming with the user first.
                       If there is a next step, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off. This should be verbatim to ensure there's no drift in task interpretation.

Here's an example of how your output should be structured:

<example>
<analysis>
[Your thought process, ensuring all points are covered thoroughly and accurately]
</analysis>

<summary>
1. Primary Request and Intent:
   [Detailed description]

2. Key Technical Concepts:
   - [Concept 1]
   - [Concept 2]
   - [...]

3. Files and Code Sections:
   - [File Name 1]
      - [Summary of why this file is important]
      - [Summary of the changes made to this file, if any]
      - [Important Code Snippet]
   - [File Name 2]
      - [Important Code Snippet]
   - [...]

4. Errors and fixes:
    - [Detailed description of error 1]:
      - [How you fixed the error]
      - [User feedback on the error if any]
    - [...]

5. Problem Solving:
   [Description of solved problems and ongoing troubleshooting]

6. All user messages:
    - [Detailed non tool use user message]
    - [...]

7. Pending Tasks:
   - [Task 1]
   - [Task 2]
   - [...]

8. Current Work:
   [Precise description of current work]

9. Optional Next Step:
   [Optional Next step to take]

</summary>
</example>

Please provide your summary based on the conversation so far, following this structure and ensuring precision and thoroughness in your response.

There may be additional summarization instructions provided in the included context. If so, remember to follow these instructions when creating the above summary. Examples of instructions include:
<example>
## Compact Instructions
When summarizing the conversation focus on typescript code changes and also remember the mistakes you made and how you fixed them.
</example>

<example>
# Summary instructions
When you are using compact - please focus on test output and code changes. Include file reads verbatim.
</example>""".trimIndent()
    }
}
