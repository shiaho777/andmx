package com.andmx.ui.conversation

import com.andmx.workspace.GuestPaths

internal enum class MessageReferenceKind { FILE, WEB, UI_REFERENCE }

internal data class MessageReference(
    val kind: MessageReferenceKind,
    val label: String,
    val target: String,
    val meta: String = "",
    val assetPath: String = "",
) {
    val command: String
        get() = if (kind == MessageReferenceKind.UI_REFERENCE) "/references" else ""
}

internal fun messageReferences(markdown: String, limit: Int = 6): List<MessageReference> {
    val text = markdown.withoutFencedCode()
    val refs = (uiReferenceLines(text) + linkedReferences(text) + inlineFileReferences(text) + bareUrlReferences(text))
        .sortedBy { it.index }
        .map { it.reference }
    val seen = LinkedHashSet<String>()
    return refs.filter { ref ->
        seen.add("${ref.kind}:${ref.target}:${ref.label}")
    }.take(limit)
}

private data class IndexedReference(val index: Int, val reference: MessageReference)

private fun uiReferenceLines(text: String): List<IndexedReference> =
    text.lineSequence().fold(0 to mutableListOf<IndexedReference>()) { (offset, refs), line ->
        val trimmed = line.trim()
        val ref = when {
            trimmed.startsWith("🖼 ") -> uiReferenceFromLine(trimmed.removePrefix("🖼 ").trim(), image = true)
            trimmed.startsWith("📎 ") -> uiReferenceFromLine(trimmed.removePrefix("📎 ").trim(), image = false)
            else -> null
        }
        if (ref != null) refs += IndexedReference(offset + line.indexOf(trimmed).coerceAtLeast(0), ref)
        offset + line.length + 1 to refs
    }.second.toList()

private fun uiReferenceFromLine(raw: String, image: Boolean): MessageReference {
    val reference = Attachments.referencesFromDisplay("${if (image) "🖼" else "📎"} $raw").firstOrNull()
    val label = reference?.label ?: raw
    val meta = reference?.meta.orEmpty()
    val refId = meta.split(" · ").firstOrNull { it.matches(Regex("""ref:[a-f0-9]{8}""", RegexOption.IGNORE_CASE)) }.orEmpty()
    return MessageReference(
        kind = MessageReferenceKind.UI_REFERENCE,
        label = label,
        target = refId.ifBlank { label },
        meta = meta,
        assetPath = reference?.assetPath.orEmpty(),
    )
}

private fun linkedReferences(text: String): List<IndexedReference> {
    val markdownLink = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")
    return markdownLink.findAll(text).mapNotNull { match ->
        val label = match.groupValues[1].trim().ifBlank { match.groupValues[2].trim() }
        val target = match.groupValues[2].trim().trimReferenceTail()
        val ref = when {
            target.isWebUrl() -> MessageReference(MessageReferenceKind.WEB, label, target)
            target.isLikelyFilePath() -> MessageReference(MessageReferenceKind.FILE, label, normalizeFileTarget(target))
            else -> null
        } ?: return@mapNotNull null
        IndexedReference(match.range.first, ref)
    }.toList()
}

private fun inlineFileReferences(text: String): List<IndexedReference> {
    val codePath = Regex("""`([^`\n]+)`""")
    val mentionPath = Regex("""(?<![\w@])@([\w./~-]+)""")
    val codeRefs = codePath.findAll(text).mapNotNull { match ->
        val raw = match.groupValues[1].trim().trimReferenceTail()
        raw.takeIf { it.isLikelyFilePath() }?.let {
            IndexedReference(match.range.first, MessageReference(MessageReferenceKind.FILE, fileLabel(it), normalizeFileTarget(it)))
        }
    }
    val mentionRefs = mentionPath.findAll(text).mapNotNull { match ->
        val raw = match.groupValues[1].trim().trimReferenceTail()
        raw.takeIf { it.length > 1 && it.isLikelyFilePath() }?.let {
            IndexedReference(match.range.first, MessageReference(MessageReferenceKind.FILE, fileLabel(it), normalizeFileTarget(it)))
        }
    }
    return (codeRefs + mentionRefs).toList()
}

private fun bareUrlReferences(text: String): List<IndexedReference> {
    val bareUrl = Regex("""https?://[^\s)`\]}>]+""")
    return bareUrl.findAll(text).map { match ->
        val url = match.value.trimReferenceTail()
        IndexedReference(match.range.first, MessageReference(MessageReferenceKind.WEB, hostLabel(url), url))
    }.toList()
}

private fun String.withoutFencedCode(): String =
    replace(Regex("""(?s)```.*?```"""), " ")

private fun String.trimReferenceTail(): String =
    trim().trimEnd('.', ',', ';', ':', '!', '?')

private fun String.isWebUrl(): Boolean =
    startsWith("http://") || startsWith("https://")

private fun String.isLikelyFilePath(): Boolean {
    if (isBlank() || contains("://") || contains(' ')) return false
    if (startsWith("/")) {
        val withoutRoot = trimStart('/')
        return withoutRoot.contains('/') || withoutRoot.substringAfterLast('/').contains('.')
    }
    if (startsWith("./") || startsWith("../") || startsWith("~/")) return true
    if (!contains('/')) return false
    val first = substringBefore('/')
    if (first.startsWith("-")) return false
    return any { it == '.' } || startsWith("app/") || startsWith("src/") || startsWith("root/")
}

private fun normalizeFileTarget(path: String): String {
    val clean = path.removePrefix("@").removePrefix("~/")
    return when {
        path.startsWith("~/") -> GuestPaths.normalize(clean, "/root")
        clean.startsWith("root/") -> GuestPaths.normalize("/$clean")
        else -> GuestPaths.normalize(clean)
    }
}

private fun fileLabel(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }

private fun hostLabel(url: String): String =
    runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty().ifBlank { url }
