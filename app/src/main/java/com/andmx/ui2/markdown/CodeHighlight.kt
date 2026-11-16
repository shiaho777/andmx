package com.andmx.ui2.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

enum class CodeTokenType { KEYWORD, TYPE, STRING, NUMBER, COMMENT, ANNOTATION, FUNCTION, PLAIN }

data class CodeToken(val type: CodeTokenType, val start: Int, val end: Int)

private class StringRule(
    val delim: String,
    val multiline: Boolean,
    val escapes: Boolean,
    val doubled: Boolean = false,
)

private class LangSpec(
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val ciKeywords: Boolean = false,
    val lineComments: List<String> = emptyList(),
    val hashCommentBoundary: Boolean = false,
    val blockStart: String? = null,
    val blockEnd: String? = null,
    val nestedBlocks: Boolean = false,
    val strings: List<StringRule> = emptyList(),
    val charLiterals: Boolean = false,
    val stringPrefixes: Boolean = false,
    val rustRaw: Boolean = false,
    val cppRaw: Boolean = false,
    val atAnnotations: Boolean = false,
    val rustAttributes: Boolean = false,
    val preprocessor: Boolean = false,
    val variables: Boolean = false,
    val markup: Boolean = false,
    val jsonKeys: Boolean = false,
    val colonKeys: Boolean = false,
    val cssProps: Boolean = false,
    val yamlAnchors: Boolean = false,
    val signedNumbers: Boolean = false,
    val hexNumbers: Boolean = true,
    val digitSep: Char? = null,
    val suffixLetters: String = "",
    val suffixDigits: Boolean = false,
    val macroBang: Boolean = false,
    val identDollar: Boolean = false,
    val identDash: Boolean = false,
    val cssHexColor: Boolean = false,
)

object CodeHighlight {
    private const val MAX_SCAN = 200_000

    private val kotlinSpec = LangSpec(
        keywords = setOf(
            "package", "import", "fun", "val", "var", "vararg", "class", "object", "interface",
            "constructor", "init", "this", "super", "if", "else", "when", "for", "while", "do",
            "return", "break", "continue", "throw", "try", "catch", "finally", "is", "in", "as",
            "typealias", "by", "companion", "data", "sealed", "enum", "inner", "annotation",
            "suspend", "inline", "noinline", "crossinline", "reified", "open", "override",
            "abstract", "final", "private", "protected", "public", "internal", "const",
            "lateinit", "operator", "infix", "tailrec", "external", "where", "get", "set",
            "true", "false", "null",
        ),
        types = setOf(
            "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String",
            "Unit", "Nothing", "Any", "Array", "List", "Map", "Set", "MutableList", "MutableMap",
            "MutableSet", "Collection", "Iterable", "Sequence", "Pair", "Triple", "Comparable",
            "Number", "CharSequence", "Throwable", "Exception", "RuntimeException",
        ),
        lineComments = listOf("//"),
        blockStart = "/*", blockEnd = "*/", nestedBlocks = true,
        strings = listOf(
            StringRule("\"\"\"", multiline = true, escapes = false),
            StringRule("\"", multiline = false, escapes = true),
        ),
        charLiterals = true,
        atAnnotations = true,
        digitSep = '_',
        suffixLetters = "fFL",
    )

    private val javaSpec = LangSpec(
        keywords = setOf(
            "package", "import", "class", "interface", "enum", "record", "extends", "implements",
            "public", "private", "protected", "static", "final", "abstract", "synchronized",
            "volatile", "transient", "native", "strictfp", "default", "new", "this", "super",
            "instanceof", "if", "else", "switch", "case", "for", "while", "do", "break",
            "continue", "return", "throw", "throws", "try", "catch", "finally", "assert",
            "var", "yield", "sealed", "permits", "true", "false", "null",
        ),
        types = setOf(
            "void", "int", "long", "short", "byte", "float", "double", "boolean", "char",
            "String", "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean",
            "Character", "Object", "Class", "System", "List", "Map", "Set", "ArrayList",
            "HashMap", "HashSet", "Collection", "Optional", "Throwable", "Exception",
            "RuntimeException", "Error",
        ),
        lineComments = listOf("//"),
        blockStart = "/*", blockEnd = "*/",
        strings = listOf(
            StringRule("\"\"\"", multiline = true, escapes = true),
            StringRule("\"", multiline = false, escapes = true),
        ),
        charLiterals = true,
        atAnnotations = true,
        digitSep = '_',
        suffixLetters = "fFdDlL",
    )

