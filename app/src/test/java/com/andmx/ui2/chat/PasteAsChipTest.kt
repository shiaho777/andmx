package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 粘贴长文本转附件 chip（ZCode paste-as-attachment 对齐）：
 * 单次插入 ≥800 字符时抽出为 PastedSegment，其余键入不触发。
 */
class PasteAsChipTest {

    private fun longText(n: Int = 900) = "x".repeat(n)

    @Test
    fun middleInsertionExtractsSegment() {
        val old = "AB"
        val seg = longText()
        val new = "A${seg}B"
        val r = extractPastedSegment(old, new)!!
        assertEquals(seg, r.text)
        assertEquals(old, new.removeRange(r.start, r.endExclusive))
    }

    @Test
    fun ambiguousSpaceCountsIntoSegment() {
        // 前后缀争抢同一字符时，重叠字符归入插入片段（最小删除重建原文）。
        val old = "hello world"
        val seg = longText()
        val new = "hello ${seg} world"
        val r = extractPastedSegment(old, new)!!
        assertEquals(old, new.removeRange(r.start, r.endExclusive))
        assertEquals("${seg} ", r.text)
    }

    @Test
    fun tailPasteExtractsSegment() {
        val old = "前缀"
        val seg = longText()
        val r = extractPastedSegment(old, old + seg)!!
        assertEquals(seg, r.text)
    }

    @Test
    fun headPasteExtractsSegment() {
        val old = "后缀"
        val seg = longText()
        val r = extractPastedSegment(old, seg + old)!!
        assertEquals(seg, r.text)
    }

    @Test
    fun shortInsertionIgnored() {
        assertNull(extractPastedSegment("", "a".repeat(799)))
        assertNull(extractPastedSegment("ab", "abc"))
    }

    @Test
    fun deletionIgnored() {
        assertNull(extractPastedSegment("a".repeat(2000), "a".repeat(500)))
    }

    @Test
    fun duplicatedSegmentRemovesOnlyPastedCopy() {
        val seg = longText(800)
        val old = seg // 粘贴内容与已有文本相同
        val new = seg + seg
        val r = extractPastedSegment(old, new)!!
        assertEquals(seg, new.removeRange(r.start, r.endExclusive))
    }
}
