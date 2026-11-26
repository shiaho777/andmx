package com.andmx.ui2.chat

import com.andmx.diff.DiffLine

object DiffRender {

    data class Line(val diff: DiffLine, val emphasis: IntRange?)

    data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<Line>,
    ) {
        val header: String
            get() = "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
    }

    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    fun parse(diff: String): List<Hunk> {
        val out = ArrayList<Hunk>()
        var body = ArrayList<DiffLine>()
        var oldNo = 1
        var newNo = 1
        var oldStart = 1
        var newStart = 1
        var oldCount = 0
        var newCount = 0
        var declared = false
        var inHunk = false

        fun flush() {
            if (body.isEmpty()) return
            val os = if (declared) oldStart else body.firstNotNullOfOrNull { it.oldNo } ?: 0
            val ns = if (declared) newStart else body.firstNotNullOfOrNull { it.newNo } ?: 0
            val oc = if (declared) oldCount else body.count { it.oldNo != null }
            val nc = if (declared) newCount else body.count { it.newNo != null }
            out += Hunk(os, oc, ns, nc, pairInline(body))
            body = ArrayList()
        }

        for (raw in diff.split("\n")) {
            val m = hunkHeader.find(raw)
            if (m != null) {
                flush()
                declared = true
                inHunk = true
                oldStart = m.groupValues[1].toInt()
                oldCount = m.groupValues[2].toIntOrNull() ?: 1
                newStart = m.groupValues[3].toInt()
                newCount = m.groupValues[4].toIntOrNull() ?: 1
                oldNo = oldStart
                newNo = newStart
                continue
            }
            if (raw.startsWith("diff ") || raw.startsWith("index ") ||
                raw.startsWith("new file") || raw.startsWith("deleted file") ||
                raw.startsWith("similarity ") || raw.startsWith("rename ") ||
                raw.startsWith("***")
            ) {
                flush()
                declared = false
                inHunk = false
                oldNo = 1
                newNo = 1
                continue
            }
            if (!inHunk && (raw.startsWith("--- ") || raw.startsWith("+++ "))) continue
            if (raw.startsWith("\\")) continue
            when {
                raw.startsWith("+") -> body += DiffLine(DiffLine.Kind.ADD, raw.drop(1), newNo = newNo++)
                raw.startsWith("-") -> body += DiffLine(DiffLine.Kind.REMOVE, raw.drop(1), oldNo = oldNo++)
                raw.startsWith(" ") -> body += DiffLine(DiffLine.Kind.CONTEXT, raw.drop(1), oldNo++, newNo++)
            }
        }
        flush()
        return out
    }

    fun hunks(lines: List<DiffLine>): List<Hunk> {
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<Hunk>()
        var body = ArrayList<DiffLine>()

        fun flush() {
            if (body.isEmpty()) return
            out += Hunk(
                oldStart = body.firstNotNullOfOrNull { it.oldNo } ?: 0,
                oldCount = body.count { it.oldNo != null },
                newStart = body.firstNotNullOfOrNull { it.newNo } ?: 0,
                newCount = body.count { it.newNo != null },
                lines = pairInline(body),
            )
            body = ArrayList()
        }

        for (line in lines) {
            if (line.kind == DiffLine.Kind.CONTEXT && line.text.isEmpty() &&
                line.oldNo == null && line.newNo == null
            ) {
                flush()
            } else {
                body += line
            }
        }
        flush()
        return out
    }

    fun focusHunks(hunks: List<Hunk>, limit: Int): List<Hunk> {
        if (hunks.isEmpty()) return emptyList()
        val firstChanged = hunks.indexOfFirst { h -> h.lines.any { it.diff.kind != DiffLine.Kind.CONTEXT } }
        val start = if (firstChanged < 0) 0 else firstChanged
        val out = ArrayList<Hunk>()
        var used = 0
        for (i in start until hunks.size) {
            val h = hunks[i]
            if (used >= limit) break
            val room = limit - used
            if (h.lines.size <= room) {
                out += h
                used += h.lines.size
            } else {
                out += h.copy(lines = h.lines.take(room))
                used = limit
            }
        }
        return out
    }

    fun reviewHunks(lines: List<DiffLine>): List<Hunk> {
        val windows = mutableListOf<IntRange>()
        lines.forEachIndexed { index, line ->
            if (line.kind != DiffLine.Kind.CONTEXT) {
                val window = (index - 3).coerceAtLeast(0)..(index + 3).coerceAtMost(lines.lastIndex)
                val previous = windows.lastOrNull()
                if (previous != null && window.first <= previous.last + 1) {
                    windows[windows.lastIndex] = previous.first..window.last
                } else {
                    windows += window
                }
            }
        }
        return windows.flatMap { hunks(lines.subList(it.first, it.last + 1)) }
    }

    fun inlineRanges(old: String, new: String): Pair<IntRange?, IntRange?> {
        if (old == new) return null to null
        val minLen = minOf(old.length, new.length)
        var p = 0
        while (p < minLen && old[p] == new[p]) p++
        var s = 0
        while (s < minLen - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        if (p == 0 && s == 0) return null to null
        val oldMid = if (p < old.length - s) p..(old.length - s - 1) else null
        val newMid = if (p < new.length - s) p..(new.length - s - 1) else null
        return oldMid to newMid
    }

    private fun pairInline(lines: List<DiffLine>): List<Line> {
        val emphasis = arrayOfNulls<IntRange>(lines.size)
        var i = 0
        while (i < lines.size) {
            if (lines[i].kind == DiffLine.Kind.CONTEXT) {
                i++
                continue
            }
            var j = i
            while (j < lines.size && lines[j].kind != DiffLine.Kind.CONTEXT) j++
            val removes = (i until j).filter { lines[it].kind == DiffLine.Kind.REMOVE }
            val adds = (i until j).filter { lines[it].kind == DiffLine.Kind.ADD }
            val pairs = minOf(removes.size, adds.size)
            for (k in 0 until pairs) {
                val (rm, ad) = inlineRanges(lines[removes[k]].text, lines[adds[k]].text)
                emphasis[removes[k]] = rm
                emphasis[adds[k]] = ad
            }
            i = j
        }
        return lines.mapIndexed { index, line -> Line(line, emphasis[index]) }
    }
}
