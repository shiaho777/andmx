package com.andmx.agent.workflow

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.agent.zcode.ESCALATE_DESCRIPTION
import com.andmx.agent.zcode.ESCALATE_MODEL_BYTES
import com.andmx.agent.zcode.ESCALATE_PARAMETERS
import com.andmx.agent.zcode.ESCALATE_TOOL_NAME
import com.andmx.agent.zcode.SUBMIT_RESULT_ACCEPTED
import com.andmx.agent.zcode.SUBMIT_RESULT_DESCRIPTION
import com.andmx.agent.zcode.SUBMIT_RESULT_MODEL_BYTES
import com.andmx.agent.zcode.SUBMIT_RESULT_PARAMETERS
import com.andmx.agent.zcode.SUBMIT_RESULT_TOOL_NAME
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SubmitResultTool(
    private val slot: AtomicReference<String?>,
) : Tool {
    override val name = SUBMIT_RESULT_TOOL_NAME
    override val description = SUBMIT_RESULT_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = false
    override val needsApproval = false
    override val stopTurnOnSuccess = true
    override val timeoutMs: Long? = null
    override val maxOutputBytes = SUBMIT_RESULT_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "workflow.submitResult"
    override val parameters: JsonObject = SUBMIT_RESULT_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val result = args["result"] ?: return ToolResult("result is required", isError = true)
        slot.set(result.toString())
        return ToolResult(SUBMIT_RESULT_ACCEPTED)
    }
}

class EscalateTool(
    private val ask: suspend (question: String, context: String) -> String,
) : Tool {
    override val name = ESCALATE_TOOL_NAME
    override val description = ESCALATE_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val stopTurnOnSuccess = false
    override val timeoutMs: Long? = null
    override val maxOutputBytes = ESCALATE_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "workflow.escalate"
    override val parameters: JsonObject = ESCALATE_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val q = args["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (q.isEmpty()) return ToolResult("question is required", isError = true)
        val ctx = args["context"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val answer = ask(q, ctx)
        return ToolResult(
            if (answer != DEFERRED) {
                answer
            } else {
                "No coordinator answer available. Proceed with the most reasonable assumption and record it in your result."
            },
        )
    }

    companion object {
        const val DEFERRED = "__escalate_deferred__"
    }
}
