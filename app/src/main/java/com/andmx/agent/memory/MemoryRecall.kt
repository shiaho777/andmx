package com.andmx.agent.memory

/**
 * Query-aware retrieval over raw memories.
 *
 * Memory injection used to dump MEMORY.md wholesale, which is fine for short
 * files but noisy once the store grows. Recall scores memories against the
 * current request and only injects the ones that plausibly matter.
 */
object MemoryRecall {
    private val tokenPattern = Regex("[\\p{L}\\p{N}_]+")
    private val cjkStopChars = setOf(
        '的', '了', '是', '我', '你', '他', '她', '它', '在', '和', '与',
        '就', '都', '也', '有', '不', '这', '那', '个', '们', '吗', '呢',
        '吧', '啊', '把', '被', '给', '对', '从', '到', '为', '以', '而',
    )

    /** Latin words plus CJK characters and bigrams (Chinese has no spaces). */
    fun queryTerms(query: String): Set<String> {
        if (query.isBlank()) return emptySet()
        val terms = mutableSetOf<String>()
        tokenPattern.findAll(query.lowercase()).forEach { match ->
            val token = match.value
            if (token.any { it.code >= 0x2E80 }) {
                val chars = token.filter { it.code >= 0x2E80 && it !in cjkStopChars }
                chars.forEach { terms += it.toString() }
                chars.windowed(2).forEach { terms += it }
            } else if (token.length >= 2) {
                terms += token
            }
        }
        return terms
    }

    fun rank(
        query: String,
        memories: List<MemorySystem.ParsedMemory>,
        limit: Int = 5,
    ): List<MemorySystem.ParsedMemory> {
        if (limit <= 0) return emptyList()
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        return memories
            .map { it to score(terms, it) }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<MemorySystem.ParsedMemory, Double>> { it.second }
                    .thenByDescending { it.first.timestamp },
            )
            .take(limit)
            .map { it.first }
    }

    private fun score(
        terms: Set<String>,
        memory: MemorySystem.ParsedMemory,
    ): Double {
        val body = memory.content.lowercase()
        var score = 0.0
        for (term in terms) {
            if (!body.contains(term)) continue
            score += if (term.length == 1) 0.4 else 1.0
        }
        if (score <= 0.0) return 0.0

        val categoryBoost = when {
            memory.category == MemorySystem.MemoryCategory.FAILURE_SHIELD &&
                terms.any { it in ERROR_TERMS } -> 1.5
            memory.category == MemorySystem.MemoryCategory.USER_PREFERENCE &&
                terms.any { it in PREFERENCE_TERMS } -> 1.0
            memory.category == MemorySystem.MemoryCategory.REPO_MAP &&
                terms.any { it in REPO_TERMS } -> 0.8
            else -> 0.0
        }
        return score + categoryBoost
    }

    private val ERROR_TERMS = setOf("error", "fail", "failed", "bug", "crash", "报错", "失败", "异常", "修复")
    private val PREFERENCE_TERMS = setOf("prefer", "always", "never", "偏好", "习惯", "一直", "不要", "必须")
    private val REPO_TERMS = setOf("file", "path", "config", "entry", "dir", "文件", "路径", "配置", "入口", "目录")
}
