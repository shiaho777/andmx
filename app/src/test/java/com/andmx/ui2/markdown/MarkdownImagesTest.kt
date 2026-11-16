package com.andmx.ui2.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownImagesTest {

    @Test
    fun singleImageParagraph() {
        val refs = MarkdownImages.parseImageOnlyParagraph("![logo](https://example.com/a.png)")
        assertEquals(listOf(MarkdownImages.ImageRef("logo", "https://example.com/a.png")), refs)
    }

    @Test
    fun multipleImagesMultipleLines() {
        val refs = MarkdownImages.parseImageOnlyParagraph(
            "![a](https://x/1.png)\n![b](https://x/2.png)",
        )
        assertEquals(
            listOf(
                MarkdownImages.ImageRef("a", "https://x/1.png"),
                MarkdownImages.ImageRef("b", "https://x/2.png"),
            ),
            refs,
        )
    }

    @Test
    fun multipleImagesSameLine() {
        val refs = MarkdownImages.parseImageOnlyParagraph("![a](u1) ![b](u2)")
        assertEquals(2, refs?.size)
        assertEquals("u1", refs?.get(0)?.url)
        assertEquals("u2", refs?.get(1)?.url)
    }

    @Test
    fun blankLinesAndIndentIgnored() {
        val refs = MarkdownImages.parseImageOnlyParagraph("\n  ![a](u1)  \n\n![b](u2)\n")
        assertEquals(2, refs?.size)
    }

    @Test
    fun mixedTextNotRecognized() {
        assertNull(MarkdownImages.parseImageOnlyParagraph("看这张图 ![a](https://x/1.png)"))
        assertNull(MarkdownImages.parseImageOnlyParagraph("![a](https://x/1.png) 后面有字"))
        assertNull(MarkdownImages.parseImageOnlyParagraph("![a](u1)\n普通文字"))
    }

    @Test
    fun plainTextAndLinkNotRecognized() {
        assertNull(MarkdownImages.parseImageOnlyParagraph("hello"))
        assertNull(MarkdownImages.parseImageOnlyParagraph("[a](https://x)"))
        assertNull(MarkdownImages.parseImageOnlyParagraph(""))
        assertNull(MarkdownImages.parseImageOnlyParagraph("  \n "))
    }

    @Test
    fun localPathImage() {
        val refs = MarkdownImages.parseImageOnlyParagraph("![本地](/sdcard/pic.png)")
        assertEquals(MarkdownImages.ImageRef("本地", "/sdcard/pic.png"), refs?.single())
        val fileRefs = MarkdownImages.parseImageOnlyParagraph("![f](file:///sdcard/pic.png)")
        assertEquals("file:///sdcard/pic.png", fileRefs?.single()?.url)
    }

    @Test
    fun imageWithTitle() {
        val refs = MarkdownImages.parseImageOnlyParagraph("""![a](https://x/1.png "标题")""")
        assertEquals(MarkdownImages.ImageRef("a", "https://x/1.png"), refs?.single())
        val single = MarkdownImages.parseImageOnlyParagraph("![a](u1 't')")
        assertEquals("u1", single?.single()?.url)
        val paren = MarkdownImages.parseImageOnlyParagraph("![a](u1 (t))")
        assertEquals("u1", paren?.single()?.url)
    }

    @Test
    fun blankAltFallsBack() {
        val refs = MarkdownImages.parseImageOnlyParagraph("![](https://x/1.png)")
        assertEquals("图片", refs?.single()?.alt)
    }

    @Test
    fun urlWithParens() {
        val refs = MarkdownImages.parseImageOnlyParagraph("![w](https://x/a_(b).png)")
        assertEquals("https://x/a_(b).png", refs?.single()?.url)
    }

    @Test
    fun unclosedImageNotRecognized() {
        assertNull(MarkdownImages.parseImageOnlyParagraph("![a](https://x/1.png"))
        assertNull(MarkdownImages.parseImageOnlyParagraph("![a]"))
    }

    @Test
    fun schemeAllowlist() {
        assertTrue(MarkdownImages.isSupportedUrl("https://x/a.png"))
        assertTrue(MarkdownImages.isSupportedUrl("http://x/a.png"))
        assertTrue(MarkdownImages.isSupportedUrl("file:///sdcard/a.png"))
        assertTrue(MarkdownImages.isSupportedUrl("/sdcard/a.png"))
        assertFalse(MarkdownImages.isSupportedUrl("content://media/1"))
        assertFalse(MarkdownImages.isSupportedUrl("ftp://x/a.png"))
        assertFalse(MarkdownImages.isSupportedUrl("javascript:alert(1)"))
        assertFalse(MarkdownImages.isSupportedUrl(""))
    }

    @Test
    fun cacheKeyIsSha256HexOfUrl() {
        val key = MarkdownImages.cacheKey("https://example.com/a.png")
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, MarkdownImages.cacheKey("https://example.com/a.png"))
        assertFalse(key == MarkdownImages.cacheKey("https://example.com/b.png"))
    }

    @Test
    fun evictNoopUnderLimit() {
        val entries = listOf(
            MarkdownImages.CacheEntry("a", 10, 1),
            MarkdownImages.CacheEntry("b", 10, 2),
        )
        assertTrue(MarkdownImages.evictOldest(entries, 20).isEmpty())
        assertTrue(MarkdownImages.evictOldest(emptyList(), 20).isEmpty())
    }

    @Test
    fun evictOldestFirstUntilUnderLimit() {
        val entries = listOf(
            MarkdownImages.CacheEntry("new", 30, 300),
            MarkdownImages.CacheEntry("old", 30, 100),
            MarkdownImages.CacheEntry("mid", 30, 200),
        )
        assertEquals(listOf("old"), MarkdownImages.evictOldest(entries, 60))
        assertEquals(listOf("old", "mid"), MarkdownImages.evictOldest(entries, 30))
        assertEquals(listOf("old", "mid", "new"), MarkdownImages.evictOldest(entries, 0))
    }

    @Test
    fun sampleSizeKeepsLongestEdgeWithinLimit() {
        assertEquals(1, MarkdownImages.sampleSize(2048, 1024))
        assertEquals(2, MarkdownImages.sampleSize(4096, 2048))
        assertEquals(4, MarkdownImages.sampleSize(8192, 100))
        assertEquals(8, MarkdownImages.sampleSize(100, 16384))
        assertEquals(1, MarkdownImages.sampleSize(0, 0))
        assertEquals(1, MarkdownImages.sampleSize(500, 300))
    }
}
