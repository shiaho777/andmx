package com.andmx.diff

/** One rendered diff line. */
data class DiffLine(
    val kind: Kind,
    val text: String,
    val oldNo: Int? = null,
    val newNo: Int? = null,
) {
    enum class Kind { CONTEXT, ADD, REMOVE }
}

data class DiffStats(val added: Int, val removed: Int)

/**
 * Pure LCS-based line diff. Small and dependency-free; good enough for the
 * file sizes an on-device agent edits, and trivially unit-testable.
 */
object DiffEngine {

    fun diff(oldText: String, newText: String): List<DiffLine> {
        val a = if (oldText.isEmpty()) emptyList() else oldText.split("\n")
        val b = if (newText.isEmpty()) emptyList() else newText.split("\n")
        return diffLines(a, b)
    }

    fun stats(lines: List<DiffLine>): DiffStats = DiffStats(
        added = lines.count { it.kind == DiffLine.Kind.ADD },
        removed = lines.count { it.kind == DiffLine.Kind.REMOVE },
    )

    /**
     * Diff stats without materializing every line. Common prefix/suffix are
     * trimmed first, so a one-line edit in a 10k-line file only costs the LCS
     * of the changed window. Huge windows fall back to multiset overlap to keep
     * the review dialog responsive and memory-bounded.
     */
    fun lineStats(oldText: String, newText: String): DiffStats {
        val a = if (oldText.isEmpty()) emptyList() else oldText.split("\n")
        val b = if (newText.isEmpty()) emptyList() else newText.split("\n")
        var start = 0
        val minSize = minOf(a.size, b.size)
        while (start < minSize && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val n = endA - start
        val m = endB - start
        if (n == 0 && m == 0) return DiffStats(0, 0)
        if (n == 0) return DiffStats(m, 0)
        if (m == 0) return DiffStats(0, n)
        val common = if (n.toLong() * m <= EXACT_CELL_BUDGET) {
            lcsLength(a, start, endA, b, start, endB)
        } else {
            multisetOverlap(a, start, endA, b, start, endB)
        }
        return DiffStats(added = m - common, removed = n - common)
    }

    private const val EXACT_CELL_BUDGET = 2_000_000L

    private fun lcsLength(
        a: List<String>,
        aStart: Int,
        aEnd: Int,
        b: List<String>,
        bStart: Int,
        bEnd: Int,
    ): Int {
        val n = aEnd - aStart
        val m = bEnd - bStart
        if (n == 0 || m == 0) return 0
        var prev = IntArray(m + 1)
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            val line = a[aStart + i - 1]
            for (j in 1..m) {
                cur[j] = if (line == b[bStart + j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[m]
    }

    private fun multisetOverlap(
        a: List<String>,
        aStart: Int,
        aEnd: Int,
        b: List<String>,
        bStart: Int,
        bEnd: Int,
    ): Int {
        val counts = HashMap<String, Int>(aEnd - aStart)
        for (i in aStart until aEnd) counts[a[i]] = (counts[a[i]] ?: 0) + 1
        var common = 0
        for (j in bStart until bEnd) {
            val left = counts[b[j]] ?: 0
            if (left > 0) {
                common++
                counts[b[j]] = left - 1
            }
        }
        return common
    }

    private fun diffLines(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size
        val m = b.size
        // LCS length table
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = ArrayList<DiffLine>()
        var i = 0
        var j = 0
        var oldNo = 1
        var newNo = 1
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out += DiffLine(DiffLine.Kind.CONTEXT, a[i], oldNo++, newNo++); i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    out += DiffLine(DiffLine.Kind.REMOVE, a[i], oldNo = oldNo++); i++
                }
                else -> {
                    out += DiffLine(DiffLine.Kind.ADD, b[j], newNo = newNo++); j++
                }
            }
        }
        while (i < n) out += DiffLine(DiffLine.Kind.REMOVE, a[i++], oldNo = oldNo++)
        while (j < m) out += DiffLine(DiffLine.Kind.ADD, b[j++], newNo = newNo++)
        return out
    }
}
