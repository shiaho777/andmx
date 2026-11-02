package com.andmx.agent.permission

import com.andmx.agent.permission.guarded.CommandWord
import com.andmx.agent.permission.guarded.ScanErrors
import com.andmx.agent.permission.guarded.posixCommandWord
import com.andmx.agent.permission.guarded.scanWord

data class BashCommandInvocation(
    val words: List<CommandWord>,
    val operatorBefore: String? = null,
)

data class BashCommandAnalysis(
    val commands: List<BashCommandInvocation>,
    val hasDynamicWords: Boolean,
    val hasParseErrors: Boolean,
    val hasRedirects: Boolean,
    val hasUnsupportedSyntax: Boolean,
    val unsupportedNodeTypes: List<String>,
)

private const val MAX_BASH_PARSE_LENGTH = 10_000
private val TIME_WORD = Regex("\\btime\\b")
private val SUPPORTED_CONTAINERS = setOf("AndOr", "Pipeline", "Statement")

fun analyzeBashCommand(command: String): BashCommandAnalysis {
    if (command.trim().isEmpty()) return emptyAnalysis()
    if (command.length > MAX_BASH_PARSE_LENGTH) return emptyAnalysis(parseErrors = true)
    val parser = BashParser(command)
    val statements = parser.parseList()
    parser.finishTopLevel()
    return walk(command, statements, parser.failed || parser.lexer.failed)
}

private fun emptyAnalysis(parseErrors: Boolean = false) = BashCommandAnalysis(
    commands = emptyList(),
    hasDynamicWords = false,
    hasParseErrors = parseErrors,
    hasRedirects = false,
    hasUnsupportedSyntax = false,
    unsupportedNodeTypes = emptyList(),
)

private class Invocation(
    val words: MutableList<CommandWord>,
    val operatorBefore: String?,
)

private fun walk(source: String, statements: List<StmtNode>, parseErrors: Boolean): BashCommandAnalysis {
    val commands = mutableListOf<Invocation>()
    val unsupported = linkedSetOf<String>()
    var hasDynamic = false
    var hasRedirects = false
    fun collect(node: Node, operatorBefore: String?) {
        when (node) {
            is CmdNode -> {
                if (node.words.any { it.dynamic }) hasDynamic = true
                if (node.redirects > 0) hasRedirects = true
                commands.add(Invocation(node.words.toMutableList(), operatorBefore))
            }
            is AndOrNode -> node.commands.forEachIndexed { index, child ->
                val op = if (index == 0) operatorBefore else node.operators[index - 1]
                collect(child, op)
            }
            is PipeNode -> {
                val firstIndex = commands.size
                node.commands.forEachIndexed { index, child ->
                    val op = if (index == 0) operatorBefore else node.operators[index - 1]
                    collect(child, op)
                }
                if (node.time) {
                    val first = commands.getOrNull(firstIndex)
                    val firstWord = first?.words?.firstOrNull()
                    if (first != null && firstWord != null && node.pos <= firstWord.start) {
                        val prefix = source.substring(node.pos, firstWord.start)
                        val match = TIME_WORD.find(prefix)
                        if (match != null) {
                            val at = node.pos + match.range.first
                            first.words.add(0, CommandWord("time", false, false, at, at + 4))
                        }
                    }
                }
            }
            is StmtNode -> {
                if (node.background) unsupported.add("background")
                collect(node.command, operatorBefore)
            }
            is OtherNode -> unsupported.add(node.typeName)
        }
    }
    statements.forEachIndexed { index, stmt ->
        collect(stmt, if (index == 0) null else "sequence")
    }
    val types = unsupported.filter { it !in SUPPORTED_CONTAINERS }
    return BashCommandAnalysis(
        commands = commands.map { BashCommandInvocation(it.words, it.operatorBefore) },
        hasDynamicWords = hasDynamic,
        hasParseErrors = parseErrors,
        hasRedirects = hasRedirects,
        hasUnsupportedSyntax = types.isNotEmpty(),
        unsupportedNodeTypes = types,
    )
}

