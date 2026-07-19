package com.andmx.agent.hooks

import android.content.Context
import com.andmx.exec.ProcessSpec
import com.andmx.exec.proot.LocalProotEnvironment
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Hook system — mirrors Codex's hook architecture.
 *
 * Hooks are user-defined commands that run at specific lifecycle points
 * (before/after tool use, on session start/stop, before/after compaction).
 * They can block tool execution, modify inputs/outputs, or inject context.
 *
 * Hook commands run inside the proot guest and communicate via JSON over
 * stdin/stdout. This design lets users write hooks in any language available
 * in the Alpine guest (sh, python, node, etc.).
 */
class HookSystem(
    private val context: Context,
    hooks: List<HookConfig> = emptyList(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val runtime = ProotRuntime(context)
    private val env = LocalProotEnvironment(context, runtime)
    @Volatile private var hooks: List<HookConfig> = hooks.toList()

    fun replaceHooks(next: List<HookConfig>) {
        hooks = next.toList()
    }

    fun appendHooks(more: List<HookConfig>) {
        if (more.isEmpty()) return
        hooks = hooks + more
    }

    companion object {
        fun parseEvent(raw: String): HookEvent? {
            val key = raw.trim().lowercase().replace('-', '_')
            return when (key) {
                "session_start" -> HookEvent.SESSION_START
                "user_prompt_submit", "user_prompt" -> HookEvent.USER_PROMPT_SUBMIT
                "pre_tool_use", "pretooluse" -> HookEvent.PRE_TOOL_USE
                "post_tool_use", "posttooluse" -> HookEvent.POST_TOOL_USE
                "pre_compact", "precompact" -> HookEvent.PRE_COMPACT
                "post_compact", "postcompact" -> HookEvent.POST_COMPACT
                "stop", "session_stop", "session_end" -> HookEvent.STOP
                else -> null
            }
        }
    }

    enum class HookEvent {
        SESSION_START,
        USER_PROMPT_SUBMIT,
        PRE_TOOL_USE,
        POST_TOOL_USE,
        PRE_COMPACT,
        POST_COMPACT,
        STOP,
    }

    enum class HookMode { SYNC, ASYNC }

    enum class HookDecision { CONTINUE, BLOCK, MODIFY }

    data class HookConfig(
        val event: HookEvent,
        val command: String,           // shell command to execute in the guest
        val timeoutMs: Long = 10_000,
        val mode: HookMode = HookMode.SYNC,
        val name: String = "",
    )

    data class HookContext(
        val toolName: String? = null,
        val toolArgs: String? = null,
        val toolOutput: String? = null,
        val userInput: String? = null,
        val sessionInfo: Map<String, String> = emptyMap(),
    )

    data class HookResult(
        val decision: HookDecision,
        val modifiedInput: String? = null,
        val modifiedOutput: String? = null,
        val message: String? = null,
        val promptFragment: String? = null,  // injected into the system prompt
    )

    /** Run all hooks for a given event. Returns the combined result. */
    suspend fun runEvent(event: HookEvent, ctx: HookContext = HookContext()): HookResult =
        withContext(Dispatchers.IO) {
            val matching = hooks.filter { it.event == event }
            if (matching.isEmpty()) return@withContext HookResult(HookDecision.CONTINUE)

            var combinedResult = HookResult(HookDecision.CONTINUE)
            for (hook in matching) {
                val result = executeHook(hook, ctx)
                when (result.decision) {
                    HookDecision.BLOCK -> return@withContext result
                    HookDecision.MODIFY -> {
                        combinedResult = result
                    }
                    HookDecision.CONTINUE -> {
                        // Merge prompt fragments
                        if (result.promptFragment != null) {
                            val existing = combinedResult.promptFragment
                            val merged = if (existing != null) "$existing\n${result.promptFragment}" else result.promptFragment
                            combinedResult = combinedResult.copy(promptFragment = merged)
                        }
                    }
                }
            }
            combinedResult
        }

    private suspend fun executeHook(hook: HookConfig, ctx: HookContext): HookResult {
        val stdin = buildContextJson(ctx)

        val res = kotlinx.coroutines.withTimeoutOrNull(hook.timeoutMs) {
            runCatching {
                env.execute(ProcessSpec(
                    argv = listOf("/bin/sh", "-c", hook.command),
                    stdin = stdin,
                    redirectErrorStream = true,
                ))
            }.getOrElse {
                return@withTimeoutOrNull null
            }
        } ?: return HookResult(
            HookDecision.CONTINUE,
            message = "Hook '${hook.name}' 超时或失败 (${hook.timeoutMs}ms)",
        )

        val result = res ?: return HookResult(
            HookDecision.CONTINUE,
            message = "Hook '${hook.name}' 执行失败",
        )

        if (res.exitCode != 0) {
            // Hook failure is non-fatal (fail-open), but log it
            return HookResult(
                HookDecision.CONTINUE,
                message = "Hook '${hook.name}' 退出码 ${res.exitCode}: ${res.stderr.take(200)}",
            )
        }

        // Parse hook output as JSON if possible
        return parseHookOutput(res.stdout)
    }

    private fun buildContextJson(ctx: HookContext): String = buildJsonObject {
        ctx.toolName?.let { put("tool_name", it) }
        ctx.toolArgs?.let { put("tool_args", it) }
        ctx.toolOutput?.let { put("tool_output", it) }
        ctx.userInput?.let { put("user_input", it) }
        val sessionObj = buildJsonObject {
            ctx.sessionInfo.forEach { (k, v) -> put(k, v) }
        }
        put("session", sessionObj)
    }.toString()

    private fun parseHookOutput(output: String): HookResult {
        return runCatching {
            val obj = json.parseToJsonElement(output.trim()) as? JsonObject
                ?: return HookResult(HookDecision.CONTINUE)
            val decisionStr = obj["decision"]?.let {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            } ?: "continue"
            val decision = when (decisionStr.lowercase()) {
                "block", "deny" -> HookDecision.BLOCK
                "modify", "rewrite" -> HookDecision.MODIFY
                else -> HookDecision.CONTINUE
            }
            HookResult(
                decision = decision,
                modifiedInput = obj["modified_input"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
                modifiedOutput = obj["modified_output"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
                message = obj["message"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
                promptFragment = obj["prompt_fragment"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
            )
        }.getOrElse {
            // Non-JSON output: treat as a prompt fragment if it's non-empty
            val text = output.trim()
            if (text.isNotEmpty()) {
                HookResult(HookDecision.CONTINUE, promptFragment = text)
            } else {
                HookResult(HookDecision.CONTINUE)
            }
        }
    }

    /** Get all configured hooks (for UI display). */
    fun listHooks(): List<HookConfig> = hooks.toList()

    /** Check if any hooks are configured for a given event. */
    fun hasHooksFor(event: HookEvent): Boolean = hooks.any { it.event == event }
}
