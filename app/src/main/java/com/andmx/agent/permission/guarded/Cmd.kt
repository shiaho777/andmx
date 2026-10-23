package com.andmx.agent.permission.guarded

data class CmdAnalysis(
    val commands: List<List<CommandWord>>,
    val unsupported: Boolean,
)

private val CMD_DELETE_BUILTIN = Regex("^(?:rd|rmdir|del|erase)$", RegexOption.IGNORE_CASE)
private val REM_LINE = Regex("^(?:rem\\s|::)", RegexOption.IGNORE_CASE)
private val REM_WORD = Regex("^(?:rem|::)$", RegexOption.IGNORE_CASE)

fun analyzeCmdCommand(source: String): CmdAnalysis {
    val commands = mutableListOf<List<CommandWord>>()
    var words = mutableListOf<CommandWord>()
    var value = ""
    var start = -1
    var quote = false
    var dynamic = false
    var hasUnquotedGlob = false
    var skipTarget = false
    var unsupported = false
    var invalidSegment = false
    var blockDepth = 0
    fun flushWord(end: Int) {
        if (start < 0) return
        if (!skipTarget) words.add(CommandWord(value, dynamic, hasUnquotedGlob, start, end))
        skipTarget = false
        value = ""
        start = -1
        dynamic = false
        hasUnquotedGlob = false
    }
    fun flushCommand(end: Int) {
        flushWord(end)
        if (!invalidSegment && blockDepth == 0 && words.isNotEmpty() && !REM_WORD.matches(words[0].value)) {
            commands.add(words)
        }
        words = mutableListOf()
        invalidSegment = blockDepth > 0
    }
    fun splitDeleteSwitch(index: Int) {
        val head = words.firstOrNull()?.value ?: value
        if (!skipTarget && CMD_DELETE_BUILTIN.matches(head)) {
            flushWord(index)
            start = index
        }
    }
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (!quote && c == '^') {
            if (start < 0) start = i
            val next = source.getOrNull(i + 1)
            if (next == null || next == '\r' || next == '\n') {
                unsupported = true
                invalidSegment = true
            } else {
                if (next == '/') splitDeleteSwitch(i)
                i++
                value += source[i]
            }
            i++
            continue
        }
        if (c == '"') {
            if (start < 0) start = i
            quote = !quote
            i++
            continue
        }
        if (!quote && (c == ' ' || c == '\t')) {
            flushWord(i)
            i++
            continue
        }
        if (!quote && (c == '&' || c == '|' || c == '\r' || c == '\n')) {
            flushCommand(i)
            if (source.getOrNull(i + 1) == c) i++
            i++
            continue
        }
        if (!quote && (c == '<' || c == '>')) {
            if (start >= 0 && value.matches(Regex("^\\d+$"))) {
                value = ""
                start = -1
            }
            flushWord(i)
            if (source.getOrNull(i + 1) == c) i++
            val after = source.getOrNull(i + 1)
            val after2 = source.getOrNull(i + 2)
            if (after == '&' && after2 != null && (after2.isDigit() || after2 == '-')) {
                i += 2
            } else {
                skipTarget = true
            }
            i++
            continue
        }
        if (!quote && (c == '(' || c == ')')) {
            unsupported = true
            invalidSegment = true
            blockDepth = if (c == '(') blockDepth + 1 else maxOf(0, blockDepth - 1)
        }
        if (!quote && c == '/') splitDeleteSwitch(i)
        if (start < 0) start = i
        if (c == '%' || c == '!') dynamic = true
        if (!quote && (c == '*' || c == '?' || c == '[')) hasUnquotedGlob = true
        value += c
        if (!quote && !skipTarget && words.isEmpty() && REM_LINE.containsMatchIn(source.substring(start))) {
            val newline = source.indexOf('\n', i)
            value = ""
            start = -1
            i = if (newline < 0) source.length else newline + 1
            continue
        }
        i++
    }
    if (quote || (skipTarget && start < 0)) {
        unsupported = true
        invalidSegment = true
    }
    flushCommand(source.length)
    return CmdAnalysis(commands, unsupported)
}
