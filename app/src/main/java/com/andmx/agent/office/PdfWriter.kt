package com.andmx.agent.office

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

object PdfWriter {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 56f
    private const val BODY_SIZE = 11f
    private const val LINE_GAP = 4f

    fun write(model: DocumentModel): ByteArray {
        val lines = layout(model)
        val document = PdfDocument()
        try {
            var pageIndex = 0
            var cursor = 0
            do {
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
                val page = document.startPage(info)
                cursor = drawPage(page.canvas, lines, cursor)
                document.finishPage(page)
                pageIndex += 1
            } while (cursor < lines.size)
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    private data class StyledLine(val text: String, val size: Float, val bold: Boolean)

    private fun layout(model: DocumentModel): List<StyledLine> {
        val lines = ArrayList<StyledLine>()
        for (block in model.blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    val p = block.paragraph
                    val size = when (p.style) {
                        ParagraphStyle.HEADING1 -> 20f
                        ParagraphStyle.HEADING2 -> 16f
                        ParagraphStyle.HEADING3 -> 13f
                        else -> BODY_SIZE
                    }
                    val bold = p.style != ParagraphStyle.NORMAL || p.runs.any { it.bold }
                    val prefix = if (p.style == ParagraphStyle.BULLET) "• " else ""
                    val indent = if (p.style == ParagraphStyle.BULLET) 14f else 0f
                    lines += wrap(prefix + p.text, size, bold, indent)
                    lines += StyledLine("", BODY_SIZE, false)
                }
                is DocBlock.Table -> {
                    for (row in block.table.rows) {
                        lines += wrap(row.joinToString("  |  "), BODY_SIZE, false, 0f)
                    }
                    lines += StyledLine("", BODY_SIZE, false)
                }
            }
        }
        return lines.ifEmpty { listOf(StyledLine("", BODY_SIZE, false)) }
    }

    private fun wrap(text: String, size: Float, bold: Boolean, indent: Float): List<StyledLine> {
        if (text.isEmpty()) return listOf(StyledLine("", size, bold))
        val paint = paintFor(size, bold)
        val maxWidth = PAGE_WIDTH - 2 * MARGIN - indent
        val out = ArrayList<StyledLine>()
        val current = StringBuilder()
        for (ch in text) {
            if (paint.measureText(current.toString() + ch) > maxWidth && current.isNotEmpty()) {
                out += StyledLine(current.toString(), size, bold)
                current.setLength(0)
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) out += StyledLine(current.toString(), size, bold)
        return out
    }

    private fun drawPage(canvas: Canvas, lines: List<StyledLine>, start: Int): Int {
        var y = MARGIN
        var index = start
        val bottom = PAGE_HEIGHT - MARGIN
        while (index < lines.size) {
            val line = lines[index]
            val paint = paintFor(line.size, line.bold)
            val lineHeight = line.size + LINE_GAP
            if (y + lineHeight > bottom) break
            y += line.size
            if (line.text.isNotEmpty()) {
                canvas.drawText(line.text, MARGIN, y, paint)
            }
            y += LINE_GAP
            index += 1
        }
        return index
    }

    private fun paintFor(size: Float, bold: Boolean): Paint = Paint().apply {
        isAntiAlias = true
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
}
