package com.andmx.agent.workflow

/**
 * ZCode frontmatter 用到的 YAML 子集解析器（对齐上游 `yaml` 库在 `saved-workflows/frontmatter.ts`
 * 里的实际用法）。
 *
 * 为什么不是完整 YAML：这里只需要解析由 `SavedWorkflowMetaSchema` 约束的那一小块形状，
 * 而完整 YAML 的隐式类型、锚点、多文档、合并键在 Kotlin 侧要几百行且没有对应测试面。
 * 子集的取舍是**宁可报错也不猜**——超出子集的写法会抛 [YamlSubsetException] 并带上行号，
 * 上层把它翻成 `invalid_yaml`，用户拿到的是「第几行写错了」而不是一个被静默吞掉的字段。
 *
 * 支持的形状：
 * - 块状映射，`key: value` 与 `key:` + 更深缩进的子块
 * - 块状序列，`- value`（`default` 里可能出现）
 * - 标量：裸标量、单引号、双引号
 * - 块标量：`|` `|-` `|+` `>` `>-` `>+`（多行 description 会用到）
 * - 流式集合：`{a: 1, b: 2}` 与 `[1, 2]`
 *
 * 不支持的形状（会报错，不会静默误解析）：锚点/别名（`&` `*`）、标签（`!!`）、
 * 多文档（`---`）、合并键（`<<`）、流式块里跨行的集合。
 */
internal object YamlSubset {

    class YamlSubsetException(message: String, val line: Int) : Exception(message)

    /** 解析结果为映射树：Map / List / String / Boolean / Long / Double / null。 */
    fun parse(text: String): Any? {
        val lines = RawLine.of(text)
        val structure = lines.filter { it.isStructural }
        if (structure.isEmpty()) return null
        val parser = BlockParser(lines)
        val value = parser.parse()
        return value
    }

    private class RawLine(val indent: Int, val content: String, val number: Int, val isStructural: Boolean) {
        companion object {
            fun of(text: String): List<RawLine> = text.split('\n').mapIndexed { index, raw ->
                val stripped = raw.trimEnd('\r')
                val content = stripped.trimStart(' ', '\t')
                val indent = stripped.length - content.length
                val structural = content.isNotEmpty() && !content.startsWith("#")
                RawLine(indent, content, index + 1, structural)
            }
        }
    }

    private class BlockParser(private val lines: List<RawLine>) {
        private var index = 0

        fun parse(): Any? {
            skipNonStructural()
            if (index >= lines.size) return null
            val first = lines[index]
            return parseBlock(first.indent)
        }

        private fun skipNonStructural() {
            while (index < lines.size && !lines[index].isStructural) index += 1
        }

        private fun parseBlock(indent: Int): Any? {
            if (lines[index].content.startsWith("- ") || lines[index].content == "-") {
                return parseSequence(indent)
            }
            return parseMapping(indent)
        }

        private fun parseMapping(indent: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                skipNonStructural()
                if (index >= lines.size) break
                val line = lines[index]
                if (line.indent < indent) break
                if (line.indent > indent) {
                    throw YamlSubsetException("unexpected indentation", line.number)
                }
                if (line.content.startsWith("- ")) break
                val colon = keyColon(line.content)
                    ?: throw YamlSubsetException("expected `key: value`", line.number)
                val key = unquoteKey(line.content.substring(0, colon).trim(), line.number)
                if (map.containsKey(key)) {
                    throw YamlSubsetException("duplicate key `$key`", line.number)
                }
                val rest = line.content.substring(colon + 1).trim()
                index += 1
                map[key] = if (rest.isEmpty()) {
                    parseNested(indent, line.number)
                } else {
                    parseInlineValue(rest, indent, line.number)
                }
            }
            return map
        }

        private fun parseSequence(indent: Int): List<Any?> {
            val items = mutableListOf<Any?>()
            while (true) {
                skipNonStructural()
                if (index >= lines.size) break
                val line = lines[index]
                if (line.indent != indent || !line.content.startsWith("-")) break
                val rest = line.content.removePrefix("-").trim()
                index += 1
                items += if (rest.isEmpty()) parseNested(indent, line.number) else parseInlineValue(rest, indent, line.number)
            }
            return items
        }

