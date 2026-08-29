package com.andmx.agent

/**
 * Code-point-safe text elision.
 *
 * Kotlin's `String.take` / `takeLast` slice by UTF-16 code unit, so a cut can
 * land between a surrogate pair and push an unpaired surrogate into model
 * context (and later into a provider request that rejects or mangles it).
 * Everything that trims model-visible text goes through code-point indices.
 */
object TextTrimming {

    /** Number of Unicode code points in [text]. */
    fun length(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            i += charWidthAt(text, i)
            count++
        }
        return count
    }

    /** The first [count] code points of [text]. */
    fun take(text: String, count: Int): String {
        if (count <= 0) return ""
        var seen = 0
        var i = 0
        while (i < text.length && seen < count) {
            i += charWidthAt(text, i)
            seen++
        }
        return text.substring(0, i)
    }

    /** The last [count] code points of [text]. */
    fun takeLast(text: String, count: Int): String {
        if (count <= 0) return ""
        var seen = 0
        var i = text.length
        while (i > 0 && seen < count) {
            val low = text[i - 1]
            i -= if (low.isLowSurrogate() && i - 2 >= 0 && text[i - 2].isHighSurrogate()) 2 else 1
            seen++
        }
        return text.substring(i)
    }

    /**
     * Keep head and tail around an elision marker. [markerCount] receives the
     * number of code points dropped so the model can tell how much is missing.
     * Returns [text] unchanged when it already fits.
     */
    fun elide(text: String, limit: Int, markerCount: (omitted: Int) -> String): String {
        if (limit <= 0) return text
        val total = length(text)
        if (total <= limit) return text
        val head = limit / 2
        val tail = (limit - head).coerceAtLeast(0)
        return take(text, head) + markerCount(total - limit) + takeLast(text, tail)
    }

    /** UTF-16 units one code point occupies at [index] (1, or 2 for a pair). */
    private fun charWidthAt(text: String, index: Int): Int {
        val c = text[index]
        return if (c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
    }
}
