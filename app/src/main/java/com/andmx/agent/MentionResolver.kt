package com.andmx.agent

/**
 * Resolves an in-progress `@path` mention at the caret for file autocomplete.
 * Pure: given the draft text it returns which directory to list and the prefix
 * to filter by; [complete] rewrites the draft once a candidate is chosen.
 */
object MentionResolver {

    data class Query(
        val atIndex: Int,
        /** Guest directory to list candidates from. */
        val listDir: String,
        /** The path segment typed before the final '/', kept verbatim. */
        val dirPart: String,
        /** Prefix to match candidate names against. */
        val prefix: String,
    )

    fun parse(text: String): Query? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        val token = text.substring(at + 1)
        if (token.any { it == ' ' || it == '\n' || it == '\t' }) return null

        val dirPart = if (token.contains('/')) token.substringBeforeLast('/') + "/" else ""
        val prefix = token.substringAfterLast('/')
        val listDir = when {
            token.startsWith('/') -> if (dirPart.isEmpty()) "/" else dirPart.trimEnd('/').ifEmpty { "/" }
            dirPart.isEmpty() -> "/root"
            else -> "/root/" + dirPart.trimEnd('/')
        }
        return Query(at, listDir, dirPart, prefix)
    }

    fun complete(text: String, q: Query, choice: String): String {
        val head = text.substring(0, q.atIndex + 1)
        val trailing = if (choice.endsWith("/")) "" else " "
        return head + q.dirPart + choice + trailing
    }
}
