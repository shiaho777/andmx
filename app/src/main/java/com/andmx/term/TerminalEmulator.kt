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
    private var altColors: Array<LongArray>? = null
    private val scrollback = ArrayList<CharArray>()
    private val scrollbackColors = ArrayList<LongArray>()
    private val maxScrollback = 4000

    private var cr = 0
    private var cc = 0
    private var savedCr = 0
    private var savedCc = 0
    private var top = 0
    private var bottom = rows - 1

    /** Current SGR color index (0=default, 1-8 standard, 9-16 bright). */
    private var curFg = 0
    private var curBg = 0
    private var curAttrs = 0
    private var curStyle = 0L

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
        colors[cr][cc] = curStyle
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
        screen[bottom] = CharArray(cols) { ' ' }; colors[bottom] = LongArray(cols)
    }

    private fun scrollDown() {
        for (r in bottom downTo top + 1) { screen[r] = screen[r - 1]; colors[r] = colors[r - 1] }
        screen[top] = CharArray(cols) { ' ' }; colors[top] = LongArray(cols)
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
        if (p.isEmpty()) { curFg = 0; curBg = 0; curAttrs = 0; curStyle = packStyle(curFg, curBg, curAttrs); return }
        var i = 0
        while (i < p.size) {
            when (val code = p[i]) {
                0 -> { curFg = 0; curBg = 0; curAttrs = 0 }
                1 -> curAttrs = curAttrs or ATTR_BOLD
                2 -> curAttrs = curAttrs or ATTR_DIM
                3 -> curAttrs = curAttrs or ATTR_ITALIC
                4 -> curAttrs = curAttrs or ATTR_UNDERLINE
                7 -> curAttrs = curAttrs or ATTR_INVERSE
                22 -> curAttrs = curAttrs and (ATTR_BOLD or ATTR_DIM).inv()
                23 -> curAttrs = curAttrs and ATTR_ITALIC.inv()
                24 -> curAttrs = curAttrs and ATTR_UNDERLINE.inv()
                27 -> curAttrs = curAttrs and ATTR_INVERSE.inv()
                in 30..37 -> curFg = code - 29
                in 40..47 -> curBg = code - 39
                in 90..97 -> curFg = code - 90 + 8
                in 100..107 -> curBg = code - 100 + 8
                38 -> { val (v, adv) = extendedColor(p, i); if (v != null) curFg = v; i += adv }
                48 -> { val (v, adv) = extendedColor(p, i); if (v != null) curBg = v; i += adv }
                39 -> curFg = 0
                49 -> curBg = 0
                else -> {}
            }
            i++
        }
        curStyle = packStyle(curFg, curBg, curAttrs)
    }

    private fun extendedColor(p: List<Int>, i: Int): Pair<Int?, Int> {
        return when (p.getOrNull(i + 1)) {
            5 -> {
                val n = p.getOrNull(i + 2) ?: return null to 1
                val v = when {
                    n < 0 -> null
                    n < 16 -> map256(n)
                    n <= 255 -> 17 + n
                    else -> null
                }
                v to 2
            }
            2 -> {
                val r = p.getOrNull(i + 2); val g = p.getOrNull(i + 3); val b = p.getOrNull(i + 4)
                if (r == null || g == null || b == null) null to 1
                else (TRUECOLOR_FLAG or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)) to 4
            }
            else -> null to 1
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
            0 -> { eraseLine(0); for (r in cr + 1 until rows) { screen[r] = CharArray(cols) { ' ' }; colors[r] = LongArray(cols) } }
            1 -> { for (r in 0 until cr) { screen[r] = CharArray(cols) { ' ' }; colors[r] = LongArray(cols) }; eraseLine(1) }
            2, 3 -> { for (r in 0 until rows) { screen[r] = CharArray(cols) { ' ' }; colors[r] = LongArray(cols) }; if (mode == 3) { scrollback.clear(); scrollbackColors.clear() } }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = screen[cr]; val rowC = colors[cr]
        when (mode) {
            0 -> for (c in cc until cols) { row[c] = ' '; rowC[c] = 0L }
            1 -> for (c in 0..cc.coerceAtMost(cols - 1)) { row[c] = ' '; rowC[c] = 0L }
            2 -> for (c in 0 until cols) { row[c] = ' '; rowC[c] = 0L }
        }
    }

    private fun insertLine() { for (r in bottom downTo cr + 1) { screen[r] = screen[r - 1]; colors[r] = colors[r - 1] }; screen[cr] = CharArray(cols) { ' ' }; colors[cr] = LongArray(cols) }
    private fun deleteLine() { for (r in cr until bottom) { screen[r] = screen[r + 1]; colors[r] = colors[r + 1] }; screen[bottom] = CharArray(cols) { ' ' }; colors[bottom] = LongArray(cols) }
    private fun deleteChars(n: Int) { val row = screen[cr]; val rowC = colors[cr]; for (c in cc until cols) { row[c] = if (c + n < cols) row[c + n] else ' '; rowC[c] = if (c + n < cols) rowC[c + n] else 0L } }
    private fun insertChars(n: Int) { val row = screen[cr]; val rowC = colors[cr]; for (c in cols - 1 downTo cc) { row[c] = if (c - n >= cc) row[c - n] else ' '; rowC[c] = if (c - n >= cc) rowC[c - n] else 0L } }
    private fun eraseChars(n: Int) { val row = screen[cr]; val rowC = colors[cr]; for (c in cc until (cc + n).coerceAtMost(cols)) { row[c] = ' '; rowC[c] = 0L } }

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
        val allColors = ArrayList<LongArray>(scrollbackColors.size + rows).apply { addAll(scrollbackColors); for (r in 0 until rows) add(colors[r]) }
        for (idx in allRows.indices) {
            val row = allRows[idx]; val rowC = allColors[idx]
            val end = lastNonBlank(row)
            if (end < 0) { result.add(emptyList()); continue }
            val spans = ArrayList<Pair<String, Int>>()
            var sb = StringBuilder()
            var prevColor = legacyIndex(fgOf(rowC[0]))
            for (c in 0..end) {
                val col = legacyIndex(fgOf(rowC[c]))
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

    fun renderStyled(): List<List<StyledSpan>> {
        val result = ArrayList<List<StyledSpan>>()
        val allRows = ArrayList<CharArray>(scrollback.size + rows).apply { addAll(scrollback); for (r in 0 until rows) add(screen[r]) }
        val allStyles = ArrayList<LongArray>(scrollbackColors.size + rows).apply { addAll(scrollbackColors); for (r in 0 until rows) add(colors[r]) }
        for (idx in allRows.indices) {
            val row = allRows[idx]; val rowS = allStyles[idx]
            val end = lastNonBlank(row)
            if (end < 0) { result.add(emptyList()); continue }
            val spans = ArrayList<StyledSpan>()
            var sb = StringBuilder()
            var prev = rowS[0]
            for (c in 0..end) {
                val st = rowS[c]
                if (st != prev && sb.isNotEmpty()) {
                    spans.add(spanOf(sb.toString(), prev))
                    sb = StringBuilder()
                }
                sb.append(row[c])
                prev = st
            }
            if (sb.isNotEmpty()) spans.add(spanOf(sb.toString(), prev))
            result.add(spans)
        }
        return result
    }

    private fun spanOf(text: String, style: Long): StyledSpan {
        val attrs = attrsOf(style)
        return StyledSpan(
            text = text,
            fg = fgOf(style),
            bg = bgOf(style),
            bold = attrs and ATTR_BOLD != 0,
            dim = attrs and ATTR_DIM != 0,
            italic = attrs and ATTR_ITALIC != 0,
            underline = attrs and ATTR_UNDERLINE != 0,
            inverse = attrs and ATTR_INVERSE != 0,
        )
    }

    private fun legacyIndex(fg: Int): Int = when {
        fg <= 16 -> fg
        fg >= TRUECOLOR_FLAG -> 0
        else -> ((fg - 17) % 8) + 1
    }

    private fun lastNonBlank(row: CharArray): Int {
        var i = row.size - 1
        while (i >= 0 && row[i] == ' ') i--
        return i
    }

    companion object {
        const val TRUECOLOR_FLAG = 0x1000000

        private const val ATTR_BOLD = 1
        private const val ATTR_DIM = 2
        private const val ATTR_ITALIC = 4
        private const val ATTR_UNDERLINE = 8
        private const val ATTR_INVERSE = 16
        private const val COLOR_MASK = 0x3FFFFFFL

        private fun blank(r: Int, c: Int) = Array(r) { CharArray(c) { ' ' } }
        private fun blankColors(r: Int, c: Int) = Array(r) { LongArray(c) }

        private fun packStyle(fg: Int, bg: Int, attrs: Int): Long =
            (fg.toLong() and COLOR_MASK) or ((bg.toLong() and COLOR_MASK) shl 26) or ((attrs.toLong() and 0x3FL) shl 52)

        private fun fgOf(style: Long): Int = (style and COLOR_MASK).toInt()
        private fun bgOf(style: Long): Int = ((style ushr 26) and COLOR_MASK).toInt()
        private fun attrsOf(style: Long): Int = ((style ushr 52) and 0x3FL).toInt()

        /** Map color index to ARGB. 0=default fg, 1-7 standard, 8-15 bright. */
        val palette = intArrayOf(
            0xFFD4D4D4.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFFFFEE58.toInt(),
            0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFF26C6DA.toInt(), 0xFFFFFFFF.toInt(),
            0xFFB0BEC5.toInt(), 0xFFEF9A9A.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFF59D.toInt(),
            0xFF90CAF9.toInt(), 0xFFCE93D8.toInt(), 0xFF80DEEA.toInt(), 0xFFFFFFFF.toInt()
        )

        fun colorArgb(index: Int): Int =
            if (index in 0 until palette.size) palette[index] else palette[0]

        private val cubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)

        fun colorArgb256(n: Int): Int {
            val v = n.coerceIn(0, 255)
            if (v < 16) return palette[if (v < 8) v else v - 8]
            if (v < 232) {
                val i = v - 16
                return 0xFF000000.toInt() or (cubeLevels[i / 36] shl 16) or (cubeLevels[(i / 6) % 6] shl 8) or cubeLevels[i % 6]
            }
            val g = 8 + (v - 232) * 10
            return 0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
        }
    }
}

data class StyledSpan(
    val text: String,
    val fg: Int = 0,
    val bg: Int = 0,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)
