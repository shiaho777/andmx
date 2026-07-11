package com.andmx.term

/**
 * A grid-based VT100/xterm-ish terminal emulator: fixed rows × cols screen
 * with cursor addressing, scroll region, erase ops, scrollback and an
 * alternate screen. Enough for shells *and* full-screen curses apps.
 *
 * SGR color sequences are parsed into a per-cell color index; renderColored()
 * returns rows of (text, color) spans for colored rendering.
 */
class TerminalEmulator(rows: Int = 24, cols: Int = 80) {

    var rows = rows; private set
    var cols = cols; private set

    private var screen = blank(rows, cols)
    private var colors = blankColors(rows, cols)
    private var alt: Array<CharArray>? = null
    private var altColors: Array<IntArray>? = null
    private val scrollback = ArrayList<CharArray>()
    private val scrollbackColors = ArrayList<IntArray>()
    private val maxScrollback = 4000

    private var cr = 0
    private var cc = 0
    private var savedCr = 0
    private var savedCc = 0
    private var top = 0
    private var bottom = rows - 1

    /** Current SGR color index (0=default, 1-8 standard, 9-16 bright). */
    private var curColor = 0

    private enum class State { NORMAL, ESC, CSI, OSC }
    private var state = State.NORMAL
    private val csi = StringBuilder()

    var revision = 0L; private set

