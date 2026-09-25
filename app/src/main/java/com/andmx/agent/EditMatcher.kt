package com.andmx.agent

/**
 * ZCode edit-matchers.ts 对齐移植：Edit 的 old_string 多级匹配。
 * 精确失败后按序回退：引号归一 → 行号前缀剥离 → 可见转义归一 →
 * unicode 转义归一 → 行 trim → 缩进弹性 → 块锚点（≥0.8 中間行相似度）。
 */
object EditMatcher {

    enum class Strategy {
        EXACT, QUOTE_NORMALIZED, LINE_NUMBER_PREFIX_STRIPPED,
        ESCAPE_NORMALIZED, UNICODE_ESCAPE_NORMALIZED,
        LINE_TRIMMED, INDENTATION_FLEXIBLE, BLOCK_ANCHOR,
    }

    sealed interface Result {
        data class Matched(
            val actualString: String,
            val strategy: Strategy,
            val candidateCount: Int,
        ) : Result

        data class Ambiguous(val strategy: Strategy, val candidateCount: Int) : Result
        data object NotFound : Result
    }

    private data class Candidate(val value: String, val index: Int)

    private val BROAD_MATCHERS = setOf(
        Strategy.LINE_TRIMMED, Strategy.INDENTATION_FLEXIBLE, Strategy.BLOCK_ANCHOR,
    )
    private const val BLOCK_ANCHOR_MIN_SIMILARITY = 0.8

    fun findEditMatch(content: String, search: String, replaceAll: Boolean): Result {
        val exact = collectSubstringCandidates(content, search)
        if (exact.isNotEmpty()) return toResult(Strategy.EXACT, exact)

        val order = listOf(
            Strategy.QUOTE_NORMALIZED,
            Strategy.LINE_NUMBER_PREFIX_STRIPPED,
            Strategy.ESCAPE_NORMALIZED,
            Strategy.UNICODE_ESCAPE_NORMALIZED,
            Strategy.LINE_TRIMMED,
            Strategy.INDENTATION_FLEXIBLE,
            Strategy.BLOCK_ANCHOR,
        )
        for (strategy in order) {
            if (replaceAll && strategy in BROAD_MATCHERS) continue
            val candidates = collectCandidates(strategy, content, search)
            if (candidates.isEmpty()) continue
            return toResult(strategy, candidates)
        }
        return Result.NotFound
    }

    /** escape_normalized 命中时 new_string 同样做可见转义反转义。 */
    fun normalizeReplacementForMatch(strategy: Strategy, newString: String): String =
        if (strategy == Strategy.ESCAPE_NORMALIZED) unescapeVisibleCharacters(newString) else newString

    /** 模糊命中后保持文件原有引号风格（弯引号文件 → 替换文本也用弯引号）。 */
    fun preserveQuoteStyle(oldString: String, actualOldString: String, newString: String): String {
        if (oldString == actualOldString) return newString
        var result = newString
        if (actualOldString.contains('“') || actualOldString.contains('”')) {
            result = applyCurlyDoubleQuotes(result)
        }
        if (actualOldString.contains('‘') || actualOldString.contains('’')) {
            result = applyCurlySingleQuotes(result)
        }
        return result
    }

    private fun collectCandidates(
        strategy: Strategy,
        content: String,
        search: String,
    ): List<Candidate> = when (strategy) {
        Strategy.EXACT -> collectSubstringCandidates(content, search)
        Strategy.QUOTE_NORMALIZED -> collectNormalizedCandidates(content, search, ::normalizeQuotes)
        Strategy.LINE_NUMBER_PREFIX_STRIPPED -> collectLineNumberPrefixCandidates(content, search)
        Strategy.ESCAPE_NORMALIZED -> collectEscapeNormalizedCandidates(content, search)
        Strategy.UNICODE_ESCAPE_NORMALIZED -> collectUnicodeEscapeNormalizedCandidates(content, search)
        Strategy.LINE_TRIMMED -> collectLineTrimmedCandidates(content, search)
        Strategy.INDENTATION_FLEXIBLE -> collectIndentationFlexibleCandidates(content, search)
        Strategy.BLOCK_ANCHOR -> collectBlockAnchorCandidates(content, search)
    }