private sealed class Node {
    abstract val pos: Int
    abstract val end: Int
}

private class CmdNode(
    val words: List<CommandWord>,
    val redirects: Int,
    override val pos: Int,
    override val end: Int,
) : Node()

private class AndOrNode(
    val commands: List<Node>,
    val operators: List<String>,
    override val pos: Int,
    override val end: Int,
) : Node()

private class PipeNode(
    val commands: List<Node>,
    val operators: List<String>,
    val time: Boolean,
    override val pos: Int,
    override val end: Int,
) : Node()

private class StmtNode(
    val command: Node,
    var background: Boolean,
    override val pos: Int,
    var stmtEnd: Int,
) : Node() {
    override val end: Int get() = stmtEnd
}

private class OtherNode(
    val typeName: String,
    override val pos: Int,
    override val end: Int,
) : Node()

private enum class Kind { EOF, WORD, SEMI, NL, AMP, AND, OR, PIPE, REDIR, LP, RP, DSEMI, SEMI_AMP, DSEMI_AMP }

private class Tok(val kind: Kind, val start: Int, val end: Int, val raw: String = "")

private class BashLexer(private val source: String) {
    private var pos = 0
    private var peeked: Tok? = null
    private val pendingHere = mutableListOf<Pair<String, Boolean>>()
    var failed: Boolean = false
    private val scanErrors = ScanErrors()

    fun peek(): Tok {
        if (peeked == null) peeked = read()
        return peeked!!
    }

    fun next(): Tok {
        val tok = peek()
        peeked = null
        return tok
    }

    private fun read(): Tok {
        while (true) {
            skipGap()
            if (pos >= source.length) return Tok(Kind.EOF, pos, pos)
            val start = pos
            val c = source[pos]
            if (c == '#') {
                while (pos < source.length && source[pos] != '\n') pos++
                continue
            }
            if (c == '\n' || c == '\r') {
                pos++
                if (c == '\r' && pos < source.length && source[pos] == '\n') pos++
                consumeHereDocs()
                return Tok(Kind.NL, start, pos, "\n")
            }
            tryOperator(start)?.let { return it }
            return readWord(start)
        }
    }

    private fun skipGap() {
        while (pos < source.length) {
            val c = source[pos]
            if (c == ' ' || c == '\t') {
                pos++
                continue
            }
            if (c == '\\' && pos + 1 < source.length && source[pos + 1] == '\n') {
                pos += 2
                continue
            }
            break
        }
    }

    private fun tryOperator(start: Int): Tok? {
        val c = source[pos]
        val next = source.getOrNull(pos + 1)
        if ((c == '<' || c == '>') && next == '(') {
            pos += 2
            val errors = ScanErrors()
            pos = skipBalancedPublic(source, pos - 1, errors)
            if (errors.unterminated) failed = true
            return Tok(Kind.WORD, start, pos, source.substring(start, pos.coerceAtMost(source.length)))
        }
        return when (c) {
            '|' -> {
                pos++
                if (next == '|') {
                    pos++
                    Tok(Kind.OR, start, pos, "||")
                } else if (next == '&') {
                    pos++
                    Tok(Kind.PIPE, start, pos, "|&")
                } else {
                    Tok(Kind.PIPE, start, pos, "|")
                }
            }
            '&' -> when (next) {
                '&' -> {
                    pos += 2
                    Tok(Kind.AND, start, pos, "&&")
                }
                '>' -> readRedirect(start, pos)
                else -> {
                    pos++
                    Tok(Kind.AMP, start, pos, "&")
                }
            }
            ';' -> {
                pos++
                if (next == ';') {
                    pos++
                    if (source.getOrNull(pos) == '&') {
                        pos++
                        Tok(Kind.DSEMI_AMP, start, pos, ";;&")
                    } else {
                        Tok(Kind.DSEMI, start, pos, ";;")
                    }
                } else if (next == '&') {
                    pos++
                    Tok(Kind.SEMI_AMP, start, pos, ";&")
                } else {
                    Tok(Kind.SEMI, start, pos, ";")
                }
            }
            '(' -> {
                pos++
                Tok(Kind.LP, start, pos, "(")
            }
            ')' -> {
                pos++
                Tok(Kind.RP, start, pos, ")")
            }
            '<' , '>' -> readRedirect(start, pos)
            else -> null
        }
    }

