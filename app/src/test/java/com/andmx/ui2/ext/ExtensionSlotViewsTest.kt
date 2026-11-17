package com.andmx.ui2.ext

import com.andmx.agent.ext.SlotDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionSlotViewsTest {

    private fun decl(
        pluginId: String,
        slotId: String,
        title: String = pluginId,
        htmlAsset: String = "ext/$pluginId/index.html",
    ) = SlotDeclaration(pluginId, slotId, title, htmlAsset)

    private val sample = listOf(
        decl("a", ExtensionSlotIds.COMPOSER_STATUS),
        decl("b", ExtensionSlotIds.COMPOSER_MENTION),
        decl("c", ExtensionSlotIds.SIDE_PANE_LAUNCHER),
        decl("d", ExtensionSlotIds.NAV_PRIMARY),
        decl("e", ExtensionSlotIds.FILE_VIEWER, htmlAsset = "ext/e/viewer.html.pdf.html"),
        decl("f", ExtensionSlotIds.COMPOSER_STATUS),
    )

    @Test
    fun slotFilterKeepsOnlyMatchingSlotInOrder() {
        val matched = declarationsForSlot(sample, ExtensionSlotIds.COMPOSER_STATUS)
        assertEquals(listOf("a", "f"), matched.map { it.pluginId })
    }

    @Test
    fun slotFilterReturnsEmptyForUnknownOrEmptySlot() {
        assertTrue(declarationsForSlot(sample, "no.such.slot").isEmpty())
        assertTrue(declarationsForSlot(emptyList(), ExtensionSlotIds.NAV_PRIMARY).isEmpty())
        assertTrue(declarationsForSlot(sample, "").isEmpty())
    }

    @Test
    fun emptyDeclarationsMeanNoEntryForEverySlot() {
        val slots = listOf(
            ExtensionSlotIds.COMPOSER_STATUS,
            ExtensionSlotIds.COMPOSER_MENTION,
            ExtensionSlotIds.SIDE_PANE_LAUNCHER,
            ExtensionSlotIds.NAV_PRIMARY,
            ExtensionSlotIds.FILE_VIEWER,
        )
        slots.forEach { slot ->
            assertTrue(declarationsForSlot(emptyList(), slot).isEmpty())
        }
        assertNull(fileViewerDeclaration(emptyList(), "report.pdf"))
    }

    @Test
    fun entryKeyIsStableAndDistinct() {
        val first = decl("a", ExtensionSlotIds.NAV_PRIMARY, htmlAsset = "ext/a/index.html")
        assertEquals("a|ext/a/index.html", slotEntryKey(first))
        assertEquals(slotEntryKey(first), slotEntryKey(first.copy()))
        assertFalse(slotEntryKey(first) == slotEntryKey(first.copy(pluginId = "b")))
    }

    @Test
    fun extensionExtractionNormalizesCaseAndMissingDot() {
        assertEquals("pdf", extensionOf("report.PDF"))
        assertEquals("gz", extensionOf("archive.tar.gz"))
        assertEquals("", extensionOf("README"))
        assertEquals("", extensionOf("trailing."))
    }

    @Test
    fun viewerExtensionsParseMultiExtensionAssetName() {
        assertEquals(
            listOf("html", "pdf"),
            viewerExtensions("ext/e/viewer.html.pdf.html"),
        )
        assertEquals(listOf("csv"), viewerExtensions("ext/f/csv-viewer.csv.html"))
        assertTrue(viewerExtensions("ext/g/index.html").isEmpty())
    }

    @Test
    fun fileViewerMatchesByExtensionCaseInsensitively() {
        val viewer = decl("e", ExtensionSlotIds.FILE_VIEWER, htmlAsset = "ext/e/viewer.pdf.html")
        assertTrue(matchesFileViewer(viewer, "report.pdf"))
        assertTrue(matchesFileViewer(viewer, "REPORT.PDF"))
        assertFalse(matchesFileViewer(viewer, "report.txt"))
        assertFalse(matchesFileViewer(viewer, "README"))
    }

    @Test
    fun genericIndexAssetDoesNotClaimFiles() {
        val generic = decl("g", ExtensionSlotIds.FILE_VIEWER, htmlAsset = "ext/g/index.html")
        assertFalse(matchesFileViewer(generic, "index.html"))
        assertNull(fileViewerDeclaration(listOf(generic), "index.html"))
    }

    @Test
    fun fileViewerDeclarationPicksFirstMatchInFileViewerSlotOnly() {
        val pdfViewer = decl("e", ExtensionSlotIds.FILE_VIEWER, htmlAsset = "ext/e/viewer.pdf.html")
        val csvViewer = decl("f", ExtensionSlotIds.FILE_VIEWER, htmlAsset = "ext/f/viewer.csv.html")
        val navWithPdfName = decl("n", ExtensionSlotIds.NAV_PRIMARY, htmlAsset = "ext/n/viewer.pdf.html")
        val all = listOf(navWithPdfName, pdfViewer, csvViewer)
        assertEquals("e", fileViewerDeclaration(all, "a.pdf")?.pluginId)
        assertEquals("f", fileViewerDeclaration(all, "b.csv")?.pluginId)
        assertNull(fileViewerDeclaration(all, "c.txt"))
    }

    @Test
    fun codecRoundTripsAllFields() {
        val original = decl(
            "插件-1",
            ExtensionSlotIds.COMPOSER_STATUS,
            title = "状态 \"面板\"",
            htmlAsset = "ext/p1/index.html",
        )
        val decoded = SlotDeclarationCodec.decode(SlotDeclarationCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun codecDecodeRejectsMalformedInput() {
        assertNull(SlotDeclarationCodec.decode("not json"))
        assertNull(SlotDeclarationCodec.decode("{}"))
        assertNull(SlotDeclarationCodec.decode("""{"pluginId":"a","slotId":"x","title":"t"}"""))
        assertNull(SlotDeclarationCodec.decode("""{"pluginId":1,"slotId":"x","title":"t","htmlAsset":"h"}"""))
    }
}