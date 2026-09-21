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

    /**
     * Cooperative deadline the engine arms around one call, or null for no
     * limit. Each tool owns its own budget: a network call that can hang
     * declares one, a build command that legitimately runs for an hour does not.
     *
     * The deadline only asks a tool to stop by cancelling its coroutine; a tool
     * that ignores cancellation keeps the step waiting until it settles.
     * Cancellation from outside the engine is never reported as a timeout.
     */
    val timeoutMs: Long? get() = null

    /**
     * May this tool run concurrently with other calls in one model-requested
     * batch (ZCode canRunInParallel)? The scheduler keeps all other calls
     * serial and preserves request order. Default: READ-risk tools are safe;
     * tools that mutate session/workspace state must override to false, and
     * side-effect-free network reads (WebFetch/WebSearch) opt back in.
     */
    val concurrentSafe: Boolean get() = risk == ToolRisk.READ

    /**
     * ZCode `alwaysAsk` 对齐：声明后每次调用都必须经用户确认——项目 allow
     * 规则与 FULL 模式都不能放行；项目 deny / 会话拒绝仍可阻断。
     * 仅会话级「允许本会话」可以免确认。
     */
    val alwaysAsk: Boolean get() = false

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
