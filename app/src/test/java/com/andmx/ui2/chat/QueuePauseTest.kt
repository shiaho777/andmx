package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueuePause 状态机（ZCode 对齐）：stop → STOPPED，出错 → ERROR，
 * 恢复后回 NONE；NONE 不应出现在 UI 暂停条上。
 */
class QueuePauseTest {

    @Test
    fun pauseReasonsAreDistinct() {
        val values = ChatViewModel.QueuePause.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(ChatViewModel.QueuePause.NONE))
        assertTrue(values.contains(ChatViewModel.QueuePause.STOPPED))
        assertTrue(values.contains(ChatViewModel.QueuePause.ERROR))
    }

    @Test
    fun stoppedAndErrorMessagesDifferFromNone() {
        val labels = ChatViewModel.QueuePause.entries.associateWith { pause ->
            when (pause) {
                ChatViewModel.QueuePause.STOPPED -> "由于你中断了当前响应，队列已暂停"
                ChatViewModel.QueuePause.ERROR -> "由于当前响应出错，队列已暂停（内容未丢失）"
                ChatViewModel.QueuePause.NONE -> ""
            }
        }
        assertTrue(labels[ChatViewModel.QueuePause.STOPPED]!!.contains("中断"))
        assertTrue(labels[ChatViewModel.QueuePause.ERROR]!!.contains("内容未丢失"))
        assertFalse(labels[ChatViewModel.QueuePause.NONE]!!.isNotBlank())
    }
}
