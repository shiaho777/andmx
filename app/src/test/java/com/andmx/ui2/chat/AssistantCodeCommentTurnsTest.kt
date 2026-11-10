package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCodeCommentTurnsTest {

    private fun message(
        id: Long,
        role: String,
        content: String,
        streaming: Boolean = false,
        process: Boolean = false,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        isStreaming = streaming,
        isProcess = process,
        sortKey = id,
        createdAt = id,
    )

    private fun directive(title: String) =
        """::code-comment{title="$title" body="B" file="a.kt"}"""

    @Test
    fun commentsFromEarlierAssistantRowMoveToTurnTail() {
        val messages = listOf(
            message(1, "user", "do it"),
            message(2, "assistant", directive("first")),
            message(3, "assistant", "done"),
        )

        val result = assistantCodeCommentsByTail(messages)

        assertFalse(2L in result)
        assertEquals(listOf("first"), result[3]?.map { it.title })
    }

    @Test
    fun commentsFromMultipleRowsMergeInOrderOnTail() {
        val messages = listOf(
            message(1, "user", "do it"),
            message(2, "assistant", directive("first")),
            message(3, "assistant", directive("second")),
        )

        val result = assistantCodeCommentsByTail(messages)

        assertEquals(listOf("first", "second"), result[3]?.map { it.title })
    }

    @Test
    fun newUserMessageStartsNewTurn() {
        val messages = listOf(
            message(1, "user", "one"),
            message(2, "assistant", directive("first")),
            message(3, "user", "two"),
            message(4, "assistant", directive("second")),
        )

        val result = assistantCodeCommentsByTail(messages)

        assertEquals(listOf("first"), result[2]?.map { it.title })
        assertEquals(listOf("second"), result[4]?.map { it.title })
    }

    @Test
    fun streamingTailDoesNotGetCardsYet() {
        val messages = listOf(
            message(1, "user", "do it"),
            message(2, "assistant", directive("first")),
            message(3, "assistant", "still working", streaming = true),
        )

        val result = assistantCodeCommentsByTail(messages)

        assertTrue(result.isEmpty())
    }

    @Test
    fun processTailDoesNotGetCards() {
        val messages = listOf(
            message(1, "user", "do it"),
            message(2, "assistant", directive("first")),
            message(3, "assistant", "process row", process = true),
        )

        val result = assistantCodeCommentsByTail(messages)

        assertTrue(result.isEmpty())
    }

    @Test
    fun turnCommentsAreCappedAtFifty() {
        val messages = listOf(
            message(1, "user", "do it"),
            message(
                2,
                "assistant",
                (1..55).joinToString("\n") { directive("T$it") },
            ),
        )

        val result = assistantCodeCommentsByTail(messages)

        assertEquals(50, result[2]?.size)
    }
}
