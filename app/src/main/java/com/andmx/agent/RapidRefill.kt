package com.andmx.agent

object RapidRefill {
    const val TOOL_TURN_THRESHOLD = 3
    const val MAX_CONSECUTIVE = 3

    data class Decision(
        val consecutiveRapidRefills: Int,
        val shouldBlock: Boolean,
        val toolTurnsSinceCompact: Int,
    )

    fun evaluate(
        trackingActive: Boolean,
        consecutiveRapidRefills: Int,
        toolTurnsSinceCompact: Int,
    ): Decision {
        val next = if (trackingActive && toolTurnsSinceCompact < TOOL_TURN_THRESHOLD) {
            consecutiveRapidRefills + 1
        } else {
            0
        }
        return Decision(
            consecutiveRapidRefills = next,
            shouldBlock = next >= MAX_CONSECUTIVE,
            toolTurnsSinceCompact = toolTurnsSinceCompact,
        )
    }

    fun blockedMessage(): String =
        "Autocompact stopped because the context refilled within fewer than " +
            "$TOOL_TURN_THRESHOLD tool turns after compaction $MAX_CONSECUTIVE times in a row. " +
            "A file or tool output may be too large. Read it in smaller chunks, or start a new session."
}