    private fun readRedirect(tokenStart: Int, opAt: Int): Tok {
        pos = opAt
        val c = source[pos]
        var stripTabs = false
        var heredoc = false
        if (c == '<') {
            pos++
            when (source.getOrNull(pos)) {
                '<' -> {
                    pos++
                    heredoc = true
                    if (source.getOrNull(pos) == '<') {
                        pos++
                        heredoc = false
                    } else if (source.getOrNull(pos) == '-') {
                        pos++
                        stripTabs = true
                    }
                }
                '>' -> pos++
                '&' -> pos++
            }
        } else {
            pos++
            when (source.getOrNull(pos)) {
                '>' -> pos++
                '&' -> pos++
                '|' -> pos++
            }
        }
        if (c == '&') {
            pos = opAt + 1
            if (source.getOrNull(pos) == '>') {
                pos++
                if (source.getOrNull(pos) == '>') pos++
            }
        }
        skipInline()
        if (heredoc) {
            val delimStart = pos
            if (pos < source.length && source[pos] != '\n') {
                val end = scanWord(source, pos, scanErrors)
                if (scanErrors.unterminated) failed = true
                pos = end
            }
            val rawDelim = if (pos > delimStart) source.substring(delimStart, pos) else ""
            pendingHere.add(hereDelimiter(rawDelim) to stripTabs)
            return Tok(Kind.REDIR, tokenStart, pos, source.substring(tokenStart, pos.coerceAtMost(source.length)))
        }
        if (pos < source.length && source[pos] != '\n' && source[pos] != '\r') {
            val end = scanWord(source, pos, scanErrors)
            if (scanErrors.unterminated) failed = true
            pos = end
        }
        return Tok(Kind.REDIR, tokenStart, pos, source.substring(tokenStart, pos.coerceAtMost(source.length)))
    }

    private fun hereDelimiter(raw: String): String {
        if (raw.length >= 2 && ((raw.first() == '\'' && raw.last() == '\'') || (raw.first() == '"' && raw.last() == '"'))) {
            return raw.substring(1, raw.length - 1)
        }
        return com.andmx.agent.permission.guarded.literalPosixWord(raw, 0, raw.length).value
    }

    private fun skipInline() {
        while (pos < source.length && (source[pos] == ' ' || source[pos] == '\t')) pos++
    }

    private fun consumeHereDocs() {
        for ((delim, strip) in pendingHere) {
            val body = readHereBody(delim, strip)
            if (body == null) failed = true
        }
        pendingHere.clear()
    }

    private fun readHereBody(delim: String, strip: Boolean): String? {
        val start = pos
        while (pos < source.length) {
            var lineStart = pos
            var lineEnd = source.indexOf('\n', pos).let { if (it < 0) source.length else it }
            var compare = lineStart
            if (strip) {
                while (compare < lineEnd && source[compare] == '\t') compare++
            }
            val line = source.substring(compare, lineEnd)
            pos = if (lineEnd < source.length) lineEnd + 1 else lineEnd
            if (line == delim) return source.substring(start, lineStart)
        }
        return null
    }

    private fun readWord(start: Int): Tok {
        if (pos < source.length && source[pos].isDigit()) {
            var j = pos
            while (j < source.length && source[j].isDigit()) j++
            if (j < source.length && (source[j] == '<' || source[j] == '>')) {
                pos = j
                return readRedirect(start, j)
            }
        }
        val end = scanWord(source, pos, scanErrors)
        if (scanErrors.unterminated) failed = true
        pos = end
        return Tok(Kind.WORD, start, end, source.substring(start, end.coerceAtMost(source.length)))
    }
}

