package com.andmx.agent.permission.guarded

data class CommandWord(
    val value: String,
    val dynamic: Boolean,
    val hasUnquotedGlob: Boolean,
    val start: Int,
    val end: Int,
)

fun literalPosixWord(text: String, start: Int, end: Int): CommandWord {
    var value = ""
    var quote = ""
    var dynamic = false
    var hasUnquotedGlob = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.toString() == quote) {
            quote = ""
            i++
            continue
        }
        if (quote != "'" && c == '\\') {
            if (i + 1 >= text.length) {
                dynamic = true
                break
            }
            val next = text[i + 1]
            if (quote.isEmpty() || next == '$' || next == '`' || next == '"' || next == '\\' || next == '\n') {
                if (next != '\n') value += next
                i += 2
                continue
            }
        }
        if (quote.isEmpty() && (c == '\'' || c == '"')) {
            quote = c.toString()
            i++
            continue
        }
        if (quote != "'" && (c == '$' || c == '`')) dynamic = true
        if (quote.isEmpty() && (c == '*' || c == '?' || c == '[' || c == '{' || c == '}' || c == '~')) dynamic = true
        if (quote.isEmpty() && (c == '*' || c == '?' || c == '[')) hasUnquotedGlob = true
        value += c
        i++
    }
    return CommandWord(value, dynamic || quote.isNotEmpty(), hasUnquotedGlob, start, end)
}

fun posixCommandWord(raw: String, start: Int, end: Int): CommandWord {
    val fact = literalPosixWord(raw, start, end)
    val literals = topLevelLiterals(raw) ?: return fact
    val glob = literals.any { literalPosixWord(it, 0, it.length).hasUnquotedGlob }
    return fact.copy(hasUnquotedGlob = glob)
}

fun commandBasename(word: CommandWord, windows: Boolean): String? {
    if (word.dynamic) return null
    val name = if (windows) {
        word.value.split(Regex("[\\\\/]")).last()
    } else {
        word.value.substringAfterLast('/')
    }
    return if (windows) name.lowercase().replace(Regex("\\.exe$"), "") else name
}

internal class ScanErrors {
    var unterminated: Boolean = false
}

private const val METACHARS = " \t\n|&;()<>"

internal fun scanWord(source: String, start: Int, errors: ScanErrors?): Int {
    var i = start
    while (i < source.length) {
        val c = source[i]
        when {
            c == '\\' -> {
                i += if (i + 1 < source.length) 2 else 1
            }
            c == '\'' -> i = skipSingle(source, i, errors)
            c == '"' -> i = skipDouble(source, i, errors)
            c == '$' -> i = skipDollar(source, i, errors)
            c == '`' -> i = skipBacktick(source, i, errors)
            c == '{' -> {
                val brace = scanBrace(source, i)
                i = if (brace > i) brace else i + 1
            }
            isExtglob(source, i) -> i = skipBalanced(source, i + 1, '(', ')', errors)
            c in METACHARS -> return i
            else -> i++
        }
    }
    return i
}

private fun topLevelLiterals(raw: String): List<String>? {
    var structural = false
    val literals = mutableListOf<String>()
    var litStart = 0
    var i = 0
    fun flush(end: Int) {
        if (end > litStart) literals.add(raw.substring(litStart, end))
        litStart = end
    }
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '\\' -> i += if (i + 1 < raw.length) 2 else 1
            c == '\'' || c == '"' || c == '$' || c == '`' -> {
                structural = true
                flush(i)
                i = when (c) {
                    '\'' -> skipSingle(raw, i, null)
                    '"' -> skipDouble(raw, i, null)
                    '$' -> skipDollar(raw, i, null)
                    else -> skipBacktick(raw, i, null)
                }
                litStart = i
            }
            c == '{' -> {
                val brace = scanBrace(raw, i)
                if (brace > i) {
                    structural = true
                    flush(i)
                    i = brace
                    litStart = i
                } else {
                    i++
                }
            }
            isExtglob(raw, i) -> {
                structural = true
                flush(i)
                i = skipBalanced(raw, i + 1, '(', ')', null)
                litStart = i
            }
            else -> i++
        }
    }
    if (!structural) return null
    flush(raw.length)
    return literals
}

