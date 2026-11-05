package com.andmx.ui2.chat

data class AssistantCodeComment(
    val title: String,
    val body: String,
    val file: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val priority: Int? = null,
) {
    val location: String
        get() = when {
            startLine == null -> file
            endLine == null || endLine == startLine -> "$file:$startLine"
            else -> "$file:$startLine-$endLine"
        }

    val displayTitle: String
        get() = title.replace(Regex("""^\[P[0-3]\]\s*"""), "").ifBlank { title }
}

data class AssistantCodeCommentProjection(
    val text: String,
    val comments: List<AssistantCodeComment>,
)

object AssistantCodeComments {
    private const val MAX_COMMENTS = 50

    private val directive = Regex("""::code-comment\{([^}]*)\}""")
    private val attribute = Regex(
        """([A-Za-z_][A-Za-z0-9_-]*)\s*=\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([^\s"'}]+))""",
    )

    fun project(raw: String): AssistantCodeCommentProjection {
        if (raw.isBlank()) return AssistantCodeCommentProjection(raw, emptyList())
        val comments = ArrayList<AssistantCodeComment>()
        val out = ArrayList<String>()
        var inFence = false
        raw.split('\n').forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                out += line
            } else if (inFence) {
                out += line
            } else {
                val projected = projectLine(line, comments)
                if (!projected.standaloneRemoved) out += projected.text
            }
        }
        return AssistantCodeCommentProjection(
            out.joinToString("\n"),
            comments.take(MAX_COMMENTS),
        )
    }

    private data class LineProjection(val text: String, val standaloneRemoved: Boolean)

    private fun projectLine(
        line: String,
        comments: MutableList<AssistantCodeComment>,
    ): LineProjection {
        val trimmed = line.trim()
        val exact = directive.matchEntire(trimmed)
        if (exact != null) {
            val comment = parse(exact.groupValues[1])
            if (comment != null) {
                comments += comment
                return LineProjection("", standaloneRemoved = true)
            }
        }
        return LineProjection(replaceOutsideInlineCode(line, comments), standaloneRemoved = false)
    }

    private fun replaceOutsideInlineCode(
        line: String,
        comments: MutableList<AssistantCodeComment>,
    ): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            if (line[i] == '`') {
                val marker = if (line.startsWith("``", i)) "``" else "`"
                val end = line.indexOf(marker, i + marker.length)
                if (end < 0) {
                    out.append(line.substring(i))
                    break
                }
                out.append(line, i, end + marker.length)
                i = end + marker.length
            } else {
                val next = line.indexOf('`', i).let { if (it < 0) line.length else it }
                out.append(
                    directive.replace(line.substring(i, next)) { match ->
                        val comment = parse(match.groupValues[1])
                        if (comment == null) {
                            match.value
                        } else {
                            comments += comment
                            " "
                        }
                    },
                )
                i = next
            }
        }
        return out.toString()
    }

    private fun parse(raw: String): AssistantCodeComment? {
        val attributes = parseAttributes(raw)
        val title = attributes["title"]?.trim().orEmpty()
        val body = attributes["body"]?.trim().orEmpty()
        val file = attributes["file"]?.trim().orEmpty()
        if (title.isEmpty() || body.isEmpty() || file.isEmpty()) return null

        val start = attributes["start"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val requestedEnd = attributes["end"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val end = when {
            start == null -> null
            requestedEnd == null -> start
            requestedEnd < start -> null
            else -> requestedEnd
        }
        val priority = attributes["priority"]?.trim()?.toIntOrNull()?.takeIf { it in 0..3 }
        return AssistantCodeComment(
            title = title,
            body = body,
            file = file,
            startLine = if (end == null) null else start,
            endLine = end,
            priority = priority,
        )
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        attribute.findAll(raw).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: match.groups[4]?.value
                ?: ""
            out[key] = unescape(value)
        }
        return out
    }

    private fun unescape(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\\\", "\\")
}
