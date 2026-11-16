package com.andmx.ui2.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {

    private val theme = CodeThemes.GithubDark

    private fun stylesOf(code: String) = CodeHighlight.highlight(code, theme).spanStyles

    private fun assertToken(
        code: String,
        lang: String,
        text: String,
        type: CodeTokenType,
        occurrence: Int = 0,
    ) {
        var from = -1
        repeat(occurrence + 1) {
            from = code.indexOf(text, from + 1)
            assertTrue("snippet not found: $text", from >= 0)
        }
        val end = from + text.length
        val tokens = CodeHighlight.tokenize(code, lang)
        assertTrue(
            "expected $type at $from..$end, got $tokens",
            tokens.any { it.type == type && it.start == from && it.end == end },
        )
    }

    private fun assertNoTokenIn(code: String, lang: String, text: String, vararg types: CodeTokenType) {
        val from = code.indexOf(text)
        assertTrue(from >= 0)
        val to = from + text.length
        val tokens = CodeHighlight.tokenize(code, lang)
        assertFalse(
            "unexpected token inside $text",
            tokens.any { it.type in types && it.start < to && it.end > from },
        )
    }

    @Test
    fun preservesAllText() {
        val code = "fun main() {\n  val x = \"hi\" // note\n  println(x)\n}"
        assertEquals(code, CodeHighlight.highlight(code, theme).text)
    }

    @Test
    fun handlesEmpty() {
        assertEquals("", CodeHighlight.highlight("", theme).text)
    }

    @Test
    fun keywordsAreStyled() {
        val spans = stylesOf("val x = 1")
        val keywordSpan = spans.firstOrNull { it.item.color == theme.keyword }
            ?: error("keyword span missing")
        assertEquals(0, keywordSpan.start)
        assertEquals(3, keywordSpan.end)
    }

    @Test
    fun stringsAreStyled() {
        val spans = stylesOf("val s = \"hello\"")
        val stringSpan = spans.firstOrNull { it.item.color == theme.string }
            ?: error("string span missing")
        assertEquals(8, stringSpan.start)
        assertEquals(15, stringSpan.end)
    }

    @Test
    fun commentsAreStyled() {
        val spans = stylesOf("x = 1 // note")
        val commentSpan = spans.firstOrNull { it.item.color == theme.comment }
            ?: error("comment span missing")
        assertEquals(6, commentSpan.start)
        assertEquals(13, commentSpan.end)
    }

    @Test
    fun numbersAreStyled() {
        val spans = stylesOf("y = 3.14")
        val numberSpan = spans.firstOrNull { it.item.color == theme.number }
            ?: error("number span missing")
        assertEquals(4, numberSpan.start)
        assertEquals(8, numberSpan.end)
    }

    @Test
    fun functionCallsAreStyledButNotKeywords() {
        val spans = stylesOf("println(x)")
        val fnSpan = spans.firstOrNull { it.item.color == theme.function }
            ?: error("function span missing")
        assertEquals(0, fnSpan.start)
        assertEquals(7, fnSpan.end)
    }

    @Test
    fun hashCommentsAreStyled() {
        val spans = stylesOf("# python note\ndef f(): pass")
        assertTrue(spans.any { it.item.color == theme.comment })
        assertTrue(spans.any { it.item.color == theme.keyword })
    }

    @Test
    fun allBundledThemesPreserveText() {
        val code = "suspend fun f(a: Int): String = \"v=\$a\" /* tail */"
        for (t in CodeThemes.all) {
            assertEquals(code, CodeHighlight.highlight(code, t).text)
        }
    }

    @Test
    fun kotlinSnapshot() {
        val code = "fun main() { val n = 0xFF; println(n) } // tail"
        assertToken(code, "kotlin", "fun", CodeTokenType.KEYWORD)
        assertToken(code, "kotlin", "main", CodeTokenType.FUNCTION)
        assertToken(code, "kotlin", "val", CodeTokenType.KEYWORD)
        assertToken(code, "kotlin", "0xFF", CodeTokenType.NUMBER)
        assertToken(code, "kotlin", "println", CodeTokenType.FUNCTION)
        assertToken(code, "kotlin", "// tail", CodeTokenType.COMMENT)
    }

    @Test
    fun kotlinTripleQuotedString() {
        val code = "val s = \"\"\"a \"b\" // c\"\"\""
        assertToken(code, "kotlin", "\"\"\"a \"b\" // c\"\"\"", CodeTokenType.STRING)
        assertNoTokenIn(code, "kotlin", "a \"b\" // c", CodeTokenType.COMMENT, CodeTokenType.KEYWORD)
    }

    @Test
    fun kotlinNestedBlockComment() {
        val code = "/* a /* b */ c */ val x = 1"
        assertToken(code, "kotlin", "/* a /* b */ c */", CodeTokenType.COMMENT)
        assertToken(code, "kotlin", "val", CodeTokenType.KEYWORD)
    }

    @Test
    fun kotlinNumbers() {
        val code = "val a = 1_000 + 3.14 + 1e-3 + 2.5f + 0b1010"
        assertToken(code, "kotlin", "1_000", CodeTokenType.NUMBER)
        assertToken(code, "kotlin", "3.14", CodeTokenType.NUMBER)
        assertToken(code, "kotlin", "1e-3", CodeTokenType.NUMBER)
        assertToken(code, "kotlin", "2.5f", CodeTokenType.NUMBER)
        assertToken(code, "kotlin", "0b1010", CodeTokenType.NUMBER)
    }

    @Test
    fun kotlinAnnotation() {
        val code = "@JvmStatic fun f() {}"
        assertToken(code, "kotlin", "@JvmStatic", CodeTokenType.ANNOTATION)
    }

    @Test
    fun javaSnapshot() {
        val code = "public class A { int x = 0xFF; } // \"unclosed"
        assertToken(code, "java", "public", CodeTokenType.KEYWORD)
        assertToken(code, "java", "class", CodeTokenType.KEYWORD)
        assertToken(code, "java", "int", CodeTokenType.TYPE)
        assertToken(code, "java", "0xFF", CodeTokenType.NUMBER)
        assertToken(code, "java", "// \"unclosed", CodeTokenType.COMMENT)
        assertFalse(CodeHighlight.tokenize(code, "java").any { it.type == CodeTokenType.STRING })
    }

    @Test
    fun pythonSnapshot() {
        val code = "def f(x):\n    return x + 1  # note"
        assertToken(code, "python", "def", CodeTokenType.KEYWORD)
        assertToken(code, "python", "f", CodeTokenType.FUNCTION, occurrence = 1)
        assertToken(code, "python", "return", CodeTokenType.KEYWORD)
        assertToken(code, "python", "# note", CodeTokenType.COMMENT)
    }

    @Test
    fun pythonDecorator() {
        val code = "@app.route(\"/x\")\ndef view():\n    pass"
        assertToken(code, "python", "@app.route", CodeTokenType.ANNOTATION)
        assertToken(code, "python", "\"/x\"", CodeTokenType.STRING)
        assertToken(code, "python", "def", CodeTokenType.KEYWORD)
    }

    @Test
    fun pythonEscapedQuoteAndTriple() {
        val code = "s = 'it\\'s'\nt = \"\"\"line1\nline2\"\"\""
        assertToken(code, "python", "'it\\'s'", CodeTokenType.STRING)
        assertToken(code, "python", "\"\"\"line1\nline2\"\"\"", CodeTokenType.STRING)
    }

    @Test
    fun pythonRawString() {
        val code = "p = r\"a\\nb\""
        assertToken(code, "python", "r\"a\\nb\"", CodeTokenType.STRING)
    }

    @Test
    fun javascriptSnapshot() {
        val code = "const x = 1_000; function f() { return x; }"
        assertToken(code, "javascript", "const", CodeTokenType.KEYWORD)
        assertToken(code, "javascript", "1_000", CodeTokenType.NUMBER)
        assertToken(code, "javascript", "function", CodeTokenType.KEYWORD)
        assertToken(code, "javascript", "f", CodeTokenType.FUNCTION, occurrence = 1)
    }

    @Test
    fun javascriptTemplateString() {
        val code = "const t = `hi \${name} // x`;"
        assertToken(code, "javascript", "`hi \${name} // x`", CodeTokenType.STRING)
        assertNoTokenIn(code, "javascript", "hi \${name} // x", CodeTokenType.COMMENT)
    }

    @Test
    fun javascriptEscapedQuote() {
        val code = "const s = \"a\\\"b\";"
        assertToken(code, "javascript", "\"a\\\"b\"", CodeTokenType.STRING)
    }

    @Test
    fun typescriptSnapshot() {
        val code = "interface U { id: number }\nconst f = (x: string) => x;"
        assertToken(code, "typescript", "interface", CodeTokenType.KEYWORD)
        assertToken(code, "typescript", "number", CodeTokenType.TYPE)
        assertToken(code, "typescript", "string", CodeTokenType.TYPE)
        assertToken(code, "typescript", "const", CodeTokenType.KEYWORD)
    }

    @Test
    fun goSnapshot() {
        val code = "func main() { s := \"a//b\"; /* c */ _ = s }"
        assertToken(code, "go", "func", CodeTokenType.KEYWORD)
        assertToken(code, "go", "main", CodeTokenType.FUNCTION)
        assertToken(code, "go", "\"a//b\"", CodeTokenType.STRING)
        assertToken(code, "go", "/* c */", CodeTokenType.COMMENT)
    }

    @Test
    fun goRawString() {
        val code = "s := `line1\nline2 \"x\"`"
        assertToken(code, "go", "`line1\nline2 \"x\"`", CodeTokenType.STRING)
    }

    @Test
    fun rustSnapshot() {
        val code = "#[derive(Debug)]\nfn main() { let n: u32 = 1_000; println!(\"{}\", n); }"
        assertToken(code, "rust", "#[derive(Debug)]", CodeTokenType.ANNOTATION)
        assertToken(code, "rust", "fn", CodeTokenType.KEYWORD)
        assertToken(code, "rust", "u32", CodeTokenType.TYPE)
        assertToken(code, "rust", "1_000", CodeTokenType.NUMBER)
        assertToken(code, "rust", "println", CodeTokenType.FUNCTION)
    }

    @Test
    fun rustRawString() {
        val code = "let s = r#\"a\"b\"#;"
        assertToken(code, "rust", "r#\"a\"b\"#", CodeTokenType.STRING)
    }

    @Test
    fun cSnapshot() {
        val code = "#include <stdio.h>\nint main(void) { return 0; }"
        assertToken(code, "c", "#include", CodeTokenType.ANNOTATION)
        assertToken(code, "c", "<stdio.h>", CodeTokenType.STRING)
        assertToken(code, "c", "int", CodeTokenType.TYPE)
        assertToken(code, "c", "main", CodeTokenType.FUNCTION)
        assertToken(code, "c", "return", CodeTokenType.KEYWORD)
    }

    @Test
    fun cppSnapshot() {
        val code = "auto s = R\"(a\"b)\"; // note\nstd::vector<int> v;"
        assertToken(code, "cpp", "R\"(a\"b)\"", CodeTokenType.STRING)
        assertToken(code, "cpp", "// note", CodeTokenType.COMMENT)
        assertToken(code, "cpp", "auto", CodeTokenType.KEYWORD)
        assertToken(code, "cpp", "vector", CodeTokenType.TYPE)
    }

    @Test
    fun shellSnapshot() {
        val code = "#!/bin/bash\nNAME=\"x # y\" # real\necho \$NAME"
        assertToken(code, "shell", "#!/bin/bash", CodeTokenType.COMMENT)
        assertToken(code, "shell", "\"x # y\"", CodeTokenType.STRING)
        assertToken(code, "shell", "# real", CodeTokenType.COMMENT)
        assertToken(code, "shell", "echo", CodeTokenType.TYPE)
        assertToken(code, "shell", "\$NAME", CodeTokenType.TYPE)
    }

    @Test
    fun sqlSnapshotCaseInsensitive() {
        val code = "SeLeCt name FROM users WHERE id = 1; -- 'x'"
        assertToken(code, "sql", "SeLeCt", CodeTokenType.KEYWORD)
        assertToken(code, "sql", "FROM", CodeTokenType.KEYWORD)
        assertToken(code, "sql", "WHERE", CodeTokenType.KEYWORD)
        assertToken(code, "sql", "1", CodeTokenType.NUMBER)
        assertToken(code, "sql", "-- 'x'", CodeTokenType.COMMENT)
        assertFalse(CodeHighlight.tokenize(code, "sql").any { it.type == CodeTokenType.STRING })
    }

    @Test
    fun sqlDoubledQuote() {
        val code = "SELECT 'it''s' FROM t"
        assertToken(code, "sql", "'it''s'", CodeTokenType.STRING)
    }

    @Test
    fun jsonSnapshot() {
        val code = "{\"key\": \"value\", \"n\": -1.5e2, \"b\": true}"
        assertToken(code, "json", "\"key\"", CodeTokenType.TYPE)
        assertToken(code, "json", "\"value\"", CodeTokenType.STRING)
        assertToken(code, "json", "-1.5e2", CodeTokenType.NUMBER)
        assertToken(code, "json", "true", CodeTokenType.KEYWORD)
    }

    @Test
    fun yamlSnapshot() {
        val code = "name: \"v\" # c\nenabled: true"
        assertToken(code, "yaml", "name", CodeTokenType.TYPE)
        assertToken(code, "yaml", "\"v\"", CodeTokenType.STRING)
        assertToken(code, "yaml", "# c", CodeTokenType.COMMENT)
        assertToken(code, "yaml", "true", CodeTokenType.KEYWORD)
    }

    @Test
    fun htmlSnapshot() {
        val code = "<div class=\"a\"><!-- x --></div>"
        assertToken(code, "html", "div", CodeTokenType.KEYWORD)
        assertToken(code, "html", "class", CodeTokenType.TYPE)
        assertToken(code, "html", "\"a\"", CodeTokenType.STRING)
        assertToken(code, "html", "<!-- x -->", CodeTokenType.COMMENT)
        assertToken(code, "html", "div", CodeTokenType.KEYWORD, occurrence = 1)
    }

    @Test
    fun xmlRoutesToMarkup() {
        val code = "<note id=\"1\">hi</note>"
        assertToken(code, "xml", "note", CodeTokenType.KEYWORD)
        assertToken(code, "xml", "id", CodeTokenType.TYPE)
        assertToken(code, "xml", "\"1\"", CodeTokenType.STRING)
    }

    @Test
    fun cssSnapshot() {
        val code = ".a { color: #fff; margin: 10px; } /* c */"
        assertToken(code, "css", "color", CodeTokenType.TYPE)
        assertToken(code, "css", "#fff", CodeTokenType.NUMBER)
        assertToken(code, "css", "10px", CodeTokenType.NUMBER)
        assertToken(code, "css", "/* c */", CodeTokenType.COMMENT)
    }

    @Test
    fun unknownLanguageFallsBackToGeneric() {
        val code = "x = \"str\" 42 # note"
        assertToken(code, "brainfuck", "\"str\"", CodeTokenType.STRING)
        assertToken(code, "brainfuck", "42", CodeTokenType.NUMBER)
        assertToken(code, "brainfuck", "# note", CodeTokenType.COMMENT)
        assertFalse(CodeHighlight.tokenize(code, "brainfuck").any { it.type == CodeTokenType.KEYWORD })
    }

    @Test
    fun keywordsInsideStringNotHighlighted() {
        val code = "val s = \"fun val class // x\""
        assertToken(code, "kotlin", "\"fun val class // x\"", CodeTokenType.STRING)
        assertNoTokenIn(
            code, "kotlin", "fun val class // x",
            CodeTokenType.KEYWORD, CodeTokenType.COMMENT, CodeTokenType.FUNCTION,
        )
    }

    @Test
    fun quotesInsideCommentDoNotBreakState() {
        val code = "// he said \"hi\nval x = 1"
        assertToken(code, "kotlin", "// he said \"hi", CodeTokenType.COMMENT)
        assertToken(code, "kotlin", "val", CodeTokenType.KEYWORD)
        assertFalse(CodeHighlight.tokenize(code, "kotlin").any { it.type == CodeTokenType.STRING })
    }

    @Test
    fun keywordsInsideBlockCommentNotHighlighted() {
        val code = "/* fun \"val\" */ var y = 2"
        assertToken(code, "kotlin", "/* fun \"val\" */", CodeTokenType.COMMENT)
        assertToken(code, "kotlin", "var", CodeTokenType.KEYWORD)
        assertNoTokenIn(code, "kotlin", "fun \"val\"", CodeTokenType.KEYWORD, CodeTokenType.STRING)
    }

    @Test
    fun extensionRouting() {
        val code = "def f(): pass"
        assertToken(code, ".py", "def", CodeTokenType.KEYWORD)
        assertToken("fun g() {}", "foo.kt", "fun", CodeTokenType.KEYWORD)
    }

    @Test
    fun largeInputOnlyScansPrefix() {
        val code = "val x = 1\n".repeat(25_000)
        assertTrue(code.length > 200_000)
        val tokens = CodeHighlight.tokenize(code, "kotlin")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.all { it.end <= 200_000 })
        assertEquals(code, CodeHighlight.highlight(code, theme, "kotlin").text)
    }

    @Test
    fun highlightWithLanguagePreservesText() {
        val code = "SELECT 'a' FROM t -- c"
        assertEquals(code, CodeHighlight.highlight(code, theme, "sql").text)
    }
}
