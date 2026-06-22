package com.andmx.agent

/**
 * Rough token estimator (no tokenizer dependency): CJK characters count ~1
 * token each, other text ~1 token per 4 chars. Good enough for a context
 * usage indicator.
 */
object TokenEstimate {
    fun estimate(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) {
            if (c.code in 0x4E00..0x9FFF || c.code in 0x3040..0x30FF || c.code in 0xAC00..0xD7AF) cjk++
            else other++
        }
        return cjk + (other + 3) / 4
    }

    fun estimateAll(texts: List<String>): Int = texts.sumOf { estimate(it) }
}
