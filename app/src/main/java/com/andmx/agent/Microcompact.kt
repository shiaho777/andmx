package com.andmx.agent

import com.andmx.llm.ApiMessage

/**
 * ZCode-style local microcompact: replace old tool-result payloads with a
 * marker instead of calling the LLM. Triggered by token pressure or idle time;
 * keeps the most recent tool results intact and never touches errors or media.
 */
object Microcompact {
    const val CLEARED_MARKER = "[Old tool result content cleared]"
    val COMPACTABLE_TOOLS = setOf(
        "Read", "Bash", "Grep", "Glob", "WebFetch", "WebSearch", "Edit", "Write", "ApplyPatch",
    )
    const val KEEP_RECENT_TOOL_RESULTS = 5
    const val IDLE_THRESHOLD_MINUTES = 60
    const val MIN_TOKEN_SAVINGS = 256

    /** Fraction of the context window at which token-pressure kicks in, minus [HEADROOM_TOKENS]. */
    const val PRESSURE_RATIO = 0.9f
    const val HEADROOM_TOKENS = 2_000

    enum class Trigger { TIME_BASED, TOKEN_PRESSURE }

    data class Result(
        val trigger: Trigger,
        val clearedCount: Int,
        val keptCount: Int,
        val tokensBefore: Int,
        val tokensAfter: Int,
    ) {
        val tokensSaved: Int get() = tokensBefore - tokensAfter
    }

    fun thresholdTokens(contextWindow: Int): Int {
        val byRatio = (contextWindow * PRESSURE_RATIO).toInt()
        return minOf(byRatio, (contextWindow - HEADROOM_TOKENS).coerceAtLeast(0))
    }

    fun resolveTrigger(
        estimatedTokens: Int,
        thresholdTokens: Int,
        lastAssistantCompletedAtMs: Long?,
        nowMs: Long,
    ): Trigger? {
        if (lastAssistantCompletedAtMs != null &&
            nowMs - lastAssistantCompletedAtMs > IDLE_THRESHOLD_MINUTES * 60_000L
        ) {
            return Trigger.TIME_BASED
        }
        if (thresholdTokens > 0 && estimatedTokens >= thresholdTokens) return Trigger.TOKEN_PRESSURE
        return null
    }

    /**
     * Group indexes of compactable tool-result messages. Groups are split at
     * assistant messages carrying tool calls so a call/result pair is never
     * partially cleared. Error results and results with images are skipped.
     */
    fun compactableGroups(
        messages: List<ApiMessage>,
        clearErrorResults: Boolean = false,
        compactableTools: Set<String> = COMPACTABLE_TOOLS,
    ): List<List<Int>> {
        val groups = mutableListOf<List<Int>>()
        var current: MutableList<Int>? = null
        messages.forEachIndexed { index, msg ->
            val compactable = msg.role == "tool" &&
                !msg.toolCallId.isNullOrBlank() &&
                msg.name in compactableTools &&
                (clearErrorResults || (!msg.content.orEmpty().startsWith("Error") && !msg.content.orEmpty().startsWith("error"))) &&
                msg.content != CLEARED_MARKER &&
                msg.imageUrls.isNullOrEmpty()
            when {
                msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty() -> current = null
                compactable -> current?.add(index) ?: mutableListOf(index).also { current = it; groups.add(it) }
            }
        }
        return groups
    }

    fun maybeMicrocompact(
        messages: MutableList<ApiMessage>,
        estimatedTokens: Int,
        thresholdTokens: Int,
        lastAssistantCompletedAtMs: Long?,
        nowMs: Long,
    ): Result? {
        val trigger = resolveTrigger(estimatedTokens, thresholdTokens, lastAssistantCompletedAtMs, nowMs)
            ?: return null
        val groups = compactableGroups(messages)
        val keep = KEEP_RECENT_TOOL_RESULTS.coerceAtLeast(1)
        val toClear = groups.dropLast(minOf(keep, groups.size)).flatten()
        if (toClear.isEmpty()) return null
        for (index in toClear) {
            val msg = messages.getOrNull(index) ?: continue
            messages[index] = msg.copy(content = CLEARED_MARKER, imageUrls = null)
        }
        val tokensAfter = TokenEstimate.forCompaction(messages)
        val saved = estimatedTokens - tokensAfter
        if (saved < MIN_TOKEN_SAVINGS) return null
        return Result(
            trigger = trigger,
            clearedCount = toClear.size,
            keptCount = groups.takeLast(keep).flatten().size,
            tokensBefore = estimatedTokens,
            tokensAfter = tokensAfter,
        )
    }
}
