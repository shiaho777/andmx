package com.andmx.term

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * A grid-based VT100/xterm-ish terminal emulator: fixed rows × cols screen
 * with cursor addressing, scroll region, erase ops, SGR colour, scrollback and
 * an alternate screen. Enough for shells *and* full-screen curses apps
 * (vi/top/htop). Not exhaustive, but covers the sequences they actually use.
 */
class TerminalEmulator(rows: Int = 24, cols: Int = 80) {

    data class Cell(val ch: Char = ' ', val fg: Color = DefaultFg, val bold: Boolean = false)

    var rows = rows; private set
    var cols = cols; private set

    private var screen = blank(rows, cols)
    private var alt: Array<Array<Cell>>? = null
    private val scrollback = ArrayList<Array<Cell>>()
    private val maxScrollback = 4000

    private var cr = 0
    private var cc = 0
    private var savedCr = 0
    private var savedCc = 0
    private var top = 0
    private var bottom = rows - 1

    private var curFg = DefaultFg
    private var curBold = false

    private enum class State { NORMAL, ESC, CSI, OSC }
    private var state = State.NORMAL
    private val csi = StringBuilder()

    var revision = 0L; private set

    fun feed(text: String) { for (c in text) consume(c); revision++ }

    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        val old = screen
        screen = blank(newRows, newCols)
        for (r in 0 until minOf(rows, newRows)) for (c in 0 until minOf(cols, newCols)) screen[r][c] = old[r][c]
        rows = newRows; cols = newCols
        top = 0; bottom = rows - 1
        cr = cr.coerceIn(0, rows - 1); cc = cc.coerceIn(0, cols - 1)
        revision++
    }

    // ---- parser ----
    private fun consume(c: Char) {
        when (state) {
            State.NORMAL -> normal(c)
            State.ESC -> esc(c)
            State.CSI -> if (c in '\u0040'..'\u007E') { applyCsi(c); state = State.NORMAL } else csi.append(c)
            State.OSC -> if (c == '\u0007' || c == '\u001b') state = State.NORMAL
        }
    }

    private fun normal(c: Char) {
        when (c) {
            '\u001b' -> { csi.setLength(0); state = State.ESC }
            '\n' -> lineFeed()
            '\r' -> cc = 0
            '\b' -> { if (cc > 0) cc-- }
            '\t' -> { cc = ((cc / 8 + 1) * 8).coerceAtMost(cols - 1) }
            '\u0007' -> {}
            else -> if (c >= ' ') put(c)
        }
    }

    private fun esc(c: Char) {
        when (c) {
            '[' -> { csi.setLength(0); state = State.CSI }
            ']' -> state = State.OSC
            '7' -> { savedCr = cr; savedCc = cc; state = State.NORMAL }
            '8' -> { cr = savedCr; cc = savedCc; state = State.NORMAL }
            'M' -> { reverseIndex(); state = State.NORMAL }
            '(', ')' -> state = State.NORMAL
            else -> state = State.NORMAL
        }
    }

    private fun put(c: Char) {
        if (cc >= cols) { cc = 0; lineFeed() }
        screen[cr][cc] = Cell(c, curFg, curBold)
        cc++
    }

    private fun lineFeed() {
        if (cr == bottom) scrollUp() else cr = (cr + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        if (cr == top) scrollDown() else cr = (cr - 1).coerceAtLeast(0)
    }

    private fun scrollUp() {
        val line = screen[top]
        if (alt == null && top == 0) {
            scrollback.add(line)
            while (scrollback.size > maxScrollback) scrollback.removeAt(0)
        }
        for (r in top until bottom) screen[r] = screen[r + 1]
        screen[bottom] = Array(cols) { Cell() }
    }

    private fun scrollDown() {
        for (r in bottom downTo top + 1) screen[r] = screen[r - 1]
        screen[top] = Array(cols) { Cell() }
    }

    // ---- CSI ----
    private fun params(): List<Int> {
        val s = csi.toString().removePrefix("?")
        return if (s.isEmpty()) emptyList() else s.split(';').map { it.toIntOrNull() ?: 0 }
    }

    private fun applyCsi(final: Char) {
        val isPrivate = csi.startsWith("?")
        val p = params()
        fun p0(def: Int = 1) = p.getOrNull(0)?.takeIf { it != 0 } ?: def
        when (final) {
            'A' -> cr = (cr - p0()).coerceAtLeast(top)
            'B' -> cr = (cr + p0()).coerceAtMost(bottom)
            'C' -> cc = (cc + p0()).coerceAtMost(cols - 1)
            'D' -> cc = (cc - p0()).coerceAtLeast(0)
            'G' -> cc = (p0() - 1).coerceIn(0, cols - 1)
            'd' -> cr = (p0() - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { cr = (p.getOrNull(0)?.minus(1) ?: 0).coerceIn(0, rows - 1); cc = (p.getOrNull(1)?.minus(1) ?: 0).coerceIn(0, cols - 1) }
            'J' -> eraseDisplay(p.getOrNull(0) ?: 0)
            'K' -> eraseLine(p.getOrNull(0) ?: 0)
            'm' -> sgr(p)
            'r' -> { top = (p.getOrNull(0)?.minus(1) ?: 0).coerceIn(0, rows - 1); bottom = (p.getOrNull(1)?.minus(1) ?: (rows - 1)).coerceIn(top, rows - 1); cr = top; cc = 0 }
            'L' -> repeat(p0()) { insertLine() }
            'M' -> repeat(p0()) { deleteLine() }
            'P' -> deleteChars(p0())
            'X' -> eraseChars(p0())
            '@' -> insertChars(p0())
            's' -> { savedCr = cr; savedCc = cc }
            'u' -> { cr = savedCr; cc = savedCc }
            'h' -> if (isPrivate) privateMode(p, true)
            'l' -> if (isPrivate) privateMode(p, false)
        }
    }

    private fun privateMode(p: List<Int>, set: Boolean) {
        if (p.contains(1049) || p.contains(47) || p.contains(1047)) {
            if (set && alt == null) { alt = screen; screen = blank(rows, cols); cr = 0; cc = 0 }
            else if (!set && alt != null) { screen = alt!!; alt = null }
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in cr + 1 until rows) screen[r] = Array(cols) { Cell() } }
            1 -> { for (r in 0 until cr) screen[r] = Array(cols) { Cell() }; eraseLine(1) }
            2, 3 -> { for (r in 0 until rows) screen[r] = Array(cols) { Cell() }; if (mode == 3) scrollback.clear() }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = screen[cr]
        when (mode) {
            0 -> for (c in cc until cols) row[c] = Cell()
            1 -> for (c in 0..cc.coerceAtMost(cols - 1)) row[c] = Cell()
            2 -> for (c in 0 until cols) row[c] = Cell()
        }
    }

    private fun insertLine() { for (r in bottom downTo cr + 1) screen[r] = screen[r - 1]; screen[cr] = Array(cols) { Cell() } }
    private fun deleteLine() { for (r in cr until bottom) screen[r] = screen[r + 1]; screen[bottom] = Array(cols) { Cell() } }
    private fun deleteChars(n: Int) { val row = screen[cr]; for (c in cc until cols) row[c] = if (c + n < cols) row[c + n] else Cell() }
    private fun insertChars(n: Int) { val row = screen[cr]; for (c in cols - 1 downTo cc) row[c] = if (c - n >= cc) row[c - n] else Cell() }
    private fun eraseChars(n: Int) { val row = screen[cr]; for (c in cc until (cc + n).coerceAtMost(cols)) row[c] = Cell() }

    private fun sgr(codes: List<Int>) {
        val cs = codes.ifEmpty { listOf(0) }
        var i = 0
        while (i < cs.size) {
            when (val code = cs[i]) {
                0 -> { curFg = DefaultFg; curBold = false }
                1 -> curBold = true
                22 -> curBold = false
                in 30..37 -> curFg = Ansi16[code - 30]
                in 90..97 -> curFg = Ansi16[code - 90 + 8]
                39 -> curFg = DefaultFg
                38 -> when (cs.getOrNull(i + 1)) {
                    5 -> { curFg = xterm256(cs.getOrNull(i + 2) ?: 7); i += 2 }
                    2 -> { curFg = Color(cs.getOrNull(i + 2) ?: 0, cs.getOrNull(i + 3) ?: 0, cs.getOrNull(i + 4) ?: 0); i += 4 }
                }
            }
            i++
        }
    }

    // ---- render ----
    fun render(): AnnotatedString = buildAnnotatedString {
        val all = ArrayList<Array<Cell>>(scrollback.size + rows)
        all.addAll(scrollback)
        for (r in 0 until rows) all.add(screen[r])
        for ((idx, row) in all.withIndex()) {
            var c = 0
            val end = lastNonBlank(row)
            while (c <= end) {
                val base = row[c]
                var e = c + 1
                while (e <= end && row[e].fg == base.fg && row[e].bold == base.bold) e++
                withStyle(SpanStyle(color = base.fg, fontWeight = if (base.bold) FontWeight.Bold else FontWeight.Normal)) {
                    append(buildString { for (k in c until e) append(row[k].ch) })
                }
                c = e
            }
            if (idx < all.size - 1) append('\n')
        }
    }

    private fun lastNonBlank(row: Array<Cell>): Int {
        var i = row.size - 1
        while (i >= 0 && row[i].ch == ' ' && row[i].fg == DefaultFg) i--
        return i
    }

    companion object {
        val DefaultFg = Color(0xFFE6E6E6)
        private fun blank(r: Int, c: Int) = Array(r) { Array(c) { Cell() } }

        private val Ansi16 = arrayOf(
            Color(0xFF2E3436), Color(0xFFCC0000), Color(0xFF4E9A06), Color(0xFFC4A000),
            Color(0xFF3465A4), Color(0xFF75507B), Color(0xFF06989A), Color(0xFFD3D7CF),
            Color(0xFF555753), Color(0xFFEF2929), Color(0xFF8AE234), Color(0xFFFCE94F),
            Color(0xFF729FCF), Color(0xFFAD7FA8), Color(0xFF34E2E2), Color(0xFFEEEEEC),
        )

        private fun xterm256(n: Int): Color = when {
            n < 16 -> Ansi16[n.coerceIn(0, 15)]
            n in 16..231 -> {
                val v = n - 16; val r = v / 36; val g = (v % 36) / 6; val b = v % 6
                fun comp(x: Int) = if (x == 0) 0 else 55 + x * 40
                Color(comp(r), comp(g), comp(b))
            }
            else -> { val l = 8 + (n - 232) * 10; Color(l, l, l) }
        }
    }
}
