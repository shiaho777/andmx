package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerPlaceholderTest {

    @Test
    fun firstSubmitWinsOverEmptyTimeline() {
        assertEquals(
            "继续输入，发送后加入队列，本轮结束后再跑",
            composerPlaceholder(flat = true, isLoading = true, guide = false),
        )
    }

    @Test
    fun guideModeHasPriorityWhileLoading() {
        assertEquals(
            "继续输入，发送后引导当前这一轮",
            composerPlaceholder(flat = true, isLoading = true, guide = true),
        )
    }

    @Test
    fun idleEmptyTimelineShowsNewTaskHint() {
        assertEquals(DEFAULT_PLACEHOLDER, composerPlaceholder(flat = true, isLoading = false, guide = false))
    }

    @Test
    fun existingHistoryShowsContinueHint() {
        assertEquals(
            "继续这个任务，@ 提及文件，/ 使用命令，\$ 使用技能",
            composerPlaceholder(flat = false, isLoading = false, guide = false),
        )
    }
}
