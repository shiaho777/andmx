package com.andmx.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentTextTest {

    @Test
    fun readableDocumentsAreInlinedInFencedBlocks() {
        val attachment = Attachment(
            name = "notes.md",
            content = "# 部署\n使用 gradlew",
        )

        val text = Attachments.augmentText("请看附件", listOf(attachment))

        assertTrue(text.startsWith("请看附件"))
        assertTrue(text.contains("附件 notes.md"))
        assertTrue(text.contains("```\n# 部署\n使用 gradlew\n```"))
    }

    @Test
    fun truncatedDocumentsAreMarked() {
        val text = Attachments.augmentText(
            "x",
            listOf(Attachment(name = "big.txt", content = "a", truncated = true)),
        )

        assertTrue(text.contains("(已截断)"))
    }

    @Test
    fun imagesAndBinaryPlaceholdersDoNotEnterTheTextChannel() {
        val image = Attachment(name = "shot.png", content = "🖼", imageDataUrl = "data:image/png;base64,AA")
        val binary = Attachment(name = "model.bin", content = "(二进制文件,未内联)", truncated = true)

        assertEquals("只发文字", Attachments.augmentText("只发文字", listOf(image, binary)))
        assertFalse(Attachments.augmentText("x", listOf(image)).contains("图片/UI 参考"))
    }
}