private fun isExtglob(source: String, i: Int): Boolean {
    if (i + 1 >= source.length || source[i + 1] != '(') return false
    return source[i] == '?' || source[i] == '@' || source[i] == '*' || source[i] == '+' || source[i] == '!'
}

private fun skipSingle(source: String, i: Int, errors: ScanErrors?): Int {
    var j = i + 1
    while (j < source.length && source[j] != '\'') j++
    if (j >= source.length) {
        errors?.unterminated = true
        return j
    }
    return j + 1
}

private fun skipDouble(source: String, i: Int, errors: ScanErrors?): Int {
    var j = i + 1
    while (j < source.length) {
        when (source[j]) {
            '\\' -> j += if (j + 1 < source.length) 2 else 1
            '"' -> return j + 1
            '$' -> j = skipDollar(source, j, errors)
            '`' -> j = skipBacktick(source, j, errors)
            else -> j++
        }
    }
    errors?.unterminated = true
    return j
}

private fun skipDollar(source: String, i: Int, errors: ScanErrors?): Int {
    if (i + 1 >= source.length) return i + 1
    val next = source[i + 1]
    return when {
        next == '(' && i + 2 < source.length && source[i + 2] == '(' -> skipBalanced(source, i + 2, '(', ')', errors)
        next == '(' -> skipBalanced(source, i + 1, '(', ')', errors)
        next == '{' -> skipBalanced(source, i + 1, '{', '}', errors)
        next == '\'' -> skipSingle(source, i + 1, errors)
        next == '"' -> skipDouble(source, i + 1, errors)
        next == '$' -> i + 2
        isNameStart(next) -> {
            var j = i + 2
            while (j < source.length && isNameCont(source[j])) j++
            j
        }
        next in "@*#?-!" || next.isDigit() -> i + 2
        else -> i + 1
    }
}

private fun skipBacktick(source: String, i: Int, errors: ScanErrors?): Int {
    var j = i + 1
    while (j < source.length) {
        if (source[j] == '\\') {
            j += if (j + 1 < source.length) 2 else 1
            continue
        }
        if (source[j] == '`') return j + 1
        j++
    }
    errors?.unterminated = true
    return j
}

private fun skipBalanced(source: String, openIndex: Int, open: Char, close: Char, errors: ScanErrors?): Int {
    var depth = 1
    var j = openIndex + 1
    while (j < source.length && depth > 0) {
        when (val c = source[j]) {
            '\\' -> j += if (j + 1 < source.length) 2 else 1
            '\'' -> j = skipSingle(source, j, errors)
            '"' -> j = skipDouble(source, j, errors)
            '$' -> j = skipDollar(source, j, errors)
            '`' -> j = skipBacktick(source, j, errors)
            else -> {
                if (c == open) depth++
                else if (c == close) depth--
                j++
            }
        }
    }
    if (depth != 0) errors?.unterminated = true
    return j
}

private fun scanBrace(src: String, pos: Int): Int {
    if (pos + 1 >= src.length) return -1
    val next = src[pos + 1]
    if (next <= ' ' || next == '}') return -1
    var depth = 1
    var hasSep = false
    var i = pos + 1
    while (i < src.length && depth > 0) {
        val c = src[i]
        if (c == '{') {
            depth++
        } else if (c == '}') {
            depth--
            if (depth == 0) break
        } else if (c <= ' ' || c == ';' || c == '|' || c == '&') {
            return -1
        } else if (depth == 1 && (c == ',' || (c == '.' && i + 1 < src.length && src[i + 1] == '.'))) {
            hasSep = true
        }
        if (c == '\\' && i + 1 < src.length) i++
        i++
    }
    if (depth == 0 && hasSep) return i + 1
    return -1
}

private fun isNameStart(c: Char): Boolean = c == '_' || c.isLetter()

private fun isNameCont(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
