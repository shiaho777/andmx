package com.andmx.ui2.chat

/**
 * Turn 耗时/速度指标（dsh web turn-metrics 对齐）：TTFT、解码吞吐。
 * 纯函数，供 JVM 单测。
 */
object TurnMetrics {

    /** 一个 turn 的可显示指标；null 字段表示未记录，不可据此显示。 */
    data class Reading(
        val ttftMs: Long?,
        val outputTokens: Int?,
        val tokensPerSecond: Double?,
    )

    /**
     * 从 turn 的开始/首 token/结束时间戳与输出 token 数推导指标。
     *
     * 诚实显示规则（dsh StatsLine 对齐）：任一必需时间戳缺失（≤0 或不递增）
     * 时对应指标为 null；输出 token 未记录时速度为 null——宁可少显示，
     * 不显示部分推算值。
     */
    fun reading(
        turnStartMs: Long,
        firstTokenMs: Long,
        turnEndMs: Long,
        outputTokens: Int?,
    ): Reading {
        val ttft = if (turnStartMs > 0 && firstTokenMs > turnStartMs) firstTokenMs - turnStartMs else null
        val decodeMs = if (firstTokenMs > 0 && turnEndMs > firstTokenMs) turnEndMs - firstTokenMs else null
        val tps = if (decodeMs != null && decodeMs > 0 && outputTokens != null && outputTokens > 0) {
            outputTokens * 1000.0 / decodeMs
        } else {
            null
        }
        return Reading(ttftMs = ttft, outputTokens = outputTokens?.takeIf { it > 0 }, tokensPerSecond = tps)
    }

    fun formatTps(tps: Double): String = when {
        tps >= 100 -> "${tps.toInt()} tok/s"
        else -> String.format("%.1f tok/s", tps)
    }

    fun formatMs(ms: Long): String = when {
        ms >= 1000 -> String.format("%.1f s", ms / 1000.0)
        else -> "${ms}ms"
    }
}
