package com.andmx.agent

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.LlmStreamEvent
import com.andmx.llm.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * LlmApi 装饰器：把每次 LLM 调用记录进 [ModelCallTrace]，
 * 来源由构造时的 [source] 标注（主会话/压缩/子智能体）。
 * 用量来自流内 [LlmStreamEvent.UsageUpdate]（chat 无流式 usage 时记 0）。
 */
class TracedLlm(
    private val delegate: LlmApi,
    private val source: ModelCallTrace.Source,
) : LlmApi {

    override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
        val startedAt = System.currentTimeMillis()
        val result = delegate.chat(request)
        result.getOrNull()?.let { msg ->
            ModelCallTrace.record(
                source = source,
                model = request.model,
                inputTokens = 0,
                cachedInputTokens = 0,
                outputTokens = 0,
                finish = finishOf(msg),
                inputPreview = inputPreview(request),
                outputPreview = outputPreview(msg),
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
        return result
    }

    override fun chatStream(request: ChatRequest): Flow<LlmStreamEvent> = flow {
        val startedAt = System.currentTimeMillis()
        var usage = TokenUsage()
        var finalMessage: ApiMessage? = null
        delegate.chatStream(request).collect { ev ->
            when (ev) {
                is LlmStreamEvent.UsageUpdate -> {
                    usage = ev.usage
                    emit(ev)
                }
                is LlmStreamEvent.Completed -> {
                    finalMessage = ev.message
                    emit(ev)
                }
                else -> emit(ev)
            }
        }
        finalMessage?.let { msg ->
            ModelCallTrace.record(
                source = source,
                model = request.model,
                inputTokens = usage.inputTokens,
                cachedInputTokens = usage.cachedInputTokens,
                outputTokens = usage.outputTokens,
                finish = finishOf(msg),
                inputPreview = inputPreview(request),
                outputPreview = outputPreview(msg),
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    private fun finishOf(msg: ApiMessage): ModelCallTrace.Finish =
        if (msg.toolCalls.isNullOrEmpty()) ModelCallTrace.Finish.STOP else ModelCallTrace.Finish.TOOL_CALLS

    private fun inputPreview(request: ChatRequest): String {
        val last = request.messages.lastOrNull { it.role != "system" }
        val body = last?.content ?: last?.toolCalls?.joinToString("\n") { call ->
            "${call.function.name} ${call.function.arguments}"
        } ?: ""
        return "[${request.messages.size} 条消息]\n$body"
    }

    private fun outputPreview(msg: ApiMessage): String =
        msg.content ?: msg.toolCalls?.joinToString("\n") { call ->
            "${call.function.name} ${call.function.arguments}"
        } ?: ""
}
