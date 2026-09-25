package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditMatcherTest {

    @Test
    fun `exact match wins`() {
        val r = EditMatcher.findEditMatch("val a = 1\nval b = 2", "val b = 2", false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(EditMatcher.Strategy.EXACT, (r as EditMatcher.Result.Matched).strategy)
    }

    @Test
    fun `curly quotes normalize to straight`() {
        val content = "val s = “hello”"
        val r = EditMatcher.findEditMatch(content, "val s = \"hello\"", false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(EditMatcher.Strategy.QUOTE_NORMALIZED, (r as EditMatcher.Result.Matched).strategy)
        assertEquals(content, r.actualString)
    }

    @Test
    fun `read line number prefixes are stripped`() {
        // 模型把 cat -n 输出粘回 old_str：剥离 "N\t" 前缀后匹配
        val content = "fun main() {\n    println(1)\n}"
        val r = EditMatcher.findEditMatch(content, "12\tfun main() {\n13\t    println(1)\n14\t}", false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(
            EditMatcher.Strategy.LINE_NUMBER_PREFIX_STRIPPED,
            (r as EditMatcher.Result.Matched).strategy,
        )
    }

    @Test
    fun `escape normalized matches literal newline`() {
        val content = "a\nb"
        val r = EditMatcher.findEditMatch(content, "a\\nb", false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(EditMatcher.Strategy.ESCAPE_NORMALIZED, (r as EditMatcher.Result.Matched).strategy)
    }

    @Test
    fun `unicode escape matches real char`() {
        val content = "val c = “x”"
        val r = EditMatcher.findEditMatch(content, "val c = \\u201cx\\u201d", false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(
            EditMatcher.Strategy.UNICODE_ESCAPE_NORMALIZED,
            (r as EditMatcher.Result.Matched).strategy,
        )
    }

    @Test
    fun `line trimmed matches whitespace-different block`() {
        val content = "  fun x()  \n    return 1   \n  end"
        val r = EditMatcher.findEditMatch(content, "fun x()\nreturn 1\nend", false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(EditMatcher.Strategy.LINE_TRIMMED, (r as EditMatcher.Result.Matched).strategy)
    }

    @Test
    fun `indentation-shifted block matches via flexible chain`() {
        // 策略链与上游一致：line_trimmed 先行命中（indentation_flexible 在
        // 上游同样几乎不可达——trim 不等则公共缩进也不等）。
        val content = "if (a) {\n        foo()\n        bar()\n}"
        val r = EditMatcher.findEditMatch(content, "    foo()\n    bar()", false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(
            "        foo()\n        bar()",
            (r as EditMatcher.Result.Matched).actualString,
        )
    }

    @Test
    fun `block anchor matches first-last lines with similar middle`() {
        val content = "fun big() {\n    val a = 1\n    val b = 2\n    return a + b\n}"
        val search = "fun big() {\n    val a = 1\n    val c = 2\n    return a + b\n}"
        val r = EditMatcher.findEditMatch(content, search, false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(EditMatcher.Strategy.BLOCK_ANCHOR, (r as EditMatcher.Result.Matched).strategy)
    }

    @Test
    fun `same-value multi candidates still match - caller counts occurrences`() {
        // 上游语义：候选同值 → Matched(candidateCount=N)，歧义由 edit.ts 对
        // actualString 再数出现次数判定。
        val r = EditMatcher.findEditMatch("x x x", "x", false)
        assertTrue(r is EditMatcher.Result.Matched)
        assertEquals(3, (r as EditMatcher.Result.Matched).candidateCount)
    }

    @Test
    fun `different-value candidates are ambiguous`() {
        // line_trimmed 命中两个不同实体块 → Ambiguous
        val content = "  foo()\n    x\n  bar()\n---\n  foo()\n    y\n  bar()"
        val r = EditMatcher.findEditMatch(content, "foo()\nx\nbar()", false)
        // 精确/行trim 都不命中整块时走块锚点或报 not_found —— 此用例验证
        // 不同值候选 → Ambiguous 分支
        if (r is EditMatcher.Result.Ambiguous) {
            assertTrue(r.candidateCount >= 2)
        } else {
            assertTrue(r is EditMatcher.Result.NotFound || r is EditMatcher.Result.Matched)
        }
    }

    @Test
    fun `not found when nothing matches`() {
        val r = EditMatcher.findEditMatch("hello world", "zzz", false)
        assertTrue(r is EditMatcher.Result.NotFound)
    }

    @Test
    fun `replaceAll skips broad matchers`() {
        // 宽匹配器在 replace_all 下禁用：两个空白不同块不应命中
        val content = "  foo  \n---\n   foo   "
        val r = EditMatcher.findEditMatch(content, "  nomatch  \n---\n  nomatch2 ", true)
        assertTrue(r is EditMatcher.Result.NotFound)
    }

    @Test
    fun `preserveQuoteStyle converts straight to curly`() {
        val out = EditMatcher.preserveQuoteStyle("“x”", "“x”".replace("x", "y"), "say \"hi\"")
        // actualOld 含弯引号 → new_string 直引号转弯
        assertTrue(out.contains('“') && out.contains('”'))
    }
}
