package com.andmx.agent.office

object XlsxWriter {
    private const val CT_WORKBOOK =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    private const val CT_SHEET =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    private const val CT_RELS = "application/vnd.openxmlformats-package.relationships+xml"
    private const val REL_WORKSHEET = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"

    fun write(model: DocumentModel): ByteArray = writeRows(model.toSheetRows())

    fun writeRows(rows: List<List<String>>): ByteArray {
        val entries = listOf(
            "[Content_Types].xml" to Ooxml.contentTypes(
                defaults = listOf("rels" to CT_RELS, "xml" to "application/xml"),
                overrides = listOf(
                    "/xl/workbook.xml" to CT_WORKBOOK,
                    "/xl/worksheets/sheet1.xml" to CT_SHEET,
                ),
            ),
            "_rels/.rels" to Ooxml.rootRels("xl/workbook.xml"),
            "xl/workbook.xml" to workbookXml(),
            "xl/_rels/workbook.xml.rels" to Ooxml.rels(
                Triple("rId1", REL_WORKSHEET, "worksheets/sheet1.xml"),
            ),
            "xl/worksheets/sheet1.xml" to sheetXml(rows),
        )
        return Ooxml.zip(entries)
    }

    private fun workbookXml(): String =
        Ooxml.XML_DECL +
            """<workbook xmlns="${Ooxml.NS_S}" xmlns:r="${Ooxml.NS_R}">""" +
            """<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    internal fun sheetXml(rows: List<List<String>>): String = buildString {
        append(Ooxml.XML_DECL)
        append("""<worksheet xmlns="${Ooxml.NS_S}"><sheetData>""")
        rows.forEachIndexed { rowIndex, row ->
            append("""<row r="${rowIndex + 1}">""")
            row.forEachIndexed { colIndex, cell ->
                val ref = columnRef(colIndex) + (rowIndex + 1)
                val number = cell.toDoubleOrNull()
                if (number != null && cell.isNotBlank()) {
                    append("""<c r="$ref"><v>""")
                    append(Ooxml.escape(cell.trim()))
                    append("</v></c>")
                } else {
                    append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">""")
                    append(Ooxml.escape(cell))
                    append("</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    internal fun columnRef(index: Int): String {
        var remaining = index
        val name = StringBuilder()
        while (true) {
            name.insert(0, 'A' + remaining % 26)
            remaining = remaining / 26 - 1
            if (remaining < 0) break
        }
        return name.toString()
    }
}
