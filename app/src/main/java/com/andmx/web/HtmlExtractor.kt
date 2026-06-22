package com.andmx.web

/**
 * Very small HTML → readable-text extractor. Strips scripts/styles and tags,
 * decodes the common entities, and collapses whitespace. Good enough to feed
 * page content to the model for research; not a full DOM parser.
 */
object HtmlExtractor {

    fun toText(html: String): String {
        var s = html
        // drop non-content blocks
        s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?is)<head[^>]*>.*?</head>"), " ")
        // block tags -> newlines
        s = s.replace(Regex("(?i)<(/?)(p|div|br|li|tr|h[1-6]|section|article|header|footer)[^>]*>"), "\n")
        // remaining tags -> gone
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = decodeEntities(s)
        // collapse whitespace
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n[ \\t]*"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    fun title(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
}
