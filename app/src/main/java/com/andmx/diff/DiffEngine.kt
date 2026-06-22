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