    fun feed(text: String) { for (c in text) consume(c); revision++ }

    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        val old = screen; val oldC = colors
        screen = blank(newRows, newCols); colors = blankColors(newRows, newCols)
        for (r in 0 until minOf(rows, newRows)) for (c in 0 until minOf(cols, newCols)) {
            screen[r][c] = old[r][c]; colors[r][c] = oldC[r][c]
        }
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
        screen[cr][cc] = c
        colors[cr][cc] = curColor
        cc++
    }

    private fun lineFeed() {
        if (cr == bottom) scrollUp() else cr = (cr + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        if (cr == top) scrollDown() else cr = (cr - 1).coerceAtLeast(0)
    }

    private fun scrollUp() {
        val line = screen[top]; val lineC = colors[top]
        if (alt == null && top == 0) {
            scrollback.add(line); scrollbackColors.add(lineC)
            while (scrollback.size > maxScrollback) { scrollback.removeAt(0); scrollbackColors.removeAt(0) }
        }
        for (r in top until bottom) { screen[r] = screen[r + 1]; colors[r] = colors[r + 1] }
        screen[bottom] = CharArray(cols) { ' ' }; colors[bottom] = IntArray(cols) { 0 }
    }

    private fun scrollDown() {
        for (r in bottom downTo top + 1) { screen[r] = screen[r - 1]; colors[r] = colors[r - 1] }
        screen[top] = CharArray(cols) { ' ' }; colors[top] = IntArray(cols) { 0 }
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
            'm' -> applySgr(p)
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

    private fun applySgr(p: List<Int>) {
        if (p.isEmpty()) { curColor = 0; return }
        var i = 0
        while (i < p.size) {
            when (val code = p[i]) {
                0 -> curColor = 0
                1 -> {}
                in 30..37 -> curColor = code - 29
                in 90..97 -> curColor = code - 90 + 8
                38 -> {
                    if (i + 1 < p.size && p[i + 1] == 5 && i + 2 < p.size) {
                        curColor = map256(p[i + 2]); i += 2
                    }
                }
                39 -> curColor = 0
                else -> {}
            }
            i++
        }
    }

    private fun map256(n: Int): Int = when {
        n < 8 -> n
        n < 16 -> n - 8
        else -> (n % 8) + 1
    }

    private fun privateMode(p: List<Int>, set: Boolean) {
        if (p.contains(1049) || p.contains(47) || p.contains(1047)) {
            if (set && alt == null) {
                alt = screen; altColors = colors
                screen = blank(rows, cols); colors = blankColors(rows, cols); cr = 0; cc = 0
            } else if (!set && alt != null) {
                screen = alt!!; colors = altColors!!; alt = null; altColors = null
            }
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in cr + 1 until rows) { screen[r] = CharArray(cols) { ' ' }; colors[r] = IntArray(cols) { 0 } } }
            1 -> { for (r in 0 until cr) { screen[r] = CharArray(cols) { ' ' }; colors[r] = IntArray(cols) { 0 } }; eraseLine(1) }
            2, 3 -> { for (r in 0 until rows) { screen[r] = CharArray(cols) { ' ' }; colors[r] = IntArray(cols) { 0 } }; if (mode == 3) { scrollback.clear(); scrollbackColors.clear() } }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = screen[cr]; val rowC = colors[cr]
        when (mode) {
            0 -> for (c in cc until cols) { row[c] = ' '; rowC[c] = 0 }
            1 -> for (c in 0..cc.coerceAtMost(cols - 1)) { row[c] = ' '; rowC[c] = 0 }
            2 -> for (c in 0 until cols) { row[c] = ' '; rowC[c] = 0 }
        }
    }

    private fun insertLine() { for (r in bottom downTo cr + 1) { screen[r] = screen[r - 1]; colors[r] = colors[r - 1] }; screen[cr] = CharArray(cols) { ' ' }; colors[cr] = IntArray(cols) { 0 } }
    private fun deleteLine() { for (r in cr until bottom) { screen[r] = screen[r + 1]; colors[r] = colors[r + 1] }; screen[bottom] = CharArray(cols) { ' ' }; colors[bottom] = IntArray(cols) { 0 } }
    private fun deleteChars(n: Int) { val row = screen[cr]; val rowC = colors[cr]; for (c in cc until cols) { row[c] = if (c + n < cols) row[c + n] else ' '; rowC[c] = if (c + n < cols) rowC[c + n] else 0 } }
    private fun insertChars(n: Int) { val row = screen[cr]; val rowC = colors[cr]; for (c in cols - 1 downTo cc) { row[c] = if (c - n >= cc) row[c - n] else ' '; rowC[c] = if (c - n >= cc) rowC[c - n] else 0 } }
    private fun eraseChars(n: Int) { val row = screen[cr]; val rowC = colors[cr]; for (c in cc until (cc + n).coerceAtMost(cols)) { row[c] = ' '; rowC[c] = 0 } }

    // ---- render ----
    fun render(): String = buildString {
        val all = ArrayList<CharArray>(scrollback.size + rows)
        all.addAll(scrollback)
        for (r in 0 until rows) all.add(screen[r])
        for ((idx, row) in all.withIndex()) {
            val end = lastNonBlank(row)
            for (c in 0..end) append(row[c])
            if (idx < all.size - 1) append('\n')
        }
    }

    /** Render with color: returns one list of (text, colorIndex) spans per line. */
    fun renderColored(): List<List<Pair<String, Int>>> {
        val result = ArrayList<List<Pair<String, Int>>>()
        val allRows = ArrayList<CharArray>(scrollback.size + rows).apply { addAll(scrollback); for (r in 0 until rows) add(screen[r]) }
        val allColors = ArrayList<IntArray>(scrollbackColors.size + rows).apply { addAll(scrollbackColors); for (r in 0 until rows) add(colors[r]) }
        for (idx in allRows.indices) {
            val row = allRows[idx]; val rowC = allColors[idx]
            val end = lastNonBlank(row)
            if (end < 0) { result.add(emptyList()); continue }
            val spans = ArrayList<Pair<String, Int>>()
            var sb = StringBuilder()
            var prevColor = rowC[0]
            for (c in 0..end) {
                val col = rowC[c]
                if (col != prevColor && sb.isNotEmpty()) {
                    spans.add(sb.toString() to prevColor)
                    sb = StringBuilder()
                }
                sb.append(row[c])
                prevColor = col
            }
            if (sb.isNotEmpty()) spans.add(sb.toString() to prevColor)
            result.add(spans)
        }
        return result
    }

    private fun lastNonBlank(row: CharArray): Int {
        var i = row.size - 1
        while (i >= 0 && row[i] == ' ') i--
        return i
    }

    companion object {
        private fun blank(r: Int, c: Int) = Array(r) { CharArray(c) { ' ' } }
        private fun blankColors(r: Int, c: Int) = Array(r) { IntArray(c) { 0 } }

        /** Map color index to ARGB. 0=default fg, 1-7 standard, 8-15 bright. */
        val palette = intArrayOf(
            0xFFD4D4D4.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFFFFEE58.toInt(),
            0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFF26C6DA.toInt(), 0xFFFFFFFF.toInt(),
            0xFFB0BEC5.toInt(), 0xFFEF9A9A.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFF59D.toInt(),
            0xFF90CAF9.toInt(), 0xFFCE93D8.toInt(), 0xFF80DEEA.toInt(), 0xFFFFFFFF.toInt()
        )

        fun colorArgb(index: Int): Int =
            if (index in 0 until palette.size) palette[index] else palette[0]
    }
}
