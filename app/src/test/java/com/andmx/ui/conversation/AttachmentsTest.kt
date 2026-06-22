package com.andmx.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {

    @Test
    fun imageAttachmentsBecomeUiReferencePrompt() {
        val attachments = listOf(
            Attachment(
                name = "Codex screenshot.png",
                content = "(UI 截图参考)",
                imageDataUrl = "data:image/png;base64,abc",
                imageMeta = ImageAttachmentMeta(
                    mime = "image/png",
                    byteSize = 432_000,
                    width = 1440,
                    height = 900,
                ),
            ),
        )

        val augmented = Attachments.augment("按图改 UI", attachments)
        val display = Attachments.displaySummary(attachments)

        assertTrue(attachments.first().isUiReference)
        assertEquals("UI 参考", attachments.first().composerKindLabel)
        assertEquals("1440x900 · image/png · 421.9 KB", attachments.first().composerMetaLabel)
        assertTrue(augmented.contains("## 图片/UI 参考"))
        assertTrue(augmented.contains("布局结构 -> 可见控件 -> 状态语言"))
        assertTrue(augmented.contains("复刻执行单"))
        assertTrue(augmented.contains("Composer、会话流、Inspector"))
        assertTrue(augmented.contains("Codex screenshot.png · UI 参考"))
        assertTrue(augmented.contains("1440x900 · image/png · 421.9 KB"))
        assertTrue(display.startsWith("\n🖼 Codex screenshot.png · UI 参考 · 1440x900 · image/png · 421.9 KB · ref:"))
        assertTrue(Regex("""ref:[a-f0-9]{8}""").containsMatchIn(display))
    }

    @Test
    fun referencesCanBeRestoredFromDisplayedUserMessage() {
        val refs = Attachments.referencesFromDisplay(
            """
            复刻这个界面
            🖼 截图 2026-06-09.png · UI 参考
            📎 notes.md
            """.trimIndent(),
        )

        assertEquals(2, refs.size)
        assertEquals("截图 2026-06-09.png · UI 参考", refs[0].label)
        assertTrue(refs[0].image)
        assertEquals("notes.md", refs[1].label)
    }

    @Test
    fun referencesRestoreImageMetadataFromDisplayedUserMessage() {
        val refs = Attachments.referencesFromDisplay(
            "看这个\n🖼 Codex screenshot.png · UI 参考 · 1440x900 · image/png · 421.9 KB · ref:abcd1234",
        )

        assertEquals(1, refs.size)
        assertEquals("Codex screenshot.png · UI 参考", refs.first().label)
        assertEquals("1440x900 · image/png · 421.9 KB · ref:abcd1234", refs.first().meta)
        assertTrue(refs.first().image)
    }

    @Test
    fun referencesRestoreImageAssetPathFromDisplayedUserMessage() {
        val refs = Attachments.referencesFromDisplay(
            "看这个\n🖼 Codex screenshot.png · UI 参考 · 1440x900 · image/png · 421.9 KB · ref:abcd1234 · asset:ui-references/ref-abcd1234.png",
        )

        assertEquals(1, refs.size)
        assertEquals("Codex screenshot.png · UI 参考", refs.first().label)
        assertEquals("1440x900 · image/png · 421.9 KB · ref:abcd1234", refs.first().meta)
        assertEquals("ui-references/ref-abcd1234.png", refs.first().assetPath)
        assertTrue(refs.first().image)
    }

    @Test
    fun imageAssetHelpersCreateStablePrivateRelativePaths() {
        assertEquals(
            "ui-references/ref-abcd1234.png",
            UiReferenceAssets.relativePath("ref:abcd1234", mime = "image/png", name = "Codex screenshot.png"),
        )
        assertEquals(
            "ui-references/ref-abcd1234.jpg",
            UiReferenceAssets.relativePath("ref:abcd1234", mime = "image/jpeg", name = "photo.jpeg"),
        )
        assertEquals("asset:ui-references/ref-abcd1234.png", UiReferenceAssets.marker("ui-references/ref-abcd1234.png"))
        assertEquals("ui-references/ref-abcd1234.png", UiReferenceAssets.pathFromMarker("asset:ui-references/ref-abcd1234.png"))
    }

    @Test
    fun composerLabelsDistinguishFilesImagesAndBinaryAttachments() {
        val file = Attachment(name = "notes.md", content = "hello")
        val binary = Attachment(name = "archive.zip", content = "(二进制文件,未内联)", truncated = true)
        val image = Attachment(
            name = "photo.png",
            content = "(图片)",
            imageDataUrl = "data:image/png;base64,abc",
            imageMeta = ImageAttachmentMeta(mime = "image/png", byteSize = 2048, width = 320, height = 240),
            imageAssetPath = "ui-references/ref-deadbeef.png",
        )

        assertEquals("文件", file.composerKindLabel)
        assertEquals("", file.composerMetaLabel)
        assertEquals("", file.referenceId)
        assertEquals("附件", binary.composerKindLabel)
        assertEquals("未内联", binary.composerMetaLabel)
        assertEquals("图片", image.composerKindLabel)
        assertEquals("320x240 · image/png · 2 KB", image.composerMetaLabel)
        assertTrue(image.referenceId.matches(Regex("""ref:[a-f0-9]{8}""")))
        assertTrue(image.referenceLabel.contains("asset:ui-references/ref-deadbeef.png"))
    }

    @Test
    fun preflightSummaryCountsVisualReferencesAndRoutes() {
        val attachments = listOf(
            Attachment(
                name = "Codex screenshot.png",
                content = "(UI 截图参考)",
                imageDataUrl = "data:image/png;base64,abc",
                imageMeta = ImageAttachmentMeta(mime = "image/png", byteSize = 432_000, width = 1440, height = 900),
                imageAssetPath = "ui-references/ref-abcd1234.png",
            ),
            Attachment(
                name = "photo.png",
                content = "(图片)",
                imageDataUrl = "data:image/png;base64,def",
                imageMeta = ImageAttachmentMeta(mime = "image/png", byteSize = 2048, width = 320, height = 240),
            ),
            Attachment(name = "notes.md", content = "hello"),
        )

        val summary = Attachments.preflightSummary(attachments)

        assertEquals(3, summary.attachmentCount)
        assertEquals(2, summary.imageCount)
        assertEquals(1, summary.uiReferenceCount)
        assertEquals(2, summary.metadataCount)
        assertEquals(1, summary.assetCount)
        assertEquals(listOf("/references", "/trace", "/evidence"), summary.routeCommands)
        assertEquals("3 个附件 · 2 张图片 · 1 个 UI 参考", summary.title)
        assertEquals("发送后进入 /references、/trace、/evidence", summary.detail)
        assertTrue(summary.hasVisualReferences)
    }

    @Test
    fun preflightSummaryKeepsPlainFilesQuiet() {
        val summary = Attachments.preflightSummary(listOf(Attachment(name = "notes.md", content = "hello")))

        assertEquals(1, summary.attachmentCount)
        assertEquals(0, summary.imageCount)
        assertEquals(0, summary.uiReferenceCount)
        assertEquals(listOf("/evidence"), summary.routeCommands)
        assertEquals("1 个附件", summary.title)
        assertEquals("发送后进入 /evidence", summary.detail)
        assertTrue(!summary.hasVisualReferences)
    }

    @Test
    fun displayTextPreservesAttachmentsForSlashCommands() {
        val attachment = Attachment(
            name = "Codex command palette screenshot.png",
            content = "(UI 截图参考)",
            imageDataUrl = "data:image/png;base64,abc",
            imageMeta = ImageAttachmentMeta(mime = "image/png", byteSize = 432_000, width = 1440, height = 900),
            imageAssetPath = "ui-references/ref-abcd1234.png",
        )

        val display = Attachments.displayText("/references", listOf(attachment))

        assertTrue(display.startsWith("/references\n🖼"))
        assertTrue(display.contains("Codex command palette screenshot.png · UI 参考"))
        assertTrue(display.contains("1440x900 · image/png · 421.9 KB"))
        assertTrue(Regex("""ref:[a-f0-9]{8}""").containsMatchIn(display))
        assertTrue(display.contains("asset:ui-references/ref-abcd1234.png"))
    }

    @Test
    fun localIntakeTextExplainsOfflineScreenshotRouting() {
        val attachment = Attachment(
            name = "Codex inspector screenshot.png",
            content = "(UI 截图参考)",
            imageDataUrl = "data:image/png;base64,abc",
            imageMeta = ImageAttachmentMeta(mime = "image/png", byteSize = 432_000, width = 1440, height = 900),
            imageAssetPath = "ui-references/ref-abcd1234.png",
        )

        val text = Attachments.localIntakeText(listOf(attachment))

        assertTrue(text.contains("## 本地截图/附件接收"))
        assertTrue(text.contains("1 个附件 · 1 张图片 · 1 个 UI 参考"))
        assertTrue(text.contains("元数据: 1 个图片附件"))
        assertTrue(text.contains("本地资产: 1 张图片"))
        assertTrue(text.contains("配置模型前也会保留在参考板和证据账本"))
        assertTrue(text.contains("`/references`"))
        assertTrue(text.contains("`/trace`"))
        assertTrue(text.contains("`/evidence`"))
    }
}
