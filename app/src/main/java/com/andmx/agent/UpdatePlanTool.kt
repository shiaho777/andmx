package com.andmx.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * update_plan: A tool the model can call to track its task plan.
 *
 * Mirrors Codex's `update_plan` tool — the model creates a short list of
 * 1-sentence steps (no more than 5-7 words each) with a status for each step
 * (pending, in_progress, completed). There should always be exactly one
 * in_progress step until everything is done.
 *
 * The UI observes [state] to render a live plan panel.
 */
class UpdatePlanTool : Tool {

    override val name = "update_plan"
    override val description =
        "更新当前任务的执行计划。创建或更新一组简短步骤(每步不超过5-7个词)，" +
            "每步有状态: pending / in_progress / completed。" +
            "应始终保持恰好一个 in_progress 步骤，直到全部完成。" +
            "不要为简单或单步任务使用计划。不要在调用后重复计划全文。"
    override val risk = ToolRisk.READ

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("steps") {
                put("type", "array")
                put("description", "步骤列表。每个步骤含 content (简短描述) 和 status (pending/in_progress/completed)")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "步骤描述，不超过7个词")
                        }
                        putJsonObject("status") {
                            put("type", "string")
                            putJsonArray("enum") { add("pending"); add("in_progress"); add("completed") }
                            put("description", "步骤状态")
                        }
                    }
                    putJsonArray("required") { add("content"); add("status") }
                }
            }
        }
        putJsonArray("required") { add("steps") }
    }

    private val _state = MutableStateFlow<List<PlanStep>>(emptyList())

    /** Live observable plan state for the UI. */
    val state: StateFlow<List<PlanStep>> = _state

    data class PlanStep(
        val content: String,
        val status: StepStatus,
    )

    enum class StepStatus { PENDING, IN_PROGRESS, COMPLETED }

    override suspend fun execute(args: JsonObject): ToolResult {
        val stepsArr = args["steps"] ?: return ToolResult("缺少参数 steps", isError = true)
        val steps = mutableListOf<PlanStep>()

        @Suppress("UNCHECKED_CAST")
        val rawList = (stepsArr as? kotlinx.serialization.json.JsonArray)
            ?: return ToolResult("steps 必须是数组", isError = true)

        for (elem in rawList) {
            val obj = elem as? JsonObject ?: continue
            val content = obj["content"]?.let {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            } ?: continue
            val statusStr = obj["status"]?.let {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            } ?: "pending"
            val status = when (statusStr.lowercase()) {
                "completed" -> StepStatus.COMPLETED
                "in_progress" -> StepStatus.IN_PROGRESS
                else -> StepStatus.PENDING
            }
            steps.add(PlanStep(content, status))
        }

        if (steps.isEmpty()) return ToolResult("步骤列表为空", isError = true)
        if (steps.size > 12) return ToolResult("步骤过多 (最多12步)，请合并", isError = true)

        _state.value = steps
        return ToolResult("计划已更新 (${steps.size} 步)")
    }

    /** Clear the plan (used when starting a new conversation). */
    fun clear() { _state.value = emptyList() }

    /** Get a text summary of the current plan. */
    fun summary(): String {
        val s = _state.value
        if (s.isEmpty()) return "(无计划)"
        return s.joinToString("\n") { step ->
            val icon = when (step.status) {
                StepStatus.COMPLETED -> "[x]"
                StepStatus.IN_PROGRESS -> "[~]"
                StepStatus.PENDING -> "[ ]"
            }
            "$icon ${step.content}"
        }
    }
}
