package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantFileCitationsTest {

    @Test
    fun citationBecomesLinkAndCard() {
        val projection = AssistantFileCitations.project(
            """See ::zcode-file-citation{path="docs/report.pdf"} for details.""",
        )

        assertEquals("See [report.pdf](docs/report.pdf) for details.", projection.text)
        assertEquals(1, projection.cards.size)
        assertEquals("docs/report.pdf", projection.cards[0].path)
        assertEquals("report.pdf", projection.cards[0].name)
        assertEquals(AssistantFileKind.PDF, projection.cards[0].kind)
    }

    @Test
    fun singleColonCompatibilityFormIsAccepted() {
        val projection = AssistantFileCitations.project(
            """:zcode-file-citation{path='docs/slides.pptx' artifact_kind="presentation"}""",
        )

        assertEquals("[slides.pptx](docs/slides.pptx)", projection.text)
        assertEquals(AssistantFileKind.OFFICE, projection.cards.single().kind)
    }

    @Test
    fun fencedCodeAndInlineCodeAreLeftAlone() {
        val raw = """
            ```
            ::zcode-file-citation{path="code.pdf"}
            ```
            Inline `::zcode-file-citation{path="inline.pdf"}` example.
        """.trimIndent()

        val projection = AssistantFileCitations.project(raw)

        assertEquals(raw, projection.text)
        assertTrue(projection.cards.isEmpty())
    }

    @Test
    fun incompatibleArtifactKindKeepsLinkButDropsCard() {
        val projection = AssistantFileCitations.project(
            """::zcode-file-citation{path="docs/report.pdf" artifact_kind="document"}""",
        )

        assertEquals("[report.pdf](docs/report.pdf)", projection.text)
        assertTrue(projection.cards.isEmpty())
    }

    @Test
    fun localMarkdownLinkBecomesCardButHttpDoesNot() {
        val projection = AssistantFileCitations.project(
            "Open [notes](notes/today.md) and [site](https://example.com/a.pdf).",
        )

        assertEquals(1, projection.cards.size)
        assertEquals("notes/today.md", projection.cards.single().path)
        assertEquals(AssistantFileKind.MARKDOWN, projection.cards.single().kind)
    }

    @Test
    fun cardsAreDeduplicatedAndCappedAtTen() {
        val raw = (1..12).joinToString("\n") {
            """::zcode-file-citation{path="docs/$it.pdf"}"""
        } + "\n" + """::zcode-file-citation{path="docs/1.pdf"}"""

        val projection = AssistantFileCitations.project(raw)

        assertEquals(10, projection.cards.size)
        assertEquals((1..10).map { "docs/$it.pdf" }.reversed(), projection.cards.map { it.path })
    }

    @Test
    fun plainPreviewPathsBecomeCardsButUrlsDoNot() {
        val projection = AssistantFileCitations.project(
            "Look at docs/report.pdf, /root/project/clip.mp4 and https://example.com/site.pdf.",
        )

        assertEquals(
            listOf("/root/project/clip.mp4", "docs/report.pdf"),
            projection.cards.map { it.path },
        )
    }

    @Test
    fun plainPathsInsideCodeBlocksAreIgnored() {
        val raw = """
            ```
            docs/report.pdf
            ```
            docs/notes.md
        """.trimIndent()

        val projection = AssistantFileCitations.project(raw)

        assertEquals(listOf("docs/notes.md"), projection.cards.map { it.path })
    }

    @Test
    fun pathsWithSpacesAreEncodedForTheBodyLink() {
        val projection = AssistantFileCitations.project(
            """::zcode-file-citation{path="docs/my report.pdf"}""",
        )

        assertEquals("[my report.pdf](docs/my%20report.pdf)", projection.text)
        assertEquals("docs/my report.pdf", projection.cards.single().path)
    }

    @Test
    fun fileUriIsAcceptedForMarkdownLinks() {
        val projection = AssistantFileCitations.project(
            "[clip](file:///root/project/clip.mp4)",
        )

        assertEquals("/root/project/clip.mp4", projection.cards.single().path)
        assertEquals(AssistantFileKind.VIDEO, projection.cards.single().kind)
    }

    @Test
    fun localhostUrlBecomesWebsiteCard() {
        val projection = AssistantFileCitations.project(
            "Open http://localhost:3000/dashboard?tab=1 for the preview.",
        )

        val card = projection.cards.single()
        assertEquals(AssistantFileKind.WEB, card.kind)
        assertEquals("http://localhost:3000/dashboard?tab=1", card.url)
        assertEquals("localhost:3000/dashboard", card.name)
    }

    @Test
    fun loopbackMarkdownLinkBecomesWebsiteCard() {
        val projection = AssistantFileCitations.project(
            "[preview](http://127.0.0.1:5173/)",
        )

        val card = projection.cards.single()
        assertEquals(AssistantFileKind.WEB, card.kind)
        assertEquals("http://127.0.0.1:5173/", card.url)
        assertEquals("127.0.0.1:5173", card.name)
    }

    @Test
    fun externalHttpsUrlDoesNotBecomeCard() {
        val projection = AssistantFileCitations.project(
            "Docs at https://example.com/guide.pdf and http://localhost:8080/app.",
        )

        assertEquals(1, projection.cards.size)
        assertEquals("http://localhost:8080/app", projection.cards.single().url)
    }

    @Test
    fun nonPreviewableCitationStillLinksWithoutCard() {
        val projection = AssistantFileCitations.project(
            """::zcode-file-citation{path="src/Main.kt"}""",
        )

        assertEquals("[Main.kt](src/Main.kt)", projection.text)
        assertFalse(projection.cards.isNotEmpty())
    }
}