    private fun toResult(strategy: Strategy, candidates: List<Candidate>): Result {
        val uniqueValues = candidates.map { it.value }.distinct()
        if (uniqueValues.size != 1) {
            return Result.Ambiguous(strategy, candidates.size)
        }
        return Result.Matched(uniqueValues[0], strategy, candidates.size)
    }

    private fun collectSubstringCandidates(content: String, search: String): List<Candidate> {
        if (search.isEmpty()) return emptyList()
        val candidates = ArrayList<Candidate>()
        var position = 0
        while (position <= content.length) {
            val index = content.indexOf(search, position)
            if (index < 0) break
            candidates += Candidate(search, index)
            position = index + maxOf(search.length, 1)
        }
        return candidates
    }

    private fun collectNormalizedCandidates(
        content: String,
        search: String,
        normalize: (String) -> String,
    ): List<Candidate> {
        val normalizedContent = normalize(content)
        val normalizedSearch = normalize(search)
        val candidates = ArrayList<Candidate>()
        var position = 0
        while (position <= normalizedContent.length) {
            val index = normalizedContent.indexOf(normalizedSearch, position)
            if (index < 0) break
            candidates += Candidate(content.substring(index, index + search.length), index)
            position = index + maxOf(normalizedSearch.length, 1)
        }
        return candidates
    }

    private fun collectLineNumberPrefixCandidates(content: String, search: String): List<Candidate> {
        val stripped = stripReadLineNumberPrefixes(search) ?: return emptyList()
        if (stripped == search) return emptyList()
        return collectSubstringCandidates(content, stripped)
    }

    private fun collectEscapeNormalizedCandidates(content: String, search: String): List<Candidate> {
        val unescaped = unescapeVisibleCharacters(search)
        if (unescaped == search) return emptyList()
        return collectSubstringCandidates(content, unescaped)
    }

    private fun collectUnicodeEscapeNormalizedCandidates(
        content: String,
        search: String,
    ): List<Candidate> {
        val unescaped = unescapeUnicodeCharacters(search)
        if (unescaped == search) return emptyList()
        return collectSubstringCandidates(content, unescaped)
    }

    private fun collectLineTrimmedCandidates(content: String, search: String): List<Candidate> {
        val contentLines = content.split("\n")
        val searchLines = trimTrailingEmptyLine(search.split("\n"))
        if (searchLines.isEmpty()) return emptyList()
        val candidates = ArrayList<Candidate>()
        for (index in 0..contentLines.size - searchLines.size) {
            var ok = true
            for (offset in searchLines.indices) {
                if (contentLines[index + offset].trim() != searchLines[offset].trim()) {
                    ok = false
                    break
                }
            }
            if (ok) candidates += blockCandidate(contentLines, index, searchLines.size)
        }
        return candidates
    }

    private fun collectIndentationFlexibleCandidates(
        content: String,
        search: String,
    ): List<Candidate> {
        val contentLines = content.split("\n")
        val searchLines = trimTrailingEmptyLine(search.split("\n"))
        if (searchLines.size < 2) return emptyList()
        val normalizedSearch = removeCommonIndent(searchLines)
        val candidates = ArrayList<Candidate>()
        for (index in 0..contentLines.size - searchLines.size) {
            val block = contentLines.subList(index, index + searchLines.size)
            if (removeCommonIndent(block) != normalizedSearch) continue
            candidates += blockCandidate(contentLines, index, searchLines.size)
        }
        return candidates
    }

