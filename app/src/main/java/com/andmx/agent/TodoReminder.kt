package com.andmx.agent

import com.andmx.llm.ApiMessage

/**
 * ZCode-style TodoWrite nudge: after 10 assistant rounds without a TodoWrite
 * call and 10 rounds since the last reminder, inject the reminder once as a
 * system turn so long-running work re-adopts progress tracking.
 */
object TodoReminder {
    const val TURNS_SINCE_WRITE = 10
    const val TURNS_BETWEEN_REMINDERS = 10
    const val REMINDER_MARKER =
        "The TodoWrite tool hasn't been used recently."
    const val MARKER_OPEN = "<system-reminder>\n"

    fun shouldRemind(history: List<ApiMessage>): Boolean {
        var turns = 0
        var sinceWrite: Int? = null
        var sinceReminder: Int? = null
        for (index in history.indices.reversed()) {
            if (sinceWrite != null && sinceReminder != null) break
            val msg = history[index]
            if (sinceReminder == null && msg.role == "system" && msg.content?.contains(REMINDER_MARKER) == true) {
                sinceReminder = turns
                continue
            }
            if (msg.role != "assistant") continue
            if (sinceWrite == null && msg.toolCalls?.any { it.function.name == "TodoWrite" } == true) {
                sinceWrite = turns
            }
            turns++
        }
        val writeTurns = sinceWrite ?: turns
        val reminderTurns = sinceReminder ?: turns
        return writeTurns >= TURNS_SINCE_WRITE && reminderTurns >= TURNS_BETWEEN_REMINDERS
    }

    fun reminderText(existingItemsJson: String? = null): String = buildString {
        append(
            "The TodoWrite tool hasn't been used recently. If you're working on tasks that would " +
                "benefit from tracking progress, consider using the TodoWrite tool to track progress. " +
                "Also consider cleaning up the todo list if has become stale and no longer matches what " +
                "you are working on. Only use it if it's relevant to the current work. This is just a " +
                "gentle reminder - ignore if not applicable.",
        )
        if (!existingItemsJson.isNullOrBlank()) {
            append("\n\nHere are the existing contents of your todo list:\n\n")
            append(existingItemsJson)
        }
    }
}
