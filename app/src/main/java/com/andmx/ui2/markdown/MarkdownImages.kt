package com.andmx.ui2.markdown

import java.security.MessageDigest
import com.andmx.ui2.settings.tr

object MarkdownImages {
    data class ImageRef(val alt: String, val url: String)

    data class CacheEntry(val name: String, val sizeBytes: Long, val lastModified: Long)

    private val titleSuffix = Regex("""\s+(?:"[^"]*"|'[^']*'|\([^)]*\))$""")

    fun parseImageOnlyParagraph(text: String): List<ImageRef>? {
        val refs = ArrayList<ImageRef>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            var i = 0
            var found = false
            while (i < trimmed.length) {
                if (trimmed[i].isWhitespace()) {
                    i++
                    continue
                }
                val parsed = parseImageAt(trimmed, i) ?: return null
                refs.add(parsed.first)
                i = parsed.second
                found = true
            }
            if (!found) return null
        }
        return refs.ifEmpty { null }
    }

    private val schemePrefix = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun isSupportedUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val prefix = schemePrefix.find(url)?.value ?: return true
        val scheme = prefix.dropLast(1).lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "file") return false
        return url.startsWith("$scheme://", ignoreCase = true)
    }

    fun cacheKey(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun evictOldest(entries: List<CacheEntry>, limitBytes: Long): List<String> {
        var total = entries.sumOf { it.sizeBytes }
        if (total <= limitBytes) return emptyList()
        val removed = ArrayList<String>()
        for (entry in entries.sortedBy { it.lastModified }) {
            if (total <= limitBytes) break
            removed.add(entry.name)
            total -= entry.sizeBytes
        }
        return removed
    }

    fun sampleSize(width: Int, height: Int, maxEdge: Int = 2048): Int {
        val longest = maxOf(width, height)
        if (longest <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (longest / sample > maxEdge) sample *= 2
        return sample
    }

    private fun parseImageAt(text: String, start: Int): Pair<ImageRef, Int>? {
        if (!text.startsWith("![", start)) return null
        val mid = text.indexOf("](", start + 2)
        if (mid == -1) return null
        val end = matchingParen(text, mid + 2)
        if (end == -1) return null
        val inside = text.substring(mid + 2, end).trim()
        val url = titleSuffix.replace(inside, "").trim()
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null
        val alt = text.substring(start + 2, mid).ifBlank { tr("图片") }
        return ImageRef(alt, url) to end + 1
    }

    private fun matchingParen(text: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
