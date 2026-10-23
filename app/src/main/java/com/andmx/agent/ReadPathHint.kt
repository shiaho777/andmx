package com.andmx.agent

object ReadPathHint {
    fun suggest(entries: List<String>, targetName: String): String? {
        if (targetName.isBlank()) return null
        val files = entries
            .filter { !it.endsWith("/") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != targetName }
            .sorted()
        if (files.isEmpty()) return null
        val stem = targetName.substringBeforeLast('.', targetName)
        files.firstOrNull { it.substringBeforeLast('.', it) == stem }?.let { return it }
        return files.firstOrNull { levenshtein(it, targetName) <= 3 }
    }

    fun levenshtein(left: String, right: String): Int {
        val prev = IntArray(right.length + 1) { it }
        val cur = IntArray(right.length + 1)
        for (i in 1..left.length) {
            cur[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        return prev[right.length]
    }
}
