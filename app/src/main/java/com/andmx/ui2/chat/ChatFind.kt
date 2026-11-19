package com.andmx.ui2.chat

data class FindHit(
    val messageIndex: Int,
    val start: Int,
    val endExclusive: Int,
)

data class FindHighlight(
    val start: Int,
    val endExclusive: Int,
    val isCurrent: Boolean = false,
)

object ChatFind {

    fun find(messages: List<ChatMessage>, query: String): List<FindHit> {
        if (query.isBlank()) return emptyList()
        val hits = ArrayList<FindHit>()
        messages.forEachIndexed { index, message ->
            rangesIn(message.content, query).forEach { r ->
                hits += FindHit(index, r.first, r.last + 1)
            }
        }
        return hits
    }

    fun rangesIn(text: String, query: String): List<IntRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (from <= text.length - query.length) {
            val idx = text.indexOf(query, from, ignoreCase = true)
            if (idx < 0) break
            out += idx until idx + query.length
            from = idx + query.length
        }
        return out
    }

    fun wrap(index: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((index % total) + total) % total
    }

    fun highlightsFor(hits: List<FindHit>, messageIndex: Int, current: Int): List<FindHighlight> =
        hits.mapIndexedNotNull { i, hit ->
            if (hit.messageIndex != messageIndex) null
            else FindHighlight(hit.start, hit.endExclusive, i == current)
        }

    fun rebase(source: String, target: String, highlights: List<FindHighlight>): List<FindHighlight> {
        if (highlights.isEmpty()) return emptyList()
        val out = ArrayList<FindHighlight>(highlights.size)
        var cursor = 0
        for (h in highlights) {
            if (h.start < 0 || h.endExclusive > source.length || h.start >= h.endExclusive) continue
            val needle = source.substring(h.start, h.endExclusive)
            val idx = target.indexOf(needle, cursor, ignoreCase = true)
            if (idx < 0) continue
            out += FindHighlight(idx, idx + needle.length, h.isCurrent)
            cursor = idx + needle.length
        }
        return out
    }

    fun quote(text: String): String {
        val body = text.trim()
        if (body.isEmpty()) return ""
        return body.lines().joinToString("\n") { "> $it" } + "\n"
    }

    fun annotate(text: String, comment: String): String {
        val quoted = quote(text)
        val note = comment.trim()
        if (quoted.isEmpty()) return note
        if (note.isEmpty()) return quoted
        return quoted + note + "\n"
    }
}
