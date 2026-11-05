package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCodeCommentsTest {

    @Test
    fun standaloneDirectiveIsRemovedAndParsed() {
        val raw = """
            Review below.
            ::code-comment{title="[P1] Null risk" body="user may be null" file="src/user.ts" start=42 end=45 priority=1}
            Done.
        """.trimIndent()

        val projection = AssistantCodeComments.project(raw)

        assertEquals("Review below.\nDone.", projection.text)
        val comment = projection.comments.single()
        assertEquals("Null risk", comment.displayTitle)
        assertEquals("user may be null", comment.body)
        assertEquals("src/user.ts", comment.file)
        assertEquals(42, comment.startLine)
        assertEquals(45, comment.endLine)
        assertEquals(1, comment.priority)
        assertEquals("src/user.ts:42-45", comment.location)
    }

    @Test
    fun inlineDirectiveIsReplacedWithSpace() {
        val projection = AssistantCodeComments.project(
            """Before ::code-comment{title="T" body="B" file="a.kt"} after""",
        )

        assertEquals("Before   after", projection.text)
        assertEquals(1, projection.comments.size)
    }

    @Test
    fun missingRequiredFieldKeepsRawDirective() {
        val raw = """::code-comment{title="T" body="B"}"""
        val projection = AssistantCodeComments.project(raw)

        assertEquals(raw, projection.text)
        assertTrue(projection.comments.isEmpty())
    }

    @Test
    fun codeBlocksAndInlineCodeAreIgnored() {
        val raw = """
            ```
            ::code-comment{title="T" body="B" file="a.kt"}
            ```
            Inline `::code-comment{title="T" body="B" file="a.kt"}` example.
        """.trimIndent()

        val projection = AssistantCodeComments.project(raw)

        assertEquals(raw, projection.text)
        assertTrue(projection.comments.isEmpty())
    }

    @Test
    fun invalidPriorityFallsBackToNoPriority() {
        val projection = AssistantCodeComments.project(
            """::code-comment{title="T" body="B" file="a.kt" priority=9}""",
        )

        assertNull(projection.comments.single().priority)
    }

    @Test
    fun startOnlyBecomesSingleLineRange() {
        val projection = AssistantCodeComments.project(
            """::code-comment{title="T" body="B" file="a.kt" start=7}""",
        )

        assertEquals("a.kt:7", projection.comments.single().location)
    }

    @Test
    fun endBeforeStartDropsRangeButKeepsComment() {
        val projection = AssistantCodeComments.project(
            """::code-comment{title="T" body="B" file="a.kt" start=9 end=3}""",
        )

        val comment = projection.comments.single()
        assertNull(comment.startLine)
        assertNull(comment.endLine)
        assertEquals("a.kt", comment.location)
    }

    @Test
    fun commentsAreCappedAtFifty() {
        val raw = (1..55).joinToString("\n") {
            """::code-comment{title="T$it" body="B" file="a.kt"}"""
        }

        val projection = AssistantCodeComments.project(raw)

        assertEquals(50, projection.comments.size)
        assertEquals("T1", projection.comments.first().title)
        assertEquals("T50", projection.comments.last().title)
    }

    @Test
    fun singleColonIsNotAccepted() {
        val raw = """:code-comment{title="T" body="B" file="a.kt"}"""
        val projection = AssistantCodeComments.project(raw)

        assertEquals(raw, projection.text)
        assertTrue(projection.comments.isEmpty())
    }

    @Test
    fun escapedQuotesAndDuplicatePriorityPrefix() {
        val projection = AssistantCodeComments.project(
            """::code-comment{title="[P2] Use \"safe\" access" body="B" file="a.kt" priority=2}""",
        )

        val comment = projection.comments.single()
        assertEquals("Use \"safe\" access", comment.displayTitle)
        assertEquals(2, comment.priority)
    }
}
