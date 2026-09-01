package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePreviewTest {

    @Test
    fun shortTextPassesThrough() {
        val plan = MessagePreview.plan("hello")
        assertFalse(plan.truncated)
        assertEquals("hello", plan.preview)
        assertEquals(5, plan.fullBytes)
    }

    @Test
    fun longTextIsTruncatedWithMarker() {
        val text = "x".repeat(MessagePreview.PREVIEW_THRESHOLD + 1)
        val plan = MessagePreview.plan(text)
        assertTrue(plan.truncated)
        assertEquals(MessagePreview.PREVIEW_THRESHOLD + 1, plan.fullBytes)
        assertTrue(plan.preview.length < text.length)
        assertTrue(plan.preview.endsWith("…"))
    }

    @Test
    fun previewCutsAtParagraphBoundaryWhenPossible() {
        val para = "a".repeat(6_500)
        val text = "$para\n\n" + "b".repeat(MessagePreview.PREVIEW_THRESHOLD)
        val plan = MessagePreview.plan(text)
        assertTrue(plan.truncated)
        assertTrue("应在段落边界截断", plan.preview.contains("$para\n\n…"))
    }

    @Test
    fun previewAvoidsCuttingInsideCodeFence() {
        val pre = "y".repeat(8_500)
        val text = "$pre\n```\ncode\n```\n" + "z".repeat(MessagePreview.PREVIEW_THRESHOLD)
        val plan = MessagePreview.plan(text)
        assertTrue(plan.truncated)
        // 预览内反引号数量应为偶数个（不切开围栏）
        val backtickCount = plan.preview.count { it == '`' }
        assertEquals(0, backtickCount % 2)
    }

    @Test
    fun exactlyAtThresholdIsNotTruncated() {
        val text = "x".repeat(MessagePreview.PREVIEW_THRESHOLD)
        assertFalse(MessagePreview.plan(text).truncated)
    }

    @Test
    fun customThresholdRespected() {
        val text = "x".repeat(100)
        val plan = MessagePreview.plan(text, threshold = 50, previewChars = 40)
        assertTrue(plan.truncated)
        assertTrue(plan.preview.length < 60)
    }
}
