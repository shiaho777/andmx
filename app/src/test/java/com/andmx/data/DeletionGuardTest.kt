package com.andmx.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionGuardTest {

    @Test
    fun blocksDeletionWhileRunning() {
        assertFalse(DeletionGuard.canDelete(running = true))
    }

    @Test
    fun allowsDeletionWhenIdle() {
        assertTrue(DeletionGuard.canDelete(running = false))
    }

    @Test
    fun blocksDuplicateDeletionWhileDeleting() {
        assertFalse(DeletionGuard.canDelete(running = false, deleting = true))
        assertFalse(DeletionGuard.canDelete(running = true, deleting = true))
    }

    @Test
    fun previewSummaryListsCounts() {
        val summary = DeletionGuard.previewSummary(
            DeletionPreview(messageCount = 12, rolloutFiles = 1, topicFiles = 1),
        )
        assertEquals("12 条消息 · 1 个会话记录文件 · 1 个话题文件", summary)
    }

    @Test
    fun previewSummaryWithZeroArtifacts() {
        val summary = DeletionGuard.previewSummary(
            DeletionPreview(messageCount = 0, rolloutFiles = 0, topicFiles = 0),
        )
        assertEquals("0 条消息 · 0 个会话记录文件 · 0 个话题文件", summary)
    }

    @Test
    fun previewForSplitsRolloutAndTopicPaths() {
        val paths = listOf(
            File("/data/rollouts/sess-1.jsonl"),
            File("/data/topics/sess-1.jsonl"),
        )
        val preview = DeletionGuard.previewFor(messageCount = 5, purgePaths = paths)
        assertEquals(5, preview.messageCount)
        assertEquals(1, preview.rolloutFiles)
        assertEquals(1, preview.topicFiles)
    }

    @Test
    fun previewForEmptyPaths() {
        val preview = DeletionGuard.previewFor(messageCount = 3, purgePaths = emptyList())
        assertEquals(3, preview.messageCount)
        assertEquals(0, preview.rolloutFiles)
        assertEquals(0, preview.topicFiles)
    }
}