    private val pythonSpec = LangSpec(
        keywords = setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "break",
            "continue", "pass", "import", "from", "as", "with", "try", "except", "finally",
            "raise", "lambda", "global", "nonlocal", "yield", "del", "assert", "in", "is",
            "not", "and", "or", "async", "await", "match", "case", "True", "False", "None",
        ),
        types = setOf(
            "int", "float", "str", "bool", "bytes", "list", "dict", "set", "tuple",
            "frozenset", "object", "type", "complex", "range", "print", "len", "enumerate",
            "zip", "map", "filter", "sum", "min", "max", "abs", "sorted", "reversed", "open",
            "input", "isinstance", "issubclass", "super", "property", "staticmethod",
            "classmethod", "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
            "RuntimeError", "StopIteration",
        ),
        lineComments = listOf("#"),
        strings = listOf(
            StringRule("\"\"\"", multiline = true, escapes = true),
            StringRule("'''", multiline = true, escapes = true),
            StringRule("\"", multiline = false, escapes = true),
            StringRule("'", multiline = false, escapes = true),
        ),
        stringPrefixes = true,
        atAnnotations = true,
        digitSep = '_',
        suffixLetters = "jJ",
    )

    private val jsKeywords = setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
        "break", "continue", "switch", "case", "default", "new", "delete", "typeof",
        "instanceof", "in", "of", "this", "super", "class", "extends", "import", "export",
        "from", "as", "async", "await", "yield", "try", "catch", "finally", "throw",
        "debugger", "with", "void", "true", "false", "null", "undefined", "NaN", "Infinity",
    )

    private val jsTypes = setOf(
        "Array", "Object", "String", "Number", "Boolean", "Symbol", "BigInt", "Map", "Set",
        "WeakMap", "WeakSet", "Promise", "Date", "RegExp", "Error", "JSON", "Math", "console",
        "document", "window", "fetch", "setTimeout", "setInterval", "parseInt", "parseFloat",
        "require", "module", "exports",
    )

    private val jsStrings = listOf(
        StringRule("`", multiline = true, escapes = true),
        StringRule("\"", multiline = false, escapes = true),
        StringRule("'", multiline = false, escapes = true),
    )

    private val javascriptSpec = LangSpec(
        keywords = jsKeywords,
        types = jsTypes,
        lineComments = listOf("//"),
        blockStart = "/*", blockEnd = "*/",
        strings = jsStrings,
        atAnnotations = true,
        digitSep = '_',
        suffixLetters = "n",
        identDollar = true,
    )

    private val typescriptSpec = LangSpec(
        keywords = jsKeywords + setOf(
            "interface", "type", "enum", "namespace", "declare", "abstract", "implements",
            "readonly", "public", "private", "protected", "keyof", "infer", "is", "asserts",
            "override", "satisfies",
        ),
        types = jsTypes + setOf(
            "string", "number", "boolean", "any", "unknown", "never", "object", "symbol",
            "bigint", "Record", "Partial", "Readonly", "Pick", "Omit",
        ),
        lineComments = listOf("//"),
        blockStart = "/*", blockEnd = "*/",
        strings = jsStrings,
        atAnnotations = true,
        digitSep = '_',
        suffixLetters = "n",
        identDollar = true,
    )

    private val goSpec = LangSpec(
        keywords = setOf(
            "package", "import", "func", "var", "const", "type", "struct", "interface",
            "map", "chan", "go", "defer", "if", "else", "for", "range", "switch", "case",
            "default", "break", "continue", "return", "fallthrough", "goto", "select",
            "true", "false", "nil", "iota",
        ),
        types = setOf(
            "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32",
            "uint64", "uintptr", "float32", "float64", "complex64", "complex128", "byte",
            "rune", "string", "bool", "error", "any", "append", "cap", "close", "copy",
            "delete", "len", "make", "new", "panic", "print", "println", "recover", "min",
            "max", "clear",
        ),
        lineComments = listOf("//"),
        blockStart = "/*", blockEnd = "*/",
        strings = listOf(
            StringRule("`", multiline = true, escapes = false),
            StringRule("\"", multiline = false, escapes = true),
        ),
        charLiterals = true,
        digitSep = '_',
        suffixLetters = "i",
    )

    private val rustSpec = LangSpec(
        keywords = setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else",
            "enum", "extern", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
            "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct",
            "super", "trait", "type", "unsafe", "use", "where", "while", "yield",
            "macro_rules", "true", "false",
        ),
        types = setOf(
            "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128",
            "usize", "f32", "f64", "bool", "char", "str", "String", "Vec", "Box", "Rc",
            "Arc", "Option", "Result", "HashMap", "HashSet", "BTreeMap", "BTreeSet", "Cell",
            "RefCell", "Mutex", "Cow", "Some", "None", "Ok", "Err",
        ),
        lineComments = listOf("//"),
        blockStart = "/*", blockEnd = "*/", nestedBlocks = true,
        strings = listOf(StringRule("\"", multiline = true, escapes = true)),
        charLiterals = true,
        rustRaw = true,
        rustAttributes = true,
        digitSep = '_',
        suffixLetters = "uif",
        suffixDigits = true,
        macroBang = true,
    )

    private val cKeywords = setOf(
        "auto", "break", "case", "const", "continue", "default", "do", "else", "enum",
        "extern", "for", "goto", "if", "inline", "register", "restrict", "return", "sizeof",
        "static", "struct", "switch", "typedef", "union", "volatile", "while", "_Alignas",
        "_Alignof", "_Atomic", "_Generic", "_Noreturn", "_Static_assert", "_Thread_local",
        "NULL",
    )

    private val cTypes = setOf(
        "char", "double", "float", "int", "long", "short", "signed", "unsigned", "void",
        "size_t", "ssize_t", "int8_t", "int16_t", "int32_t", "int64_t", "uint8_t",
        "uint16_t", "uint32_t", "uint64_t", "intptr_t", "uintptr_t", "ptrdiff_t", "FILE",
        "bool",
    )

    private val cSpec = LangSpec(
        keywords = cKeywords,
        types = cTypes,
        lineComments = listOf("//"),
        blockStart = "/*", blockEnd = "*/",
        strings = listOf(StringRule("\"", multiline = false, escapes = true)),
        charLiterals = true,
        preprocessor = true,
        suffixLetters = "uUlLfF",
    )

    private val cppSpec = LangSpec(
        keywords = cKeywords + setOf(
            "class", "namespace", "template", "typename", "using", "public", "private",
            "protected", "virtual", "override", "final", "friend", "operator", "new",
            "delete", "this", "throw", "try", "catch", "noexcept", "constexpr", "consteval",
            "constinit", "decltype", "explicit", "mutable", "export", "concept", "requires",
            "co_await", "co_return", "co_yield", "static_cast", "dynamic_cast", "const_cast",
            "reinterpret_cast", "static_assert", "thread_local", "alignas", "alignof",
            "true", "false", "nullptr",
        ),
        types = cTypes + setOf(
            "string", "wstring", "vector", "map", "set", "unordered_map", "unordered_set",
            "array", "deque", "list", "queue", "stack", "pair", "tuple", "optional",
            "variant", "any", "shared_ptr", "unique_ptr", "weak_ptr", "cout", "cin", "endl",
        ),
        lineComments = listOf("//"),
        blockStart = "/*", blockEnd = "*/",
        strings = listOf(StringRule("\"", multiline = false, escapes = true)),
        charLiterals = true,
        cppRaw = true,
        preprocessor = true,
        digitSep = '\'',
        suffixLetters = "uUlLfF",
    )

    private val shellSpec = LangSpec(
        keywords = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
            "case", "esac", "in", "function", "select", "time", "coproc",
        ),
        types = setOf(
            "echo", "cd", "pwd", "exit", "export", "local", "readonly", "declare",
            "typeset", "unset", "shift", "source", "eval", "exec", "set", "trap", "return",
            "printf", "read", "test", "true", "false", "alias", "unalias", "umask", "wait",
            "kill", "jobs", "bg", "fg", "getopts", "let", "dirs", "pushd", "popd",
        ),
        lineComments = listOf("#"),
        hashCommentBoundary = true,
        strings = listOf(
            StringRule("\"", multiline = true, escapes = true),
            StringRule("'", multiline = true, escapes = false),
            StringRule("`", multiline = true, escapes = true),
        ),
        variables = true,
    )

    private val sqlSpec = LangSpec(
        keywords = setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set",
            "delete", "create", "drop", "alter", "table", "index", "view", "database",
            "schema", "join", "inner", "left", "right", "full", "outer", "cross", "on",
            "as", "and", "or", "not", "null", "is", "in", "exists", "between", "like",
            "ilike", "union", "all", "distinct", "order", "by", "group", "having", "limit",
            "offset", "fetch", "asc", "desc", "case", "when", "then", "else", "end",
            "primary", "key", "foreign", "references", "constraint", "unique", "check",
            "default", "cascade", "truncate", "grant", "revoke", "commit", "rollback",
            "transaction", "begin", "savepoint", "with", "recursive", "returning", "over",
            "partition", "rows", "range", "window", "explain", "analyze", "vacuum",
        ),
        types = setOf(
            "int", "integer", "smallint", "bigint", "tinyint", "decimal", "numeric",
            "float", "real", "double", "precision", "char", "varchar", "nchar",
            "nvarchar", "text", "blob", "clob", "date", "time", "timestamp", "datetime",
            "interval", "boolean", "bool", "serial", "bigserial", "uuid", "json", "jsonb",
            "bytea", "money",
        ),
        ciKeywords = true,
        lineComments = listOf("--"),
        blockStart = "/*", blockEnd = "*/",
        strings = listOf(
            StringRule("'", multiline = true, escapes = false, doubled = true),
            StringRule("\"", multiline = true, escapes = false, doubled = true),
            StringRule("`", multiline = true, escapes = false, doubled = true),
        ),
    )

    private val jsonSpec = LangSpec(
        keywords = setOf("true", "false", "null"),
        strings = listOf(StringRule("\"", multiline = false, escapes = true)),
        jsonKeys = true,
        signedNumbers = true,
        hexNumbers = false,
    )

    private val yamlSpec = LangSpec(
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
        ciKeywords = true,
        lineComments = listOf("#"),
        hashCommentBoundary = true,
        strings = listOf(
            StringRule("\"", multiline = false, escapes = true),
            StringRule("'", multiline = true, escapes = false, doubled = true),
        ),
        colonKeys = true,
        yamlAnchors = true,
        signedNumbers = true,
        identDash = true,
    )

    private val htmlSpec = LangSpec(
        blockStart = "<!--", blockEnd = "-->",
        markup = true,
    )

    private val cssSpec = LangSpec(
        keywords = setOf("important", "inherit", "initial", "unset", "revert", "auto", "none"),
        blockStart = "/*", blockEnd = "*/",
        strings = listOf(
            StringRule("\"", multiline = false, escapes = true),
            StringRule("'", multiline = false, escapes = true),
        ),
        atAnnotations = true,
        cssProps = true,
        cssHexColor = true,
        signedNumbers = true,
        suffixLetters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
    )

    private val genericSpec = LangSpec(
        lineComments = listOf("//", "#"),
        hashCommentBoundary = true,
        blockStart = "/*", blockEnd = "*/",
        strings = listOf(
            StringRule("`", multiline = true, escapes = true),
            StringRule("\"", multiline = false, escapes = true),
            StringRule("'", multiline = false, escapes = true),
        ),
    )

    private val specs = mapOf(
        "kotlin" to kotlinSpec,
        "java" to javaSpec,
        "python" to pythonSpec,
        "javascript" to javascriptSpec,
        "typescript" to typescriptSpec,
        "go" to goSpec,
        "rust" to rustSpec,
        "c" to cSpec,
        "cpp" to cppSpec,
        "shell" to shellSpec,
        "sql" to sqlSpec,
        "json" to jsonSpec,
        "yaml" to yamlSpec,
        "html" to htmlSpec,
        "css" to cssSpec,
    )

    private val aliases = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "py" to "python", "python3" to "python",
        "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript",
        "cjs" to "javascript", "node" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "golang" to "go",
        "rs" to "rust",
        "h" to "c",
        "cc" to "cpp", "cxx" to "cpp", "c++" to "cpp", "hpp" to "cpp", "hh" to "cpp",
        "hxx" to "cpp",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell", "console" to "shell",
        "shellsession" to "shell",
        "mysql" to "sql", "postgres" to "sql", "postgresql" to "sql", "sqlite" to "sql",
        "yml" to "yaml",
        "htm" to "html", "xml" to "html", "svg" to "html",
        "scss" to "css", "less" to "css",
    )

    private val detectKotlin = Regex("\\bfun\\s+\\w+|\\bval\\s+\\w+\\s*[:=]")
    private val detectPython = Regex("(^|\\n)\\s*def\\s+\\w+\\s*\\(")
    private val detectRust = Regex("\\bfn\\s+\\w+")
    private val detectGo = Regex("(^|\\n)package\\s+\\w+")
    private val detectSqlSelect = Regex("(?i)\\bselect\\b")
    private val detectSqlFrom = Regex("(?i)\\bfrom\\b")

    fun highlight(code: String, theme: CodeTheme): AnnotatedString = highlight(code, theme, "")

    fun highlight(code: String, theme: CodeTheme, language: String): AnnotatedString =
        buildAnnotatedString {
            append(code)
            for (token in tokenize(code, language)) {
                val color = when (token.type) {
                    CodeTokenType.KEYWORD -> theme.keyword
                    CodeTokenType.TYPE -> theme.function
                    CodeTokenType.STRING -> theme.string
                    CodeTokenType.NUMBER -> theme.number
                    CodeTokenType.COMMENT -> theme.comment
                    CodeTokenType.ANNOTATION -> theme.function
                    CodeTokenType.FUNCTION -> theme.function
                    CodeTokenType.PLAIN -> null
                }
                if (color != null) {
                    addStyle(SpanStyle(color = color), token.start, token.end)
                }
            }
        }

    fun tokenize(code: String, language: String = ""): List<CodeToken> {
        val spec = specFor(language, code)
        val limit = minOf(code.length, MAX_SCAN)
        return Lexer(code, limit, spec).run()
    }

    private fun specFor(language: String, code: String): LangSpec {
        var id = language.trim().lowercase().removePrefix(".")
        if ('.' in id) id = id.substringAfterLast('.')
        id = aliases[id] ?: id
        if (id.isEmpty()) id = detectLanguage(code)
        return specs[id] ?: genericSpec
    }

    private fun detectLanguage(code: String): String {
        val head = code.take(4096)
        val trimmed = head.trimStart()
        if (trimmed.startsWith("#!")) {
            val line = trimmed.lineSequence().first()
            return when {
                "python" in line -> "python"
                "node" in line -> "javascript"
                else -> "shell"
            }
        }
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && '"' in head) return "json"
        if (trimmed.startsWith("<")) return "html"
        if (detectKotlin.containsMatchIn(head)) return "kotlin"
        if (detectPython.containsMatchIn(head)) return "python"
        if ("#include" in head) return "cpp"
        if (detectRust.containsMatchIn(head) && ("let " in head || "impl " in head)) return "rust"
        if (detectGo.containsMatchIn(head) && "func " in head) return "go"
        if (detectSqlSelect.containsMatchIn(head) && detectSqlFrom.containsMatchIn(head)) return "sql"
        return ""
    }

    private class Lexer(
        val code: String,
        val limit: Int,
        val spec: LangSpec,
    ) {
        val tokens = mutableListOf<CodeToken>()
        var i = 0
        var prevSignificant = -1

        fun run(): List<CodeToken> {
            while (i < limit) {
                i = step()
            }
            return tokens
        }

        fun emit(type: CodeTokenType, start: Int, end: Int) {
            if (end > start) {
                tokens.add(CodeToken(type, start, end))
                prevSignificant = tokens.size - 1
            }
        }

        fun isIdentStart(c: Char): Boolean =
            c.isLetter() || c == '_' || (spec.identDollar && c == '$')

        fun isIdentPart(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || (spec.identDollar && c == '$') ||
                (spec.identDash && c == '-')

        fun step(): Int {
            val c = code[i]
            if (c.isWhitespace()) return i + 1
            if (spec.blockStart != null && code.startsWith(spec.blockStart, i)) return lexBlock()
            if (spec.markup && c == '<') return lexMarkup()
            for (lc in spec.lineComments) {
                if (code.startsWith(lc, i)) {
                    if (lc == "#" && spec.hashCommentBoundary && i > 0 && !code[i - 1].isWhitespace()) {
                        continue
                    }
                    var j = i
                    while (j < limit && code[j] != '\n') j++
                    emit(CodeTokenType.COMMENT, i, j)
                    return j
                }
            }
            if (spec.rustAttributes && c == '#' && i + 1 < limit &&
                (code[i + 1] == '[' || (code[i + 1] == '!' && i + 2 < limit && code[i + 2] == '['))
            ) return lexRustAttribute()
            if (spec.preprocessor && c == '#' && atLineStart()) return lexPreprocessor()
            if (spec.cssHexColor && c == '#' && i + 1 < limit && isHexDigit(code[i + 1])) {
                var j = i + 1
                while (j < limit && isHexDigit(code[j])) j++
                emit(CodeTokenType.NUMBER, i, j)
                return j
            }
            if (spec.yamlAnchors && (c == '&' || c == '*') && i + 1 < limit && isIdentStart(code[i + 1])) {
                var j = i + 1
                while (j < limit && isIdentPart(code[j])) j++
                emit(CodeTokenType.ANNOTATION, i, j)
                return j
            }
            if (spec.atAnnotations && c == '@' && i + 1 < limit && isIdentStart(code[i + 1])) {
                var j = i + 1
                while (j < limit) {
                    if (isIdentPart(code[j])) {
                        j++
                    } else if (code[j] == '.' && j + 1 < limit && isIdentStart(code[j + 1])) {
                        j++
                    } else break
                }
                emit(CodeTokenType.ANNOTATION, i, j)
                return j
            }
            if (spec.variables && c == '$') return lexVariable()
            for (rule in spec.strings) {
                if (code.startsWith(rule.delim, i)) {
                    val end = scanString(i, rule, rule.escapes)
                    emit(stringType(end), i, end)
                    return end
                }
            }
            if (spec.charLiterals && c == '\'') {
                val end = scanCharLiteral()
                if (end > 0) {
                    emit(CodeTokenType.STRING, i, end)
                    return end
                }
                return i + 1
            }
            if (c.isDigit() ||
                (c == '.' && i + 1 < limit && code[i + 1].isDigit()) ||
                (c == '-' && spec.signedNumbers && i + 1 < limit && code[i + 1].isDigit())
            ) {
                val end = scanNumber()
                emit(CodeTokenType.NUMBER, i, end)
                return end
            }
            if (isIdentStart(c)) return lexIdentifier()
            return i + 1
        }

        fun stringType(end: Int): CodeTokenType {
            if (spec.jsonKeys) {
                var k = end
                while (k < limit && code[k].isWhitespace()) k++
                if (k < limit && code[k] == ':') return CodeTokenType.TYPE
            }
            return CodeTokenType.STRING
        }

        fun atLineStart(): Boolean {
            var k = i - 1
            while (k >= 0 && code[k] != '\n') {
                if (!code[k].isWhitespace()) return false
                k--
            }
            return true
        }

        fun lexBlock(): Int {
            val bs = spec.blockStart!!
            val be = spec.blockEnd!!
            var j = i + bs.length
            var depth = 1
            while (j < limit && depth > 0) {
                when {
                    spec.nestedBlocks && code.startsWith(bs, j) -> {
                        depth++
                        j += bs.length
                    }
                    code.startsWith(be, j) -> {
                        depth--
                        j += be.length
                    }
                    else -> j++
                }
            }
            emit(CodeTokenType.COMMENT, i, j)
            return j
        }

        fun lexRustAttribute(): Int {
            var j = i + 1
            if (j < limit && code[j] == '!') j++
            var depth = 0
            while (j < limit) {
                when (code[j]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            j++
                            emit(CodeTokenType.ANNOTATION, i, j)
                            return j
                        }
                    }
                }
                j++
            }
            emit(CodeTokenType.ANNOTATION, i, j)
            return j
        }

        fun lexPreprocessor(): Int {
            var j = i + 1
            while (j < limit && (code[j] == ' ' || code[j] == '\t')) j++
            val wordStart = j
            while (j < limit && isIdentPart(code[j])) j++
            if (j == wordStart) return i + 1
            emit(CodeTokenType.ANNOTATION, i, j)
            if (code.substring(wordStart, j) == "include") {
                var k = j
                while (k < limit && (code[k] == ' ' || code[k] == '\t')) k++
                if (k < limit && code[k] == '<') {
                    var m = k + 1
                    while (m < limit && code[m] != '>' && code[m] != '\n') m++
                    val end = if (m < limit && code[m] == '>') m + 1 else m
                    emit(CodeTokenType.STRING, k, end)
                    return end
                }
            }
            return j
        }

        fun lexVariable(): Int {
            if (i + 1 >= limit) return i + 1
            val n = code[i + 1]
            if (n == '{') {
                var j = i + 2
                var depth = 1
                while (j < limit && depth > 0) {
                    when (code[j]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    j++
                }
                emit(CodeTokenType.TYPE, i, j)
                return j
            }
            if (isIdentStart(n) || n.isDigit()) {
                var j = i + 1
                while (j < limit && (isIdentPart(code[j]) || code[j].isDigit())) j++
                emit(CodeTokenType.TYPE, i, j)
                return j
            }
            if (n in "@*#?$!-") {
                emit(CodeTokenType.TYPE, i, i + 2)
                return i + 2
            }
            return i + 1
        }

        fun lexMarkup(): Int {
            if (code.startsWith("<!", i) || code.startsWith("<?", i)) {
                var j = i + 2
                while (j < limit && code[j] != '>') j++
                val end = if (j < limit) j + 1 else limit
                emit(CodeTokenType.ANNOTATION, i, end)
                return end
            }
            var j = i + 1
            if (j < limit && code[j] == '/') j++
            val nameStart = j
            while (j < limit && (code[j].isLetterOrDigit() || code[j] in "-_:")) j++
            if (j > nameStart) emit(CodeTokenType.KEYWORD, nameStart, j)
            while (j < limit && code[j] != '>') {
                val c = code[j]
                when {
                    c == '"' || c == '\'' -> {
                        var k = j + 1
                        while (k < limit && code[k] != c) k++
                        val end = if (k < limit) k + 1 else limit
                        emit(CodeTokenType.STRING, j, end)
                        j = end
                    }
                    c.isLetter() || c == '_' -> {
                        val s = j
                        while (j < limit && (code[j].isLetterOrDigit() || code[j] in "-_:")) j++
                        emit(CodeTokenType.TYPE, s, j)
                    }
                    else -> j++
                }
            }
            return if (j < limit) j + 1 else limit
        }

        fun scanString(quoteStart: Int, rule: StringRule, escapes: Boolean): Int {
            var j = quoteStart + rule.delim.length
            while (j < limit) {
                if (escapes && code[j] == '\\') {
                    j += 2
                    continue
                }
                if (code.startsWith(rule.delim, j)) {
                    if (rule.doubled && code.startsWith(rule.delim, j + rule.delim.length)) {
                        j += rule.delim.length * 2
                        continue
                    }
                    return j + rule.delim.length
                }
                if (code[j] == '\n' && !rule.multiline) return j
                j++
            }
            return limit
        }

        fun scanCharLiteral(): Int {
            var j = i + 1
            if (j < limit && code[j] == '\\') {
                j++
                if (j < limit) j++
            } else if (j < limit) {
                j++
            }
            return if (j < limit && code[j] == '\'') j + 1 else -1
        }

        fun isHexDigit(c: Char): Boolean =
            c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

        fun isBaseDigit(c: Char, kind: Char): Boolean = when (kind) {
            'x' -> isHexDigit(c)
            'b' -> c == '0' || c == '1'
            else -> c in '0'..'7'
        }

        fun scanNumber(): Int {
            var j = i
            if (code[j] == '-') j++
            if (code[j] == '.') {
                j++
                while (j < limit && (code[j].isDigit() || code[j] == spec.digitSep)) j++
            } else if (spec.hexNumbers && j + 1 < limit && code[j] == '0' && code[j + 1] in "xXbBoO") {
                val kind = code[j + 1].lowercaseChar()
                j += 2
                while (j < limit && (isBaseDigit(code[j], kind) || code[j] == spec.digitSep)) j++
            } else {
                while (j < limit && (code[j].isDigit() || code[j] == spec.digitSep)) j++
                if (j < limit && code[j] == '.' &&
                    (j + 1 >= limit || code[j + 1].isDigit() || !isIdentStart(code[j + 1]))
                ) {
                    j++
                    while (j < limit && (code[j].isDigit() || code[j] == spec.digitSep)) j++
                }
                if (j < limit && code[j] in "eE") {
                    var k = j + 1
                    if (k < limit && code[k] in "+-") k++
                    if (k < limit && code[k].isDigit()) {
                        j = k
                        while (j < limit && (code[j].isDigit() || code[j] == spec.digitSep)) j++
                    }
                }
            }
            while (j < limit && code[j] in spec.suffixLetters) j++
            if (spec.suffixDigits) {
                while (j < limit && code[j].isDigit()) j++
            }
            return j
        }

        fun lexIdentifier(): Int {
            var j = i
            while (j < limit && isIdentPart(code[j])) j++
            val word = code.substring(i, j)
            if (spec.stringPrefixes && word.length <= 2 &&
                word.lowercase() in setOf("r", "b", "u", "f", "rb", "br", "rf", "fr") &&
                j < limit && (code[j] == '"' || code[j] == '\'')
            ) {
                val rule = spec.strings.firstOrNull { code.startsWith(it.delim, j) }
                if (rule != null) {
                    val raw = 'r' in word.lowercase()
                    val end = scanString(j, rule, rule.escapes && !raw)
                    emit(CodeTokenType.STRING, i, end)
                    return end
                }
            }
            if (spec.rustRaw && (word == "r" || word == "br") && j < limit &&
                (code[j] == '"' || code[j] == '#')
            ) {
                var k = j
                var hashes = 0
                while (k < limit && code[k] == '#') {
                    hashes++
                    k++
                }
                if (k < limit && code[k] == '"') {
                    val closing = "\"" + "#".repeat(hashes)
                    val idx = code.indexOf(closing, k + 1)
                    val end = if (idx == -1 || idx + closing.length > limit) limit else idx + closing.length
                    emit(CodeTokenType.STRING, i, end)
                    return end
                }
            }
            if (spec.cppRaw && word in setOf("R", "u8R", "uR", "UR", "LR") &&
                j < limit && code[j] == '"'
            ) {
                val open = code.indexOf('(', j + 1)
                if (open != -1 && open < limit && open - j <= 18) {
                    val delim = code.substring(j + 1, open)
                    val closing = ")" + delim + "\""
                    val idx = code.indexOf(closing, open + 1)
                    val end = if (idx == -1 || idx + closing.length > limit) limit else idx + closing.length
                    emit(CodeTokenType.STRING, i, end)
                    return end
                }
            }
            val key = if (spec.ciKeywords) word.lowercase() else word
            if (key in spec.keywords) {
                emit(CodeTokenType.KEYWORD, i, j)
                return j
            }
            if (key in spec.types) {
                emit(CodeTokenType.TYPE, i, j)
                return j
            }
            var k = j
            while (k < limit && code[k].isWhitespace()) k++
            val prev = tokens.getOrNull(prevSignificant)
            val afterDefKeyword = prev != null && prev.type == CodeTokenType.KEYWORD &&
                code.substring(prev.start, prev.end) in setOf("fun", "func", "fn", "def", "function")
            if (afterDefKeyword || (k < limit && code[k] == '(')) {
                emit(CodeTokenType.FUNCTION, i, j)
                return j
            }
            if (spec.macroBang && k < limit && code[k] == '!') {
                var m = k + 1
                while (m < limit && code[m].isWhitespace()) m++
                if (m < limit && code[m] == '(') {
                    emit(CodeTokenType.FUNCTION, i, j)
                    return j
                }
            }
            if (spec.colonKeys && k < limit && code[k] == ':' &&
                (k + 1 >= limit || code[k + 1].isWhitespace())
            ) {
                emit(CodeTokenType.TYPE, i, j)
                return j
            }
            if (spec.cssProps && k < limit && code[k] == ':') {
                emit(CodeTokenType.TYPE, i, j)
                return j
            }
            return j
        }
    }
}