private fun skipBalancedPublic(source: String, openParen: Int, errors: ScanErrors): Int {
    var depth = 1
    var j = openParen + 1
    while (j < source.length && depth > 0) {
        when (val c = source[j]) {
            '\\' -> j += if (j + 1 < source.length) 2 else 1
            '\'' -> {
                j++
                while (j < source.length && source[j] != '\'') j++
                j = if (j < source.length) j + 1 else j
            }
            '"' -> {
                j++
                while (j < source.length && source[j] != '"') {
                    if (source[j] == '\\') j += 2 else j++
                }
                j = if (j < source.length) j + 1 else j
            }
            '(' -> {
                depth++
                j++
            }
            ')' -> {
                depth--
                j++
            }
            else -> j++
        }
    }
    if (depth != 0) errors.unterminated = true
    return j
}

private class BashParser(private val source: String) {
    val lexer = BashLexer(source)
    var failed: Boolean = false

    fun finishTopLevel() {
        skipNl()
        if (lexer.peek().kind != Kind.EOF) failed = true
    }

    fun parseList(): List<StmtNode> {
        val commands = mutableListOf<StmtNode>()
        skipNl()
        if (stops() || !canStart()) return commands
        val first = parseAndOr()
        if (first != null) commands.add(StmtNode(first, false, first.pos, first.end))
        while (true) {
            val kind = lexer.peek().kind
            if (kind != Kind.SEMI && kind != Kind.NL && kind != Kind.AMP) break
            val background = kind == Kind.AMP
            val sepEnd = lexer.next().end
            if (background && commands.isNotEmpty()) {
                val stmt = commands.last()
                stmt.background = true
                stmt.stmtEnd = sepEnd
            }
            skipNl()
            if (stops() || !canStart()) break
            val node = parseAndOr() ?: break
            commands.add(StmtNode(node, false, node.pos, node.end))
        }
        return commands
    }

    private fun parseAndOr(): Node? {
        val first = parsePipeline() ?: return null
        val kind = lexer.peek().kind
        if (kind != Kind.AND && kind != Kind.OR) return first
        val commands = mutableListOf(first)
        val operators = mutableListOf<String>()
        var look = lexer.peek().kind
        while (look == Kind.AND || look == Kind.OR) {
            operators.add(if (lexer.next().kind == Kind.AND) "&&" else "||")
            skipNl()
            val next = parsePipeline() ?: break
            commands.add(next)
            look = lexer.peek().kind
        }
        return AndOrNode(commands, operators, first.pos, commands.last().end)
    }

    private fun parsePipeline(): Node? {
        var time = false
        var timePos = 0
        if (isExact("time")) {
            time = true
            timePos = lexer.peek().start
            lexer.next()
            if (isExact("-p")) lexer.next()
        }
        if (isExact("!")) {
            if (!time) timePos = lexer.peek().start
            lexer.next()
        }
        val first = parseCommand()
        if (first == null) {
            return if (time) PipeNode(emptyList(), emptyList(), true, timePos, timePos) else null
        }
        val commands = mutableListOf(first)
        val operators = mutableListOf<String>()
        while (lexer.peek().kind == Kind.PIPE) {
            operators.add(if (lexer.next().raw == "|&") "|&" else "|")
            skipNl()
            val cmd = parseCommand() ?: break
            commands.add(cmd)
        }
        if (!time && operators.isEmpty()) return first
        val pos = if (time) timePos else first.pos
        return PipeNode(commands, operators, time, pos, commands.last().end)
    }

    private fun parseCommand(): Node? {
        if (lexer.peek().kind == Kind.EOF || stops()) return null
        if (lexer.peek().kind == Kind.LP) {
            return if (source.getOrNull(lexer.peek().start + 1) == '(') parseArith() else parseSubshell()
        }
        if (isExact("{")) return parseBrace()
        if (isExact("if")) return parseIf()
        if (isExact("for")) return parseFor()
        if (isExact("while")) return parseWhile()
        if (isExact("until")) return parseWhile()
        if (isExact("case")) return parseCase()
        if (isExact("select")) return parseFor()
        if (isExact("function")) return parseFunctionKeyword()
        if (isExact("[[")) return parseDoubleTest()
        if (isExact("coproc")) return parseCoproc()
        val peeked = lexer.peek()
        if (peeked.kind == Kind.WORD || peeked.kind == Kind.REDIR || isAssignment(peeked)) return parseSimple()
        return null
    }

