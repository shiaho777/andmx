package com.andmx.ui2.chat

enum class AssistantFileKind(val label: String) {
    PDF("PDF 文档"),
    MARKDOWN("Markdown 文档"),
    OFFICE("Office 文档"),
    VIDEO("视频"),
    AUDIO("音频"),
    WEB("网页"),
}

data class AssistantFileCard(
    val path: String,
    val name: String,
    val kind: AssistantFileKind,
)

data class AssistantFileProjection(
    val text: String,
    val cards: List<AssistantFileCard>,
)

object AssistantFileCitations {
    private const val MAX_CARDS = 10

    private val directive = Regex(""":{1,2}zcode-file-citation\{([^}]*)\}""")
    private val attribute = Regex(
        """([A-Za-z_][A-Za-z0-9_-]*)\s*=\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([^\s"'}]+))""",
    )
    private val markdownLink = Regex("""(?<!!)\[([^\]]*)\]\(([^)\s]+)\)""")

    private val markdownExtensions = setOf("md", "markdown")
    private val webExtensions = setOf("html", "htm")
    private val officeExtensions = setOf("docx", "xlsx", "pptx")
    private val videoExtensions = setOf("mp4", "mov", "webm", "m4v")
    private val audioExtensions = setOf("mp3", "wav", "m4a", "ogg", "opus", "flac", "weba")

    fun project(raw: String): AssistantFileProjection {
        if (raw.isBlank()) return AssistantFileProjection(raw, emptyList())
        val cards = LinkedHashMap<String, AssistantFileCard>()
        val out = StringBuilder(raw.length)
        var inFence = false
        val lines = raw.split('\n')
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                out.append(line)
            } else if (inFence) {
                out.append(line)
            } else {
                out.append(projectOutsideInlineCode(line, cards))
            }
            if (index != lines.lastIndex) out.append('\n')
        }
        return AssistantFileProjection(out.toString(), cards.values.toList().take(MAX_CARDS))
    }

    private fun projectOutsideInlineCode(
        line: String,
        cards: MutableMap<String, AssistantFileCard>,
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
                out.append(projectSegment(line.substring(i, next), cards))
                i = next
            }
        }
        return out.toString()
    }

    private fun projectSegment(
        segment: String,
        cards: MutableMap<String, AssistantFileCard>,
    ): String {
        markdownLink.findAll(segment).forEach { match ->
            val target = localPath(match.groupValues[2]) ?: return@forEach
            val card = cardFor(target, null) ?: return@forEach
            cards.putIfAbsent(card.path, card)
        }
        return directive.replace(segment) { match ->
            val attributes = parseAttributes(match.groupValues[1])
            val path = attributes["path"]?.trim().orEmpty()
            if (path.isEmpty()) return@replace match.value
            val card = cardFor(path, attributes["artifact_kind"])
            if (card != null) cards.putIfAbsent(card.path, card)
            val label = card?.name ?: leafName(decodePercent(path))
            "[$label](${encodePath(path)})"
        }
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

    private fun cardFor(path: String, artifactKind: String?): AssistantFileCard? {
        val clean = decodePercent(path).trim()
        if (clean.isEmpty()) return null
        val extension = clean.substringAfterLast('.', "").lowercase()
        val kind = when (artifactKind?.trim()?.lowercase()) {
            "document" -> if (extension == "docx") AssistantFileKind.OFFICE else null
            "presentation" -> if (extension == "pptx") AssistantFileKind.OFFICE else null
            "workbook" -> if (extension == "xlsx") AssistantFileKind.OFFICE else null
            "video" -> if (extension in videoExtensions) AssistantFileKind.VIDEO else null
            "audio" -> if (extension in audioExtensions) AssistantFileKind.AUDIO else null
            null, "" -> kindForExtension(extension)
            else -> null
        } ?: return null
        return AssistantFileCard(clean, leafName(clean), kind)
    }

    private fun kindForExtension(extension: String): AssistantFileKind? = when {
        extension == "pdf" -> AssistantFileKind.PDF
        extension in markdownExtensions -> AssistantFileKind.MARKDOWN
        extension in webExtensions -> AssistantFileKind.WEB
        extension in officeExtensions -> AssistantFileKind.OFFICE
        extension in videoExtensions -> AssistantFileKind.VIDEO
        extension in audioExtensions -> AssistantFileKind.AUDIO
        else -> null
    }

    private fun localPath(target: String): String? {
        if (target.startsWith("#") || target.startsWith("mailto:")) return null
        if (target.startsWith("file://")) return decodePercent(target.removePrefix("file://"))
        if (target.contains("://")) return null
        return decodePercent(target)
    }

    private fun leafName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

    private fun encodePath(path: String): String = path
        .replace("%", "%25")
        .replace(" ", "%20")
        .replace("(", "%28")
        .replace(")", "%29")
        .replace("#", "%23")

    private fun decodePercent(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 2 < value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    append(code.toChar())
                    i += 3
                    continue
                }
            }
            append(value[i])
            i++
        }
    }
}
