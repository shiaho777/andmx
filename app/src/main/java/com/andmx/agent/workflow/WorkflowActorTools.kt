package com.andmx.agent.workflow

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ZCode submit_result / escalate actor 工具对齐：注入 workflow_child 会话。

class SubmitResultTool(
    private val slot: AtomicReference<String?>,
) : Tool {
    override val name = "submit_result"
    override val description =
        "Submit the structured result for this ask, matching the JSON schema given in the ask instructions. The workflow runtime reads this as the node's structured output."
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("result") {
                put("description", "The structured result for this ask, matching the JSON schema given in the ask instructions.")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val result = args["result"]
            ?: return ToolResult("result is required", isError = true)
        slot.set(result.toString())
        return ToolResult("Result submitted.")
    }
}

class EscalateTool(
    private val ask: suspend (question: String, context: String) -> String,
) : Tool {
    override val name = "escalate"
    override val description =
        "Escalate one focused blocking question to the coordinator. One question per call, answerable in a sentence. Use only when genuinely blocked — the answer may take time."
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("question") { put("type", "string") }
            putJsonObject("context") { put("type", "string") }
        }
        putJsonArray("required") { add("question") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val q = args["question"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("question is required", isError = true)
        val ctx = args["context"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val qid = "q_${UUID.randomUUID().toString().take(8)}"
        val answer = ask(q, ctx)
        return ToolResult(
            if (answer != DEFERRED) {
                """{"status":"answered","qid":"$qid","message":${kotlinx.serialization.json.JsonPrimitive(answer)}}"""
            } else {
                """{"status":"deferred","qid":"$qid","message":"No coordinator answer available. Proceed with the most reasonable assumption and record it in your result."}"""
            },
        )
    }

    companion object {
        const val DEFERRED = "__escalate_deferred__"
    }
}
