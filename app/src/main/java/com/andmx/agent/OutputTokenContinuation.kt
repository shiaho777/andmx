package com.andmx.agent

object OutputTokenContinuation {
    const val MAX = 3
    const val PROMPT =
        "Output token limit hit. Resume directly — no apology, no recap of " +
            "what you were doing. Pick up mid-thought if that is where the " +
            "cut happened. Break remaining work into smaller pieces."

    private val RAW_REASONS = setOf(
        "max_tokens",
        "max_output_tokens",
        "model_context_window_exceeded",
    )

    enum class Decision { CONTINUE, EXHAUSTED, NONE }

    fun classify(
        finishReason: String?,
        rawFinishReason: String?,
        toolCallCount: Int,
        continuationCount: Int,
    ): Decision {
        if (toolCallCount > 0) return Decision.NONE
        if (!isOutputTokenLimitFinishReason(finishReason, rawFinishReason)) return Decision.NONE
        return if (continuationCount < MAX) Decision.CONTINUE else Decision.EXHAUSTED
    }

    fun isOutputTokenLimitFinishReason(
        finishReason: String?,
        rawFinishReason: String?,
    ): Boolean {
        return finishReason == "length" || (rawFinishReason ?: "") in RAW_REASONS
    }

    fun isContinuationMessage(role: String, content: String?): Boolean =
        role == "user" && content == PROMPT

    fun withoutContinuationMessages(messages: List<com.andmx.llm.ApiMessage>): List<com.andmx.llm.ApiMessage> {
        if (messages.none { isContinuationMessage(it.role, it.content) }) return messages
        return messages.filterNot { isContinuationMessage(it.role, it.content) }
    }
}
