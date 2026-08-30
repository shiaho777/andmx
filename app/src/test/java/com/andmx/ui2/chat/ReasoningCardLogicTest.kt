package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningCardLogicTest {

    @Test
    fun whileStreamingAndCollapsedTheLabelIsAlwaysThinking() {
        for (duration in listOf(null, 0, 1, 7, 999)) {
            assertEquals(
                "正在思考",
                reasoningTriggerLabel(isStreaming = true, isOpen = false, durationSec = duration),
            )
        }
    }

    @Test
    fun openingWhileStillStreamingSwitchesTheLabelToTheElapsedTime() {
        assertEquals(
            "思考 · 持续了 1 秒",
            reasoningTriggerLabel(isStreaming = true, isOpen = true, durationSec = 1),
        )
        assertEquals(
            "思考 · 持续了 42 秒",
            reasoningTriggerLabel(isStreaming = true, isOpen = true, durationSec = 42),
        )
    }

    @Test
    fun aDurationWeDoNotKnowYetFallsBackToTheVagueWording() {
        assertEquals(
            "思考 · 持续了几秒",
            reasoningTriggerLabel(isStreaming = true, isOpen = true, durationSec = null),
        )
        assertEquals(
            "思考 · 持续了几秒",
            reasoningTriggerLabel(isStreaming = false, isOpen = false, durationSec = null),
        )
    }

    @Test
    fun onceFinishedTheLabelCarriesTheDurationWhetherOrNotItIsOpen() {
        for (open in listOf(true, false)) {
            assertEquals(
                "思考 · 持续了 3 秒",
                reasoningTriggerLabel(isStreaming = false, isOpen = open, durationSec = 3),
            )
        }
    }

    @Test
    fun aSingleLineIsItsOwnTail() {
        assertEquals("正在核对文件路径", reasoningTailLine("正在核对文件路径"))
    }

    @Test
    fun theTailIsTheLastNonBlankLine() {
        assertEquals("第三行", reasoningTailLine("第一行\n第二行\n第三行"))
        assertEquals("第三行", reasoningTailLine("第一行\n第二行\n第三行\n"))
        assertEquals("第三行", reasoningTailLine("第一行\n\n第三行\n\n\n"))
    }

    @Test
    fun aWholeBufferOfNothingHasNoTail() {
        assertNull(reasoningTailLine(""))
        assertNull(reasoningTailLine("   "))
        assertNull(reasoningTailLine("\n\n\n"))
        assertNull(reasoningTailLine("  \n\t\n  "))
    }

    @Test
    fun carriageReturnsAreStrippedFromTheTail() {
        assertEquals("尾巴", reasoningTailLine("头\r\n尾巴\r\n"))
    }

    @Test
    fun aVeryLongLineIsTrimmedToItsTailRatherThanItsHead() {
        val line = "x".repeat(REASONING_TAIL_LIMIT + 50)
        val tail = reasoningTailLine(line)
        assertEquals(REASONING_TAIL_LIMIT, tail?.length)
        assertEquals("x".repeat(REASONING_TAIL_LIMIT), tail)
    }

    @Test
    fun aLineExactlyAtTheLimitKeepsEveryCharacter() {
        val line = "y".repeat(REASONING_TAIL_LIMIT)
        assertEquals(line, reasoningTailLine(line))
    }

    @Test
    fun growingABufferChunkByChunkAlwaysYieldsTheNewestLine() {
        val chunks = listOf("第一段。\n", "第二段仍在", "书写中。\n", "\n")
        var buffer = ""
        val seen = mutableListOf<String?>()
        for (chunk in chunks) {
            buffer += chunk
            seen += reasoningTailLine(buffer)
        }
        assertEquals(
            listOf("第一段。", "第二段仍在", "第二段仍在书写中。", "第二段仍在书写中。"),
            seen,
        )
    }
}
