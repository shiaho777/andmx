package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSnapshotTest {

    @Test
    fun pressureLabelsFollowTokenThresholds() {
        assertEquals("轻量", contextPressureLabel(1_000))
        assertEquals("中等", contextPressureLabel(20_000))
        assertEquals("偏高", contextPressureLabel(50_000))
        assertEquals("需要压缩", contextPressureLabel(100_000))
    }

    @Test
    fun contextSnapshotIncludesCountsAndAdvice() {
        val text = contextSnapshotText(
            ContextSnapshot(
                project = "andmx",
                model = "gpt-test",
                tokenEstimate = 55_000,
                messageCount = 12,
                userMessages = 3,
                assistantMessages = 4,
                toolEvents = 4,
                approvalEvents = 1,
                changedFiles = 2,
                sourceLinks = 3,
                recentActivity = 8,
            ),
        )

        assertTrue(text.contains("## 上下文快照"))
        assertTrue(text.contains("`andmx`"))
        assertTrue(text.contains("~55000 tokens"))
        assertTrue(text.contains("**偏高**"))
        assertTrue(text.contains("工具事件: 4"))
        assertTrue(text.contains("待审变更: 2"))
        assertTrue(text.contains("建议减少重复输出"))
        assertTrue(text.contains("先审查 Diff"))
    }
}
