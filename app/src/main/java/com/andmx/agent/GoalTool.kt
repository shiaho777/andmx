package com.andmx.agent

import com.andmx.agent.ConversationGoal
import com.andmx.agent.GoalStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Agent-side goal management tools — mirrors Codex's create_goal / update_goal /
 * get_goal.
 *
 * These let the agent autonomously set and track a long-running objective with
 * an optional token budget. The UI observes [onGoalChange] and re-renders.
 *
 * Codex tool specs (from binary strings):
 * - create_goal(objective, token_budget?): starts a new active goal
 * - update_goal(objective?, status?, token_budget?): updates the current goal
 * - get_goal(): returns current objective, status, budgets, usage
 */
class GoalToolState {
    @Volatile var goal: ConversationGoal = ConversationGoal()
        internal set

    @Volatile var onGoalChange: ((ConversationGoal) -> Unit)? = null

    fun setGoal(g: ConversationGoal) {
        goal = g
        onGoalChange?.invoke(g)
    }
}

/**
 * create_goal — start a new active goal or replace the current one.
 *
 * Mirrors Codex: "This starts a new active goal when no goal exists or
 * replaces the current goal when it is complete."
 */
class CreateGoalTool(
    private val state: GoalToolState,
) : Tool {
    override val name = "create_goal"
    override val description = "Set a concrete objective to pursue. Starts a new active goal or replaces the current goal when it is complete. Optionally set a token budget."
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("objective") {
                put("type", "string")
                put("description", "Required. The concrete objective to start pursuing.")
            }
            putJsonObject("token_budget") {
                put("type", "integer")
                put("description", "Positive token budget for the new goal. Omit unless explicitly requested.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("objective")) }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val objective = args["objective"]?.toString()?.trim()?.trim('"') ?: ""
        if (objective.isBlank()) return ToolResult("objective is required", isError = true)
        val budget = args["token_budget"]?.toString()?.trim()?.trim('"')?.toIntOrNull() ?: 0
        val now = System.currentTimeMillis()
        state.setGoal(
            ConversationGoal(
                text = objective,
                status = GoalStatus.ACTIVE,
                phase = GoalStatus.ACTIVE.toPhase(),
                tokenBudget = budget,
                startedAt = now,
                updatedAt = now,
            ),
        )
        val budgetText = if (budget > 0) " (budget: $budget tokens)" else ""
        return ToolResult("Goal created: $objective$budgetText")
    }
}

/**
 * update_goal — update the current goal's objective, status, or budget.
 *
 * Mirrors Codex: "Update the current goal. Can change objective, status, or
 * token_budget. Report the final consumed token budget to the user after
 * update_goal succeeds."
 */
class UpdateGoalTool(
    private val state: GoalToolState,
) : Tool {
    override val name = "update_goal"
    override val description = "Update the current goal's objective, status, or token budget. Completion is decided by the runtime's completion verifier after each turn — do not mark a goal complete yourself."
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("objective") {
                put("type", "string")
                put("description", "New objective text. Omit to keep the current one.")
            }
            putJsonObject("status") {
                put("type", "string")
                put("description", "New status: active, paused, blocked. Completion is set by the runtime verifier, not by this tool.")
                putJsonArray("enum") {
                    add(JsonPrimitive("active")); add(JsonPrimitive("paused"))
                    add(JsonPrimitive("blocked"))
                }
            }
            putJsonObject("token_budget") {
                put("type", "integer")
                put("description", "New token budget. Omit to keep the current one.")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val cur = state.goal
        if (!cur.hasGoal) return ToolResult("No active goal to update. Use create_goal first.", isError = true)
        val objective = args["objective"]?.toString()?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
        val statusStr = args["status"]?.toString()?.trim()?.trim('"')
        val budget = args["token_budget"]?.toString()?.trim()?.trim('"')?.toIntOrNull()
        if (statusStr?.lowercase() == "complete") {
            return ToolResult(
                "Completion is decided by the runtime's completion verifier after this turn; finish the work and answer normally.",
                isError = true,
            )
        }
        val newStatus = when (statusStr?.lowercase()) {
            "active" -> GoalStatus.ACTIVE
            "paused" -> GoalStatus.PAUSED
            "blocked" -> GoalStatus.BLOCKED
            else -> cur.status
        }
        state.setGoal(
            cur.copy(
                text = objective ?: cur.text,
                status = newStatus,
                phase = newStatus.toPhase(),
                tokenBudget = budget ?: cur.tokenBudget,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val parts = mutableListOf<String>()
        if (objective != null) parts += "objective updated"
        if (statusStr != null) parts += "status → ${newStatus.label}"
        if (budget != null) parts += "budget → $budget tokens"
        return ToolResult(if (parts.isEmpty()) "No changes" else parts.joinToString(", "))
    }
}

/**
 * get_goal — return the current goal with status, budgets, and usage.
 *
 * Mirrors Codex: "Get the current goal for this thread, including status,
 * budgets, token and elapsed-time usage, and remaining token budget."
 */
class GetGoalTool(
    private val state: GoalToolState,
) : Tool {
    override val name = "get_goal"
    override val description = "Get the current goal including status, token budget, and usage."
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

    override suspend fun execute(args: JsonObject): ToolResult {
        val g = state.goal
        if (!g.hasGoal) return ToolResult("No goal is currently set.")
        val parts = mutableListOf(
            "Current session goal state (authoritative):",
            "Status: ${g.status.name.lowercase()}",
            "Tokens used: ${g.tokensUsed}",
            "Token budget: ${if (g.tokenBudget > 0) g.tokenBudget else "none"}",
            "Time used: ${g.timeUsedSeconds} seconds",
            "Objective:",
            g.text,
        )
        if (g.goalIteration > 0) {
            parts += "Completion verifications run: ${g.goalIteration}"
            if (g.lastVerifyReason.isNotBlank()) parts += "Last verifier reason: ${g.lastVerifyReason}"
            if (g.nextAction.isNotBlank()) parts += "Verifier next action: ${g.nextAction}"
        }
        parts += "Use it as the authoritative long-running objective unless a later get_goal result or goal event updates it."
        return ToolResult(parts.joinToString("\n"))
    }
}
