package com.andmx.agent

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.LlmStreamEvent
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Events streamed out of an agent turn for the UI to render. */
sealed interface AgentEvent {
    /** Incremental assistant text chunk (streaming). */
    data class AssistantDelta(val text: String) : AgentEvent
    /** A fully-committed assistant message (final answer for a turn). */
    data class Assistant(val text: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val arguments: String) : AgentEvent
    data class ToolFinished(
        val id: String, val name: String, val output: String, val isError: Boolean,
        /** Image data-urls produced by the tool (e.g. computer-use screenshots). */
        val imageUrls: List<String>? = null,
    ) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object Done : AgentEvent
}

/**
 * What the agent needs to know about the active backend for a turn: the bound
 * [provider] (for capabilities/retry already baked into the client) and the
 * model id the user selected, plus that model's metadata (if known) so the
 * loop can decide reasoning effort and compaction thresholds.
 */
data class TurnContext(
    val provider: ProviderDefinition,
    val model: String,
) {
    val modelMeta: ModelDefinition? get() = provider.models[model]
}

/**
 * Provider-agnostic agent loop: ask the model, run any tool calls inside the
 * sandbox, feed results back, repeat until the model answers in plain text.
 */
class AgentEngine(
    private val tools: List<Tool>,
    private val client: LlmApi,
    private val compactor: ContextCompactor = ContextCompactor(client = client),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxSteps: Int = 50,
    /** Steps granted after maxSteps to let the model wrap up; if it still hasn't converged, we fail. */
    private val graceSteps: Int = 3,
    /** Optional hook system; PRE/POST_TOOL_USE hooks run around each tool call. */
    private val hooks: com.andmx.agent.hooks.HookSystem? = null,
    /** Gate consulted before running each tool; return false to refuse. */
    private val approve: suspend (Tool, JsonObject) -> Boolean = { _, _ -> true },
) {
    private val history = mutableListOf(ApiMessage(role = "system", content = systemPrompt))
    private var extraTools: List<Tool> = emptyList()
    private val allTools get() = tools + extraTools
    private val toolsByName get() = allTools.associateBy { it.name }
    private var systemSuffix: String = ""
    private var persona: String = ""

    /** Register additional tools at runtime (e.g. from MCP servers). */
    fun addTools(more: List<Tool>) { extraTools = extraTools + more }

    /** (name, description) of all currently-registered tools, for the plugins page. */
    fun listTools(): List<Pair<String, String>> = allTools.map { it.name to it.description }

    /**
     * Resolve the reasoning effort to send for this turn, driven by the model's
     * declared [com.andmx.llm.provider.ReasoningConfig]:
     * - NONE style (or unknown model) → null (send nothing; DeepSeek-style CoT
     *   is not adjustable, gpt-4o doesn't support it)
     * - EFFORT style → the user's level only if it's one the model accepts,
     *   else the model's default effort
     * - THINKING style → the user's value (a budget number or "enabled"); the
     *   adapter clamps it to the spec range
     */
    private fun reasoningFor(settings: ProviderSettings, ctx: TurnContext): String? {
        val e = settings.reasoningEffort
        if (e.isBlank() || e == "off") return null
        val reasoning = ctx.modelMeta?.reasoning ?: return null
        return when (reasoning.style) {
            com.andmx.llm.provider.ReasoningStyle.NONE -> null
            com.andmx.llm.provider.ReasoningStyle.EFFORT ->
                if (e in reasoning.effortLevels) e else reasoning.defaultEffort
            com.andmx.llm.provider.ReasoningStyle.THINKING -> e
        }
    }

    /** Append project/custom instructions to the system prompt. */
    fun setCustomInstructions(text: String) {
        systemSuffix = text.trim()
        history[0] = ApiMessage(role = "system", content = composedSystem())
    }

    /** Set the assistant persona/tone. */
    fun setPersona(p: String) {
        persona = p.trim()
        history[0] = ApiMessage(role = "system", content = composedSystem())
    }

    private fun composedSystem(): String = buildString {
        append(systemPrompt)
        if (persona.isNotBlank()) append("\n\n# 语气\n以「").append(persona).append("」的风格回应。")
        if (systemSuffix.isNotBlank()) append("\n\n# 用户自定义指令\n").append(systemSuffix)
    }

    /** Public access to the composed system prompt (for rollout recording). */
    fun composedSystemPrompt(): String = composedSystem()

    /** Reset the conversation history (used when loading a saved conversation). */
    fun seed(messages: List<ApiMessage>) {
        history.clear()
        history += ApiMessage(role = "system", content = composedSystem())
        history += messages
    }

    /** Snapshot of the current history (used to preserve state across engine rebuilds). */
    fun snapshotHistory(): List<ApiMessage> = history.toList()

    fun runTurn(settings: ProviderSettings, turn: TurnContext, userInput: String, images: List<String> = emptyList()): Flow<AgentEvent> = flow {
        history += ApiMessage(role = "user", content = userInput, imageUrls = images.ifEmpty { null })
        loop(settings, turn)
    }

    /**
     * Re-run the model on the current history without adding a new user message,
     * after discarding any trailing assistant/tool messages (regenerate).
     */
    fun regenerate(settings: ProviderSettings, turn: TurnContext): Flow<AgentEvent> = flow {
        while (history.size > 1 && history.last().role != "user") history.removeAt(history.lastIndex)
        if (history.none { it.role == "user" }) { emit(AgentEvent.Done); return@flow }
        loop(settings, turn)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.loop(settings: ProviderSettings, turn: TurnContext) {
        // Repair history damaged by an interrupted turn: if the tail is an
        // assistant message with tool_calls but no matching tool results,
        // synthesize "interrupted" results so the model isn't confused.
        cleanupOrphanToolCalls()

        val contextWindow = turn.modelMeta?.contextWindow?.takeIf { it > 0 } ?: 128_000
        var convergenceHinted = false
        var step = 0
        val hardLimit = maxSteps + graceSteps
        while (step++ < hardLimit) {
            // ── Context management: soft compact, then hard-limit fallback ──
            val overHardLimit = compactor.isContextWindowExceeded(history, contextWindow)
            if (overHardLimit || compactor.needsCompaction(history, contextWindow)) {
                val result = compactor.compact(history, settings, turn)
                if (result != null) {
                    history.clear()
                    history += result.compacted
                    emit(AgentEvent.AssistantDelta("\n_(上下文已自动压缩: 移除 ${result.removedCount} 条历史消息)_\n"))
                } else if (overHardLimit) {
                    // Compaction failed while over the hard limit — fall back to
                    // dropping the oldest messages instead of looping forever.
                    val dropped = dropOldestNonSystem(keepRecent = 8)
                    if (dropped > 0) {
                        emit(AgentEvent.AssistantDelta("\n_(压缩失败,已丢弃 $dropped 条旧消息以释放上下文)_\n"))
                    }
                }
            }

            // ── Step budget: nudge the model to converge as it runs out ──
            if (step == maxSteps && !convergenceHinted) {
                convergenceHinted = true
                history += ApiMessage(
                    role = "system",
                    content = "步数即将用尽。请立即总结当前进度,完成收尾,不要再发起新的工具调用。",
                )
            }

            val request = ChatRequest(
                model = turn.model,
                messages = history.toList(),
                tools = allTools.map { it.toApiTool() },
                reasoningEffort = reasoningFor(settings, turn),
            )

            // Stream with retry: a transient stream break / empty reply shouldn't
            // kill the whole turn. Retry up to 2 times with backoff.
            val msg = streamWithRetry(request) { emit(AgentEvent.AssistantDelta(it)) }
            if (msg == null) {
                emit(AgentEvent.Failed("多次重试后仍无响应"))
                emit(AgentEvent.Done)
                return
            }
            history += msg

            val calls = msg.toolCalls
            if (calls.isNullOrEmpty()) {
                // Final answer — commit the text and finish.
                msg.content?.takeIf { it.isNotBlank() }?.let { emit(AgentEvent.Assistant(it)) }
                emit(AgentEvent.Done)
                return
            }

            // Tool call ahead — commit any intermediate text so it's not lost
            // on pause/restart. Codex shows these as agent_message items between
            // tool calls, narrating the work process.
            msg.content?.takeIf { it.isNotBlank() }?.let { emit(AgentEvent.Assistant(it)) }

            // Run each requested tool. Multiple calls execute concurrently, but
            // emissions must stay on the flow's owner coroutine (FlowCollector
            // is not thread-safe), so we emit ToolStarted up front, run the
            // tools in parallel without emitting, then emit ToolFinished in order.
            if (calls.size <= 1) {
                for (call in calls) {
                    emit(AgentEvent.ToolStarted(call.id, call.function.name, call.function.arguments))
                    val result = executeToolCall(call)
                    emit(AgentEvent.ToolFinished(call.id, call.function.name, result.output, result.isError, result.imageUrls))
                    history += ApiMessage(role = "tool", content = result.output, toolCallId = call.id, name = call.function.name, imageUrls = result.imageUrls)
                }
            } else {
                // Emit all ToolStarted first (serial, on the flow coroutine).
                calls.forEach { call ->
                    emit(AgentEvent.ToolStarted(call.id, call.function.name, call.function.arguments))
                }
                // Execute tools in parallel — NO emit inside async.
                val results = coroutineScope {
                    calls.map { call ->
                        async { call to executeToolCall(call) }
                    }.map { it.await() }
                }
                // Emit ToolFinished in order (serial, on the flow coroutine).
                for ((call, result) in results) {
                    emit(AgentEvent.ToolFinished(call.id, call.function.name, result.output, result.isError, result.imageUrls))
                    history += ApiMessage(role = "tool", content = result.output, toolCallId = call.id, name = call.function.name, imageUrls = result.imageUrls)
                }
            }
        }
        emit(AgentEvent.Failed("已达最大步数 ($maxSteps) + 收敛宽限 ($graceSteps),任务未能完成"))
        emit(AgentEvent.Done)
    }

    /**
     * Stream a request with up to [maxRetries] retries on failure or empty reply.
     * Returns the assembled assistant message, or null if all attempts fail.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.streamWithRetry(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit,
    ): ApiMessage? {
        val maxRetries = 2
        var lastError: String? = null
        for (attempt in 0..maxRetries) {
            var message: ApiMessage? = null
            val gotContent = try {
                client.chatStream(request).collect { ev ->
                    when (ev) {
                        is LlmStreamEvent.Content -> onDelta(ev.delta)
                        is LlmStreamEvent.Completed -> message = ev.message
                        is LlmStreamEvent.UsageUpdate -> { /* tracked by LlmClient */ }
                    }
                }
                true
            } catch (t: Throwable) {
                lastError = t.message ?: "请求失败"
                false
            }
            if (gotContent && message != null) return message
            // Empty reply on first attempt: retry once (possibly a lost first chunk).
            if (gotContent && message == null && attempt == 0) continue
            if (attempt < maxRetries) kotlinx.coroutines.delay(1000L shl attempt)
        }
        lastError?.let { emit(AgentEvent.Failed(it)) }
        return null
    }

    /**
     * Drop the oldest non-system messages (keeping the most recent [keepRecent])
     * as a last-resort context release when compaction fails. Returns the count
     * removed. Never touches system messages or the recent window.
     */
    private fun dropOldestNonSystem(keepRecent: Int): Int {
        if (history.size <= keepRecent + 1) return 0
        val systemEnd = history.indexOfLast { it.role == "system" } + 1
        val removable = history.subList(systemEnd, history.size - keepRecent)
        val count = removable.size
        if (count <= 0) return 0
        removable.clear()
        return count
    }

    /**
     * Repair a history left mid-tool by an interrupted turn: any trailing
     * assistant tool_calls without matching tool results get synthesized
     * "interrupted" results so the next model call isn't confused.
     */
    private fun cleanupOrphanToolCalls() {
        if (history.isEmpty()) return
        val last = history.last()
        val orphanCalls = last.toolCalls.orEmpty().filter { call ->
            history.none { it.role == "tool" && it.toolCallId == call.id }
        }
        if (orphanCalls.isNotEmpty()) {
            orphanCalls.forEach { call ->
                history += ApiMessage(
                    role = "tool",
                    content = "_[执行被中断,未拿到结果]_",
                    toolCallId = call.id,
                    name = call.function.name,
                )
            }
        }
    }

    private fun parseArgs(raw: String): JsonObject = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }

    /** Execute a single tool call: PRE_TOOL_USE hook → approval → run → POST_TOOL_USE hook. */
    private suspend fun executeToolCall(call: com.andmx.llm.ApiToolCall): ToolResult {
        val tool = toolsByName[call.function.name]
        return if (tool == null) {
            ToolResult("未知工具: ${call.function.name}", isError = true)
        } else {
            // ── PRE_TOOL_USE: hooks may block or modify args ──
            val preCtx = com.andmx.agent.hooks.HookSystem.HookContext(
                toolName = call.function.name,
                toolArgs = call.function.arguments,
            )
            val pre = hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.PRE_TOOL_USE, preCtx)
                ?: com.andmx.agent.hooks.HookSystem.HookResult(com.andmx.agent.hooks.HookSystem.HookDecision.CONTINUE)
            if (pre.decision == com.andmx.agent.hooks.HookSystem.HookDecision.BLOCK) {
                return ToolResult("被 hook 拦截: ${pre.message.orEmpty()}", isError = true)
            }
            val effectiveArgs = if (pre.decision == com.andmx.agent.hooks.HookSystem.HookDecision.MODIFY && pre.modifiedInput != null) {
                parseArgs(pre.modifiedInput!!)
            } else {
                parseArgs(call.function.arguments)
            }

            if (!approve(tool, effectiveArgs)) {
                ToolResult("已被用户拒绝执行", isError = true)
            } else {
                val raw = runCatching {
                    if (tool is ExecutionAwareTool) tool.execute(call.id, effectiveArgs)
                    else tool.execute(effectiveArgs)
                }
                    .getOrElse { ToolResult("工具异常: ${it.message}", isError = true) }
                // ── POST_TOOL_USE: hooks may rewrite the output ──
                val postCtx = com.andmx.agent.hooks.HookSystem.HookContext(
                    toolName = call.function.name,
                    toolArgs = call.function.arguments,
                    toolOutput = raw.output,
                )
                val post = hooks?.runEvent(com.andmx.agent.hooks.HookSystem.HookEvent.POST_TOOL_USE, postCtx)
                if (post?.decision == com.andmx.agent.hooks.HookSystem.HookDecision.MODIFY && post.modifiedOutput != null) {
                    raw.copy(output = post.modifiedOutput!!)
                } else {
                    raw
                }
            }
        }
    }

    companion object {
        val DEFAULT_SYSTEM_PROMPT = buildString {
            // ── Identity ──
            appendLine("你是 AndMX,一个运行在 Android 设备上的 AI 编码工作台 agent。")
            appendLine("你和一个无 root 的 Linux (Alpine/proot) 沙箱协同工作。")
            appendLine()

            // ── Autonomy & Persistence (mirrors Codex) ──
            appendLine("# 自主性与持久性")
            appendLine("在当前轮次内尽可能端到端地完成任务:不要停在分析或部分修复上;")
            appendLine("将变更贯穿到实现、验证和清晰的结果说明中,除非用户明确暂停或重定向。")
            appendLine("除非用户明确要求计划、提问、头脑风暴或明确不写代码,否则假设用户希望")
            appendLine("你直接做代码变更或运行工具来解决问题。不要在消息中输出方案,直接实现。")
            appendLine("如果遇到挑战或阻塞,尝试自己解决而不是放弃。")
            appendLine("始终在当前轮次内坚持把工作做完。")
            appendLine()

            // ── Tool Guidelines (mirrors Codex) ──
            appendLine("# 工具指南")
            appendLine()
            appendLine("## Shell 命令 (run_shell)")
            appendLine("- 搜索文本或文件时优先用 rg 或 rg --files (比 grep 快得多)")
            appendLine("- 不要用 python 脚本输出大段文件内容")
            appendLine("- 尽可能并行工具调用 — 尤其是文件读取 (cat, rg, sed, ls, git show, nl, wc)")
            appendLine("- 运行命令前考虑风险和当前授权模式")
            appendLine()
            appendLine("## apply_patch")
            appendLine("用 apply_patch 工具编辑文件。支持两种格式:")
            appendLine()
            appendLine("格式一 (Codex freeform):")
            appendLine("*** Begin Patch")
            appendLine("*** Add File: hello.txt")
            appendLine("+Hello world")
            appendLine("*** Update File: src/app.py")
            appendLine("*** Move to: src/main.py")
            appendLine("@@ def greet():")
            appendLine("-print(\"Hi\")")
            appendLine("+print(\"Hello, world!\")")
            appendLine("*** Delete File: obsolete.txt")
            appendLine("*** End Patch")
            appendLine()
            appendLine("格式二 (unified diff):")
            appendLine("@@ def greet():")
            appendLine("-print(\"Hi\")")
            appendLine("+print(\"Hello, world!\")")
            appendLine()
            appendLine("要点:")
            appendLine("- 改文件优先用 apply_patch 以便用户审查 diff")
            appendLine("- 变更会进入 diff 审查面板,用户可以逐个接受或拒绝")
            appendLine()
            appendLine("## update_plan")
            appendLine("有一个 update_plan 工具可用于跟踪任务进度。")
            appendLine("创建计划时,调用 update_plan 并传入简短步骤列表(每步不超过5-7个词),")
            appendLine("每个步骤带 status: pending / in_progress / completed。")
            appendLine("始终保持恰好一个 in_progress 步骤,直到全部完成。")
            appendLine("不要为简单或单步任务使用计划。不要在调用后重复计划全文。")
            appendLine()
            appendLine("## 其他工具")
            appendLine("- read_file / write_file / edit_file: 读写与精确替换文件")
            appendLine("- list_dir: 列目录")
            appendLine("- git: 版本控制 (首次使用自动安装 git)")
            appendLine("- browse: 抓取网页正文")
            appendLine("- web_search: DuckDuckGo 联网搜索")
            appendLine()

            // ── Context Hygiene ──
            appendLine("# 上下文卫生")
            appendLine("- 不要重复读取已经读过的文件")
            appendLine("- 工具输出过长时用摘要 + 精确错误片段 + 指针")
            appendLine("- 项目根目录的 AGENTS.md (如果存在) 已包含在上下文中,无需重读")
            appendLine("- 不要用 python 脚本输出大段文件内容")
            appendLine("- 尽可能并行工具调用 — 尤其是文件读取 (cat, rg, sed, ls, git show, nl, wc)")
            appendLine()

            // ── Diff ──
            appendLine("# Diff")
            appendLine("变更进入 diff 审查。用户可能需要批准你的补丁。")
            appendLine("保持变更最小且聚焦于请求的范围。不要做无关的重构。")
            appendLine("遵循已有模式、框架和本地辅助 API,而非发明新的抽象风格。")
            appendLine("仅在确实需要时添加抽象,且必须移除真正的复杂度或减少有意义的重复。")
            appendLine()

            // ── Output Format ──
            appendLine("# 输出格式")
            appendLine("- 用 GitHub-flavored Markdown 格式化")
            appendLine("- 简单任务用一行回答。按从通用到具体到支撑的顺序组织")
            appendLine("- 不要用嵌套列表。保持列表扁平(单层)。需要层次时拆分为多个列表或段落")
            appendLine("- 标题可选,仅在必要时使用,用短 Title Case (1-3词) 加 ** 包裹")
            appendLine("- 完成后用简洁中文说明做了什么、验证了什么、还有哪些风险或下一步")
            appendLine("- 对于简单的问候、确认或一次性的对话消息,自然回应即可,不需要标题或列表")
            appendLine()

            // ── Working with the user (mirrors Codex) ──
            appendLine("# 与用户交互")
            appendLine("在工具调用之间,用一两句话说明你在做什么以及为什么。")
            appendLine("不要空洞地叙述;解释具体动作和理由。")
            appendLine("工具的输出不需要重复(用户已能看到),只需总结变更并指出重要的上下文或下一步。")
            appendLine("完成所有工作后,发送最终消息总结结果。")
            appendLine()

            // ── Computer Use (screen operation) ──
            appendLine("# 屏幕操作 (Computer Use)")
            appendLine("当用户要求操作设备屏幕或其他 app 时,使用 `computer` 工具。这是纯视觉能力:")
            appendLine("- 先 `screenshot` 看清当前屏幕,再决定下一步动作")
            appendLine("- 用 `click [x,y]` 点击(坐标基于你最近看到的截图分辨率)")
            appendLine("- 用 `type` 输入文本、`scroll` 滚动、`swipe` 滑动、`key` 发送返回/主页等")
            appendLine("- 每次操作后工具会自动返回新截图,务必基于新画面判断是否达到目标")
            appendLine("- 坐标尽量精确;不确定元素位置时先 screenshot 再行动")
            appendLine("- 安全:不自动化 AndMX 自身窗口;仅操作用户明确要求的目标;每次动作都会经过审批")
            appendLine("用户未授权屏幕录制/无障碍权限时,工具会返回引导信息,此时告知用户如何开启。")
            appendLine()

            // ── Frontend Guidance ──
            appendLine("# 前端指导")
            appendLine("做前端任务时,避免落入\"AI 套路\"或安全的平庸布局。")
            appendLine("追求有意图、大胆、略带惊喜的界面。")
            appendLine("- 排版: 用有表现力的字体,避免默认字体栈")
            appendLine("- 色彩: 选择清晰的视觉方向,定义 CSS 变量,避免紫色偏倚")
            appendLine("- 动效: 用少量有意义的动画而非通用微动效")
            appendLine("- 背景: 不要依赖扁平单色背景,用渐变、形状或微妙图案营造氛围")
            appendLine("- 整体: 避免样板布局和可互换的 UI 模式")
        }
    }
}