    private fun parseSimple(): Node {
        val start = lexer.peek().start
        var end = start
        var redirects = 0
        while (isAssignment(lexer.peek())) end = lexer.next().end
        while (lexer.peek().kind == Kind.REDIR) {
            end = lexer.next().end
            redirects++
        }
        if (lexer.peek().kind != Kind.WORD) {
            return CmdNode(emptyList(), redirects, start, end)
        }
        val words = mutableListOf<CommandWord>()
        val name = lexer.next()
        words.add(posixCommandWord(name.raw, name.start, name.end))
        end = name.end
        while (true) {
            when (lexer.peek().kind) {
                Kind.WORD -> {
                    val tok = lexer.next()
                    words.add(posixCommandWord(tok.raw, tok.start, tok.end))
                    end = tok.end
                }
                Kind.REDIR -> {
                    end = lexer.next().end
                    redirects++
                }
                else -> break
            }
        }
        return CmdNode(words, redirects, start, end)
    }

    private fun parseFor(): Node {
        val startTok = lexer.next()
        if (lexer.peek().kind == Kind.LP) return parseArith(startTok.start)
        if (lexer.peek().kind == Kind.WORD) lexer.next()
        skipNl()
        if (isExact("in")) {
            lexer.next()
            while (lexer.peek().kind == Kind.WORD) lexer.next()
        }
        if (lexer.peek().kind == Kind.SEMI) lexer.next()
        skipNl()
        if (isExact("do")) lexer.next() else failed = true
        parseList()
        if (isExact("done")) lexer.next() else failed = true
        val end = consumeTrailingRedirects(startTok.end)
        return OtherNode("For", startTok.start, end)
    }

    private fun parseWhile(): Node {
        val startTok = lexer.next()
        parseList()
        if (isExact("do")) lexer.next() else failed = true
        parseList()
        if (isExact("done")) lexer.next() else failed = true
        val end = consumeTrailingRedirects(startTok.end)
        return OtherNode("While", startTok.start, end)
    }

    private fun parseIf(): Node {
        val startTok = lexer.next()
        parseList()
        if (isExact("then")) lexer.next() else failed = true
        parseList()
        while (isExact("elif")) {
            lexer.next()
            parseList()
            if (isExact("then")) lexer.next() else failed = true
            parseList()
        }
        if (isExact("else")) {
            lexer.next()
            parseList()
        }
        if (isExact("fi")) lexer.next() else failed = true
        val end = consumeTrailingRedirects(startTok.end)
        return OtherNode("If", startTok.start, end)
    }

    private fun parseCase(): Node {
        val startTok = lexer.next()
        if (lexer.peek().kind == Kind.WORD) lexer.next()
        skipNl()
        if (isExact("in")) lexer.next() else failed = true
        skipNl()
        while (!isExact("esac") && lexer.peek().kind != Kind.EOF) {
            if (lexer.peek().kind == Kind.LP) lexer.next()
            while (lexer.peek().kind != Kind.RP && lexer.peek().kind != Kind.EOF && !isExact("esac")) lexer.next()
            if (lexer.peek().kind == Kind.RP) lexer.next()
            parseList()
            val kind = lexer.peek().kind
            if (kind == Kind.DSEMI || kind == Kind.SEMI_AMP || kind == Kind.DSEMI_AMP) lexer.next()
            skipNl()
        }
        if (isExact("esac")) lexer.next() else failed = true
        val end = consumeTrailingRedirects(startTok.end)
        return OtherNode("Case", startTok.start, end)
    }

    private fun parseBrace(): Node {
        val startTok = lexer.next()
        parseList()
        if (isExact("}")) lexer.next() else failed = true
        val end = consumeTrailingRedirects(startTok.end)
        return OtherNode("BraceGroup", startTok.start, end)
    }

