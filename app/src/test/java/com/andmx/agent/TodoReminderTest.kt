package com.andmx.agent

import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoReminderTest {

    private fun assistant(toolNames: List<String> = emptyList()) = ApiMessage(
        role = "assistant",
        content = if (toolNames.isEmpty()) "done" else null,
        toolCalls = toolNames.map { ApiToolCall(id = it, function = ApiFunctionCall(it, "{}")) },
    )

    private fun reminder() = ApiMessage(role = "system", content = TodoReminder.reminderText())

    private fun rounds(count: Int): List<ApiMessage> =
        (1..count).flatMap { listOf(ApiMessage(role = "user", content = "go"), assistant()) }

    @Test
    fun noReminderForShortHistory() {
        assertFalse(TodoReminder.shouldRemind(rounds(5)))
    }

    @Test
    fun reminderAfterTenQuietRounds() {
        assertTrue(TodoReminder.shouldRemind(rounds(12)))
    }

    @Test
    fun todoWriteResetsTheClock() {
        val history = rounds(15) +
            listOf(
                assistant(listOf("TodoWrite")),
                ApiMessage(role = "tool", content = "ok", toolCallId = "TodoWrite"),
            ) +
            rounds(3)
        assertFalse(TodoReminder.shouldRemind(history))
    }

    @Test
    fun previousReminderDefersTheNextOne() {
        val history = rounds(12) + listOf(reminder()) + rounds(4)
        assertFalse(TodoReminder.shouldRemind(history))
    }

    @Test
    fun reminderFiresAgainAfterTenMoreRounds() {
        val history = rounds(12) + listOf(reminder()) + rounds(11)
        assertTrue(TodoReminder.shouldRemind(history))
    }

    @Test
    fun reminderTextCarriesExistingItems() {
        val text = TodoReminder.reminderText("""[{"content":"fix bug","status":"in_progress","priority":"high"}]""")
        assertTrue(text.contains("Here are the existing contents of your todo list:"))
        assertTrue(text.contains("fix bug"))
    }
}
