package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleGeneratorTest {

    @Test
    fun jsonTitleParses() {
        assertEquals("Fix login crash", TitleGenerator.cleanGeneratedTitle("""{"title":"Fix login crash"}"""))
    }

    @Test
    fun fencedJsonParses() {
        assertEquals("Refactor parser", TitleGenerator.cleanGeneratedTitle("```json\n{\"title\":\"Refactor parser\"}\n```"))
    }

    @Test
    fun thinkTagsStripped() {
        assertEquals("Add dark mode", TitleGenerator.cleanGeneratedTitle("<think>pondering</think>{\"title\":\"Add dark mode\"}"))
    }

    @Test
    fun plainLineFallbackAndPunctStripped() {
        assertEquals("调查崩溃原因", TitleGenerator.cleanGeneratedTitle("调查崩溃原因。"))
        // 上游清洗顺序：先引号后标点——"Fix bug"! 的尾部引号残留与上游一致
        assertEquals("Fix bug\"", TitleGenerator.cleanGeneratedTitle("\"Fix bug\"!"))
    }

    @Test
    fun garbageRejected() {
        assertNull(TitleGenerator.cleanGeneratedTitle(""))
        assertNull(TitleGenerator.cleanGeneratedTitle("!!!"))
        // 非 JSON 文本走首行兜底（上游行为）
        assertEquals("{not json}", TitleGenerator.cleanGeneratedTitle("{not json}"))
    }

    @Test
    fun longTitleTruncated() {
        val t = TitleGenerator.cleanGeneratedTitle("""{"title":"${"a".repeat(150)}"}""")
        assertEquals(TitleGenerator.MAX_TITLE_CHARS, t!!.length)
        assertTrue(t.endsWith("..."))
    }

    @Test
    fun inputNormalized() {
        assertEquals("a b c", TitleGenerator.normalizeTitleInput("  a\n  b\tc "))
        assertEquals(TitleGenerator.MAX_TITLE_INPUT_CHARS, TitleGenerator.normalizeTitleInput("x".repeat(2000)).length)
    }
}