    private fun parseSubshell(): Node {
        val startTok = lexer.next()
        parseList()
        if (lexer.peek().kind == Kind.RP) lexer.next() else failed = true
        val end = consumeTrailingRedirects(startTok.end)
        return OtherNode("Subshell", startTok.start, end)
    }

    private fun parseArith(fallbackStart: Int = lexer.peek().start): Node {
        val start = if (lexer.peek().kind == Kind.LP) lexer.peek().start else fallbackStart
        var depth = 0
        var end = start
        while (lexer.peek().kind != Kind.EOF && depth >= 0) {
            val tok = lexer.next()
            end = tok.end
            if (tok.kind == Kind.LP) depth++
            if (tok.kind == Kind.RP) {
                depth--
                if (depth <= 0) break
            }
            if (depth == 0 && tok.kind != Kind.LP && tok.kind != Kind.RP) break
        }
        if (depth > 0) failed = true
        return OtherNode("ArithmeticCommand", start, end)
    }

    private fun parseDoubleTest(): Node {
        val startTok = lexer.next()
        while (lexer.peek().kind != Kind.EOF && !isExact("]]")) lexer.next()
        val end = if (isExact("]]")) lexer.next().end else {
            failed = true
            startTok.end
        }
        return OtherNode("TestCommand", startTok.start, end)
    }

    private fun parseFunctionKeyword(): Node {
        val startTok = lexer.next()
        if (lexer.peek().kind == Kind.WORD) lexer.next()
        if (lexer.peek().kind == Kind.LP) {
            lexer.next()
            if (lexer.peek().kind == Kind.RP) lexer.next() else failed = true
        }
        skipNl()
        parseCommand()
        val end = consumeTrailingRedirects(startTok.end)
        return OtherNode("Function", startTok.start, end)
    }

    private fun parseCoproc(): Node {
        val startTok = lexer.next()
        parseCommand()
        val end = consumeTrailingRedirects(startTok.end)
        return OtherNode("Coproc", startTok.start, end)
    }

    private fun consumeTrailingRedirects(fallback: Int): Int {
        var end = fallback
        while (lexer.peek().kind == Kind.REDIR) end = lexer.next().end
        return end
    }

    private fun skipNl() {
        while (lexer.peek().kind == Kind.NL) lexer.next()
    }

    private fun stops(): Boolean {
        val tok = lexer.peek()
        if (tok.kind == Kind.EOF || tok.kind == Kind.RP || tok.kind == Kind.DSEMI || tok.kind == Kind.SEMI_AMP || tok.kind == Kind.DSEMI_AMP) {
            return true
        }
        if (tok.kind != Kind.WORD) return false
        return tok.raw == "}" || tok.raw == "then" || tok.raw == "else" || tok.raw == "elif" ||
            tok.raw == "fi" || tok.raw == "do" || tok.raw == "done" || tok.raw == "esac"
    }

    private fun canStart(): Boolean {
        val tok = lexer.peek()
        if (tok.kind == Kind.EOF || stops()) return false
        return tok.kind == Kind.WORD || tok.kind == Kind.REDIR || tok.kind == Kind.LP
    }

    private fun isExact(text: String): Boolean {
        val tok = lexer.peek()
        return tok.kind == Kind.WORD && tok.raw == text
    }

    private fun isAssignment(tok: Tok): Boolean {
        if (tok.kind != Kind.WORD) return false
        val text = tok.raw
        val eq = text.indexOf('=')
        if (eq <= 0) return false
        var nameEnd = eq
        if (text[eq - 1] == '+') nameEnd--
        val bracket = text.indexOf('[')
        if (bracket in 1 until nameEnd) {
            val rb = text.indexOf(']', bracket)
            if (rb > bracket && rb + 1 == nameEnd) nameEnd = bracket
        }
        if (nameEnd <= 0) return false
        val name = text.substring(0, nameEnd)
        if (name.isEmpty() || !(name[0] == '_' || name[0].isLetter())) return false
        return name.all { it == '_' || it.isLetterOrDigit() }
    }
}
