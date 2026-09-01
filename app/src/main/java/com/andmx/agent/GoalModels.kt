package com.andmx.agent

/**
 * Goal status — mirrors Codex's thread_goals.status enum exactly.
 *
 * The agent transitions between these via create_goal/update_goal tools and
 * /goal commands. The UI renders a different color/label for each.
 */
enum class GoalStatus(val label: String) {
    ACTIVE("运行中"),
    PAUSED("已暂停"),
    BLOCKED("已阻塞"),
    USAGE_LIMITED("达到用量上限"),
    BUDGET_LIMITED("达到预算上限"),
    COMPLETE("已完成"),
    EMPTY("未设置");

    /** Map to the legacy GoalPhase for backward-compatible code paths. */
    fun toPhase(): GoalPhase = when (this) {
        ACTIVE -> GoalPhase.RUNNING
        PAUSED -> GoalPhase.PAUSED
        BLOCKED -> GoalPhase.FAILED
        USAGE_LIMITED -> GoalPhase.FAILED
        BUDGET_LIMITED -> GoalPhase.FAILED
        COMPLETE -> GoalPhase.READY
        EMPTY -> GoalPhase.EMPTY
    }
}

/** Legacy phase enum — kept for compatibility; new code uses [GoalStatus]. */
enum class GoalPhase { EMPTY, RUNNING, PAUSED, READY, WAITING_APPROVAL, NEEDS_SETUP, FAILED }

data class ConversationGoal(
    val text: String = "",
    val status: GoalStatus = GoalStatus.EMPTY,
    val phase: GoalPhase = GoalPhase.EMPTY,
    val tokenBudget: Int = 0,
    val tokensUsed: Int = 0,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val note: String = "",
) {
    val hasGoal: Boolean get() = text.isNotBlank()
    /** Remaining token budget, or 0 if no budget set. */
    val remainingBudget: Int get() = if (tokenBudget > 0) (tokenBudget - tokensUsed).coerceAtLeast(0) else 0
    /** True when a budget is set and has been exhausted. */
    val isBudgetExhausted: Boolean get() = tokenBudget > 0 && tokensUsed >= tokenBudget
}