    private fun collectBlockAnchorCandidates(content: String, search: String): List<Candidate> {
        val contentLines = content.split("\n")
        val searchLines = trimTrailingEmptyLine(search.split("\n"))
        if (searchLines.size < 3) return emptyList()
        val first = searchLines.first().trim()
        val last = searchLines.last().trim()
        val candidates = ArrayList<Candidate>()
        for (index in 0..contentLines.size - searchLines.size) {
            val block = contentLines.subList(index, index + searchLines.size)
            if (block.first().trim() != first) continue
            if (block.last().trim() != last) continue
            if (averageMiddleSimilarity(block, searchLines) < BLOCK_ANCHOR_MIN_SIMILARITY) continue
            candidates += blockCandidate(contentLines, index, searchLines.size)
        }
        return candidates
    }

    private fun stripReadLineNumberPrefixes(search: String): String? {
        val lines = search.split("\n")
        val stripped = lines.map { line ->
            val colon = Regex("""^\d+: (.*)$""").find(line)
            if (colon != null) return@map colon.groupValues[1]
            val tab = Regex("""^\d+\t(.*)$""").find(line)
            if (tab != null) return@map tab.groupValues[1]
            return null
        }
        return stripped.joinToString("\n")
    }

    private fun unescapeVisibleCharacters(search: String): String =
        Regex("""\\([ntr"'`\\$])""").replace(search) { m ->
            when (m.groupValues[1]) {
                "n" -> "\n"
                "t" -> "\t"
                "r" -> "\r"
                "\"", "'", "`", "\\", "$" -> m.groupValues[1]
                else -> m.value
            }
        }

    private fun unescapeUnicodeCharacters(search: String): String =
        Regex("""(\\\\)|\\u([0-9a-fA-F]{4})""").replace(search) { m ->
            if (m.groupValues[1].isNotEmpty()) return@replace m.value
            m.groupValues[2].toInt(16).toChar().toString()
        }

    private fun blockCandidate(lines: List<String>, startLine: Int, lineCount: Int): Candidate {
        var offset = 0
        for (i in 0 until startLine) offset += lines[i].length + 1
        return Candidate(lines.subList(startLine, startLine + lineCount).joinToString("\n"), offset)
    }

    private fun trimTrailingEmptyLine(lines: List<String>): List<String> =
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines

    private fun removeCommonIndent(lines: List<String>): String {
        val nonEmpty = lines.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return lines.joinToString("\n")
        val minIndent = nonEmpty.minOf { line -> line.takeWhile { it == ' ' || it == '\t' }.length }
        return lines.joinToString("\n") { if (it.isBlank()) it else it.drop(minIndent) }
    }

    private fun averageMiddleSimilarity(actual: List<String>, expected: List<String>): Double {
        if (actual.size <= 2) return 1.0
        var total = 0.0
        var count = 0
        for (i in 1 until actual.size - 1) {
            total += lineSimilarity(actual[i].trim(), expected[i].trim())
            count++
        }
        return if (count == 0) 1.0 else total / count
    }

    private fun lineSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        val maxLen = maxOf(left.length, right.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(left, right).toDouble() / maxLen
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (i in 1..left.length) {
            val current = IntArray(right.length + 1) { 0 }
            current[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun normalizeQuotes(value: String): String = value
        .replace('‘', '\'').replace('’', '\'')
        .replace('“', '"').replace('”', '"')

    private fun applyCurlyDoubleQuotes(value: String): String {
        val chars = value.toCharArray()
        return chars.mapIndexed { index, c ->
            if (c != '"') c else if (isOpeningQuoteContext(chars, index)) '“' else '”'
        }.joinToString("")
    }

    private fun applyCurlySingleQuotes(value: String): String {
        val chars = value.toCharArray()
        return chars.mapIndexed { index, c ->
            if (c != '\'') return@mapIndexed c
            val prev = chars.getOrNull(index - 1)
            val next = chars.getOrNull(index + 1)
            if (prev != null && next != null && prev.isLetter() && next.isLetter()) {
                '’'
            } else if (isOpeningQuoteContext(chars, index)) '‘' else '’'
        }.joinToString("")
    }

    private fun isOpeningQuoteContext(chars: CharArray, index: Int): Boolean {
        if (index == 0) return true
        return chars[index - 1] in charArrayOf(' ', '\t', '\n', '\r', '(', '[', '{', '—', '–')
    }
}
