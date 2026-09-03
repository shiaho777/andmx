package com.andmx.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模型调用轨迹（ZCode modelTrajectory.* 对齐）：逐次 LLM 调用的可回放记录。
 *
 * ZCode 把每次调用落盘 model-io 并在查看器展示来源、token、结束原因与
 * 输入/输出原文；AndMX 在会话进程内保存最近 [MAX_CALLS] 条（环形淘汰），
 * 供设置页查看器展示。
 */
object ModelCallTrace {

    enum class Source(val label: String) {
        MAIN("主会话"),
        SESSION_TITLE("标题生成"),
        COMPACT("上下文压缩"),
        SUBAGENT("子智能体"),
        UNKNOWN("未知来源"),
    }

    enum class Finish(val label: String) {
        STOP("正常结束"),
        TOOL_CALLS("工具调用"),
        LENGTH("达到长度限制"),
        CONTENT_FILTER("内容过滤"),
    }

    data class Call(
        val seq: Long,
        val atMs: Long,
        val source: Source,
        val model: String,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int,
        val finish: Finish,
        val inputPreview: String,
        val outputPreview: String,
        val durationMs: Long,
    ) {
        val totalTokens: Int get() = inputTokens + outputTokens
    }

    const val MAX_CALLS = 50
    const val PREVIEW_CHARS = 2_000

    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    val calls: StateFlow<List<Call>> = _calls.asStateFlow()

    private var seqCounter = 0L

    fun record(
        source: Source,
        model: String,
        inputTokens: Int,
        cachedInputTokens: Int,
        outputTokens: Int,
        finish: Finish,
        inputPreview: String,
        outputPreview: String,
        durationMs: Long,
        atMs: Long = System.currentTimeMillis(),
    ): Call {
        val call = Call(
            seq = ++seqCounter,
            atMs = atMs,
            source = source,
            model = model,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            finish = finish,
            inputPreview = inputPreview.take(PREVIEW_CHARS),
            outputPreview = outputPreview.take(PREVIEW_CHARS),
            durationMs = durationMs,
        )
        val next = (_calls.value + call).takeLast(MAX_CALLS)
        _calls.value = next
        return call
    }

    fun clear() {
        _calls.value = emptyList()
    }

    /** 汇总（查看器顶部条）：调用数 + 总 token。 */
    fun summary(): Pair<Int, Int> {
        val list = _calls.value
        return list.size to list.sumOf { it.totalTokens }
    }
}
