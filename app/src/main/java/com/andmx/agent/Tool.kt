package com.andmx.agent

import com.andmx.llm.ApiFunctionDef
import com.andmx.llm.ApiTool
import kotlinx.serialization.json.JsonObject

/** A capability the agent can invoke. Maps 1:1 to an OpenAI function tool. */
interface Tool {
    val name: String
    val description: String
    /** JSON-schema object describing the arguments. */
    val parameters: JsonObject

    /** Risk class, used by the approval policy to decide auto-run vs. prompt. */
    val risk: ToolRisk get() = ToolRisk.EXECUTE

    suspend fun execute(args: JsonObject): ToolResult

    fun toApiTool(): ApiTool = ApiTool(
        function = ApiFunctionDef(name = name, description = description, parameters = parameters),
    )
}

interface ExecutionAwareTool {
    suspend fun execute(callId: String, args: JsonObject): ToolResult
}

/** How dangerous a tool call is — drives the graduated approval policy. */
enum class ToolRisk { READ, WRITE, EXECUTE, NETWORK }

/**
 * A tool's outcome. [output] is the text fed back to the model (and shown in
 * the UI). [imageUrls] optionally carries image data-urls (`data:<mime>;base64,...`)
 * — used by computer-use/screenshot tools so the model can *see* the result
 * alongside the text (the pure-visual screenshot→action→screenshot loop).
 */
data class ToolResult(
    val output: String,
    val isError: Boolean = false,
    val imageUrls: List<String>? = null,
)
