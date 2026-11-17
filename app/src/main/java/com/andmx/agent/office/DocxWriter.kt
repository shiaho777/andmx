package com.andmx.agent.office

object DocxWriter {
    private const val CT_DOCUMENT =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private const val CT_RELS = "application/vnd.openxmlformats-package.relationships+xml"

    fun write(model: DocumentModel): ByteArray {
        val entries = listOf(
            "[Content_Types].xml" to Ooxml.contentTypes(
                defaults = listOf("rels" to CT_RELS, "xml" to "application/xml"),
                overrides = listOf("/word/document.xml" to CT_DOCUMENT),
            ),
            "_rels/.rels" to Ooxml.rootRels("word/document.xml"),
            "word/document.xml" to documentXml(model),
            "word/_rels/document.xml.rels" to Ooxml.rels(),
        )
        return Ooxml.zip(entries)
    }

    internal fun documentXml(model: DocumentModel): String = buildString {
        append(Ooxml.XML_DECL)
        append("""<w:document xmlns:w="${Ooxml.NS_W}"><w:body>""")
        for (block in model.blocks) {
            when (block) {
                is DocBlock.Paragraph -> append(paragraphXml(block.paragraph))
                is DocBlock.Table -> append(tableXml(block.table))
            }
        }
        append("</w:body></w:document>")
    }

    private fun paragraphXml(paragraph: DocParagraph): String = buildString {
        append("<w:p>")
        val styleId = when (paragraph.style) {
            ParagraphStyle.HEADING1 -> "Heading1"
            ParagraphStyle.HEADING2 -> "Heading2"
            ParagraphStyle.HEADING3 -> "Heading3"
            else -> null
        }
        if (styleId != null) {
            append("""<w:pPr><w:pStyle w:val="$styleId"/></w:pPr>""")
        }
        val prefix = if (paragraph.style == ParagraphStyle.BULLET) "• " else ""
        if (prefix.isNotEmpty()) append(runXml(InlineRun(prefix)))
        for (run in paragraph.runs) append(runXml(run))
        append("</w:p>")
    }

    private fun runXml(run: InlineRun): String = buildString {
        append("<w:r>")
        if (run.bold) append("<w:rPr><w:b/></w:rPr>")
        append("""<w:t xml:space="preserve">""")
        append(Ooxml.escape(run.text))
        append("</w:t></w:r>")
    }

    private fun tableXml(table: DocTable): String = buildString {
        append("<w:tbl><w:tblPr><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            append("""<w:$edge w:val="single" w:sz="4" w:color="auto"/>""")
        }
        append("</w:tblBorders></w:tblPr>")
        for (row in table.rows) {
            append("<w:tr>")
            for (cell in row) {
                append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>")
                append("<w:p><w:r><w:t xml:space=\"preserve\">")
                append(Ooxml.escape(cell))
                append("</w:t></w:r></w:p></w:tc>")
            }
            append("</w:tr>")
        }
        append("</w:tbl>")
    }
}
