package com.andmx.agent

class ModelAnomalyState {
    var warnings: Int = 0
    var signature: String? = null
    var streak: Int = 0
    var toolCallCount: Int = 0

    fun reset() {
        warnings = 0
        signature = null
        streak = 0
        toolCallCount = 0
    }
}

data class RepeatedToolCallWarning(
    val toolName: String,
    val observedCount: Int,
    val threshold: Int,
    val warningInjected: Boolean,
)

fun detectRepeatedToolCallWarnings(
    toolName: String,
    input: String,
    state: ModelAnomalyState,
    threshold: Int = 3,
    maxWarnings: Int = 3,
): RepeatedToolCallWarning? {
    if (threshold <= 0) return null
    val signature = "$toolName:$input"
    if (state.signature == signature) {
        state.streak += 1
    } else {
        state.signature = signature
        state.streak = 1
    }
    if (state.streak != threshold) return null
    val injected = state.warnings < maxWarnings
    if (injected) state.warnings += 1
    return RepeatedToolCallWarning(toolName, state.streak, threshold, injected)
}

fun repeatedToolCallReminder(toolName: String, observedCount: Int): String = listOf(
    "You have called $toolName with the same input $observedCount times in a row.",
    "Do not repeat the exact same tool call again unless the user explicitly asked you to retry it unchanged.",
    "Use the existing result to take a different next step, explain the blocker, or ask the user for guidance.",
).joinToString("\n")
