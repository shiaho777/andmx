package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话引用上限（ZCode chat.selections.limit 对齐）：
 * 单条 ≤ 8,000 字符、最多 8 条、总计 ≤ 16,000 字符。
 */
class MessageSelectionsTest {

    private fun chip(id: String, content: String) = ContextChip(
        id = id,
        kind = ContextChipKind.MESSAGE,
        label = "引用",
        payload = content,
    )

    private fun msg(n: Int, size: Int = 100) = chip("msg:$n", "x".repeat(size))

    @Test
    fun acceptsUnderAllLimits() {
        val current = (0 until 7).map { msg(it) }
        val result = MessageSelections.validate(current, "new content", "msg:99")
        assertTrue(result is MessageSelections.AddResult.Ok)
    }

    @Test
    fun rejectsNinthSelection() {
        val current = (0 until 8).map { msg(it, size = 10) }
        val result = MessageSelections.validate(current, "content", "msg:99")
        assertTrue(result is MessageSelections.AddResult.Rejected)
        assertEquals("最多可添加 8 条对话引用。", (result as MessageSelections.AddResult.Rejected).reason)
    }

    @Test
    fun rejectsOversizedSingle() {
        val result = MessageSelections.validate(emptyList(), "x".repeat(8_001), "msg:1")
        assertTrue(result is MessageSelections.AddResult.Rejected)
        assertEquals("单条引用最多 8,000 个字符。", (result as MessageSelections.AddResult.Rejected).reason)
    }

    @Test
    fun acceptsAtExactSingleLimit() {
        val result = MessageSelections.validate(emptyList(), "x".repeat(8_000), "msg:1")
        assertTrue(result is MessageSelections.AddResult.Ok)
    }

    @Test
    fun rejectsExceedingTotal() {
        val current = listOf(chip("msg:1", "x".repeat(10_000)))
        val result = MessageSelections.validate(current, "x".repeat(6_001), "msg:2")
        assertTrue(result is MessageSelections.AddResult.Rejected)
        assertEquals("对话引用总计最多 16,000 个字符。", (result as MessageSelections.AddResult.Rejected).reason)
    }

    @Test
    fun acceptsAtExactTotalLimit() {
        val current = listOf(chip("msg:1", "x".repeat(10_000)))
        val result = MessageSelections.validate(current, "x".repeat(6_000), "msg:2")
        assertTrue(result is MessageSelections.AddResult.Ok)
    }

    @Test
    fun duplicateIdPassesThrough() {
        // 已存在的引用再次添加：直接放行（去重），不因 count/total 误拒
        val current = (0 until 8).map { msg(it, size = 10) }
        val result = MessageSelections.validate(current, "dup", "msg:3")
        assertTrue(result is MessageSelections.AddResult.Ok)
    }

    @Test
    fun otherChipKindsDoNotConsumeSelectionQuota() {
        // FILE/CONVERSATION chips 不占用对话引用的 8 条与 16k 上限
        val files = (0 until 10).map { i ->
            ContextChip(id = "file:$i", kind = ContextChipKind.FILE, label = "@f$i", payload = "/p/$i")
        }
        val result = MessageSelections.validate(files, "content", "msg:99")
        assertNull(result as? MessageSelections.AddResult.Rejected)
        assertNotNull(result as? MessageSelections.AddResult.Ok)
    }
}
