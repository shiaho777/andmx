package com.andmx.data

data class DeletionPreview(
    val messageCount: Int,
    val rolloutFiles: Int,
    val topicFiles: Int,
)

object DeletionGuard {
    fun canDelete(running: Boolean, deleting: Boolean = false): Boolean = !running && !deleting

    fun previewSummary(preview: DeletionPreview): String =
        "${preview.messageCount} 条消息 · ${preview.rolloutFiles} 个会话记录文件 · ${preview.topicFiles} 个话题文件"

    fun previewFor(messageCount: Int, purgePaths: List<java.io.File>): DeletionPreview =
        DeletionPreview(
            messageCount = messageCount,
            rolloutFiles = purgePaths.count { it.parentFile?.name != "topics" },
            topicFiles = purgePaths.count { it.parentFile?.name == "topics" },
        )
}