package com.andmx.agent

import com.andmx.workspace.FileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSummaryTest {

    @Test
    fun summarizesChangedFilesWithStatsAndNewFileMarker() {
        val changes = listOf(
            FileChange("/root/app.kt", oldContent = "a\nb", newContent = "a\nc\nd", existedBefore = true),
            FileChange("/root/new.md", oldContent = "", newContent = "one\ntwo", existedBefore = false),
        )

        val items = changeSummaryItems(changes)

        assertEquals("/root/app.kt", items[0].path)
        assertEquals(2, items[0].added)
        assertEquals(1, items[0].removed)
        assertEquals(false, items[0].isNew)
        assertEquals(true, items[1].isNew)
        assertEquals(2, items[1].added)

        val text = changeSummaryText(changes)
        assertTrue(text.contains("## 变更摘要"))
        assertTrue(text.contains("文件: 2"))
        assertTrue(text.contains("行数: +4 / -1"))
        assertTrue(text.contains("`/root/app.kt` · 修改 · +2 / -1"))
        assertTrue(text.contains("`/root/new.md` · 新建 · +2 / -0"))
    }

    @Test
    fun emptySummaryShowsReviewHint() {
        val text = changeSummaryText(emptyList())

        assertTrue(text.contains("暂无待审变更"))
        assertTrue(text.contains("打开 Diff 面板"))
    }
}