        /** `key:` 之后：更深缩进就是子块，否则是 null。 */
        private fun parseNested(parentIndent: Int, lineNumber: Int): Any? {
            skipNonStructural()
            if (index >= lines.size) return null
            val next = lines[index]
            if (next.indent > parentIndent) return parseBlock(next.indent)
            return null
        }

        private fun parseInlineValue(rest: String, indent: Int, lineNumber: Int): Any? = when {
            rest == "|" || rest == "|-" || rest == "|+" -> parseBlockScalar(indent, lineNumber, literal = true, style = rest)
            rest == ">" || rest == ">-" || rest == ">+" -> parseBlockScalar(indent, lineNumber, literal = false, style = rest)
            rest.startsWith("\"") -> quoted(rest, lineNumber, single = false)
            rest.startsWith("'") -> quoted(rest, lineNumber, single = true)
            rest.startsWith("[") || rest.startsWith("{") -> FlowParser(rest, lineNumber).parseValue()
            else -> scalar(rest)
        }

        private fun parseBlockScalar(parentIndent: Int, lineNumber: Int, literal: Boolean, style: String): String {
            val collected = mutableListOf<RawLine>()
            while (index < lines.size) {
                val line = lines[index]
                val blank = line.content.isEmpty()
                if (!blank && line.indent <= parentIndent) break
                collected += line
                index += 1
            }
            val contentIndent = collected
                .filter { it.content.isNotEmpty() }
                .minOfOrNull { it.indent }
                ?: parentIndent + 2
            val body = collected.map { line ->
                when {
                    line.content.isEmpty() -> ""
                    line.indent >= contentIndent -> " ".repeat(line.indent - contentIndent) + line.content
                    else -> line.content
                }
            }
            val joined = if (literal) body.joinToString("\n") else fold(body)
            val trailingBlank = body.takeLastWhile { it.isEmpty() }.size
            val core = joined.trimEnd('\n')
            return when {
                style.endsWith("-") -> core
                style.endsWith("+") -> core + "\n".repeat(trailingBlank + 1)
                else -> core + "\n"
            }.also {
                if (it.isEmpty() && style.endsWith("-")) {
                    throw YamlSubsetException("empty block scalar", lineNumber)
                }
            }
        }

        private fun fold(body: List<String>): String {
            val builder = StringBuilder()
            var previousBlank = true
            body.forEach { line ->
                if (line.isEmpty()) {
                    builder.append('\n')
                    previousBlank = true
                } else {
                    if (builder.isNotEmpty() && !previousBlank) builder.append(' ')
                    builder.append(line)
                    previousBlank = false
                }
            }
            return builder.toString()
        }

        private fun quoted(text: String, lineNumber: Int, single: Boolean): String {
            val quote = if (single) '\'' else '"'
            if (text.length < 2 || !text.endsWith(quote)) {
                throw YamlSubsetException("unterminated quoted scalar", lineNumber)
            }
            val body = text.substring(1, text.length - 1)
            return if (single) body.replace("''", "'") else unescapeDouble(body, lineNumber)
        }

        private fun unescapeDouble(body: String, lineNumber: Int): String {
            val builder = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\') {
                    builder.append(c)
                    i += 1
                    continue
                }
                i += 1
                if (i >= body.length) throw YamlSubsetException("dangling escape", lineNumber)
                when (val e = body[i]) {
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    'r' -> builder.append('\r')
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '0' -> builder.append('\u0000')
                    else -> throw YamlSubsetException("unsupported escape `\\$e`", lineNumber)
                }
                i += 1
            }
            return builder.toString()
        }
    }

    private fun scalar(raw: String): Any? {
        val text = raw.trim()
        return when {
            text.isEmpty() -> null
            text == "null" || text == "~" -> null
            text == "true" || text == "True" || text == "TRUE" -> true
            text == "false" || text == "False" || text == "FALSE" -> false
            isIntegral(text) -> text.toLong()
            isDecimal(text) -> text.toDouble()
            else -> text
        }
    }

    private fun isIntegral(text: String): Boolean =
        Regex("^[-+]?\\d+$").matches(text) && runCatching { text.toLong() }.isSuccess

    private fun isDecimal(text: String): Boolean =
        Regex("^[-+]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][-+]?\\d+)?$").matches(text) &&
            runCatching { text.toDouble() }.isSuccess

    /** 找到分隔键与值的冒号：引号与流式集合内的冒号不算。 */
    private fun keyColon(text: String): Int? {
        var i = 0
        var quote: Char? = null
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> {
                    if (c == quote) {
                        if (quote == '\'' && i + 1 < text.length && text[i + 1] == '\'') i += 1 else quote = null
                    } else if (quote == '"' && c == '\\') {
                        i += 1
                    }
                }
                c == '"' || c == '\'' -> quote = c
                c == '{' || c == '[' -> depth += 1
                c == '}' || c == ']' -> depth -= 1
                c == ':' && depth == 0 -> {
                    val next = text.getOrNull(i + 1)
                    if (next == null || next == ' ' || next == '\t') return i
                }
            }
            i += 1
        }
        return null
    }

    private fun unquoteKey(raw: String, lineNumber: Int): String = when {
        raw.isEmpty() -> throw YamlSubsetException("empty key", lineNumber)
        raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 -> raw.substring(1, raw.length - 1)
        raw.startsWith("'") && raw.endsWith("'") && raw.length >= 2 -> raw.substring(1, raw.length - 1).replace("''", "'")
        else -> raw
    }

    /** 流式集合：`{a: 1}`、`[1, 2]`，以及其中允许出现的嵌套与引号。 */
    private class FlowParser(private val text: String, private val lineNumber: Int) {
        private var i = 0

        fun parseValue(): Any? {
            skipSpace()
            if (i >= text.length) throw YamlSubsetException("empty flow value", lineNumber)
            return when (text[i]) {
                '{' -> parseMap()
                '[' -> parseSequence()
                '"' -> parseQuoted('"')
                '\'' -> parseQuoted('\'')
                else -> scalar(readBare())
            }
        }

        private fun parseMap(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                i += 1
                trailing()
                return map
            }
            while (true) {
                skipSpace()
                val key = if (peek() == '"' || peek() == '\'') parseQuoted(text[i]) else readBare(stopAtColon = true)
                    ?: throw YamlSubsetException("expected flow key", lineNumber)
                skipSpace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipSpace()
                when (peek()) {
                    ',' -> {
                        i += 1
                        continue
                    }
                    '}' -> {
                        i += 1
                        trailing()
                        return map
                    }
                    else -> throw YamlSubsetException("expected `,` or `}` in flow mapping", lineNumber)
                }
            }
        }

        private fun parseSequence(): List<Any?> {
            expect('[')
            val items = mutableListOf<Any?>()
            skipSpace()
            if (peek() == ']') {
                i += 1
                trailing()
                return items
            }
            while (true) {
                items += parseValue()
                skipSpace()
                when (peek()) {
                    ',' -> {
                        i += 1
                        continue
                    }
                    ']' -> {
                        i += 1
                        trailing()
                        return items
                    }
                    else -> throw YamlSubsetException("expected `,` or `]` in flow sequence", lineNumber)
                }
            }
        }

        private fun trailing() {
            if (text.substring(i).isNotBlank()) {
                throw YamlSubsetException("trailing content after flow collection", lineNumber)
            }
        }

        private fun parseQuoted(quote: Char): String {
            expect(quote)
            val builder = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                if (c == quote) {
                    if (quote == '\'' && i + 1 < text.length && text[i + 1] == '\'') {
                        builder.append('\'')
                        i += 2
                        continue
                    }
                    i += 1
                    return builder.toString()
                }
                if (quote == '"' && c == '\\') {
                    i += 1
                    if (i >= text.length) throw YamlSubsetException("dangling escape", lineNumber)
                    when (val e = text[i]) {
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        else -> throw YamlSubsetException("unsupported escape `\\$e`", lineNumber)
                    }
                    i += 1
                    continue
                }
                builder.append(c)
                i += 1
            }
            throw YamlSubsetException("unterminated quoted scalar", lineNumber)
        }

        private fun readBare(stopAtColon: Boolean = false): String {
            skipSpace()
            val start = i
            while (i < text.length) {
                val c = text[i]
                if (c == ',' || c == '}' || c == ']') break
                if (stopAtColon && c == ':') break
                i += 1
            }
            return text.substring(start, i).trim()
        }

        private fun skipSpace() {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i += 1
        }

        private fun peek(): Char? = text.getOrNull(i)

        private fun expect(c: Char) {
            skipSpace()
            if (peek() != c) throw YamlSubsetException("expected `$c`", lineNumber)
            i += 1
        }
    }
}
