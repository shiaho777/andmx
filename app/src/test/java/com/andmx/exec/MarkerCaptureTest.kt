package com.andmx.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PTY read loop feeds output in arbitrary chunk sizes, so the sentinel it
 * watches for can land anywhere — including split across two reads. These cover
 * that boundary behaviour plus the bounded-capture guarantee.
 */
class MarkerCaptureTest {

    private val marker = "__ANDMX_EXIT_abc123_"

    private fun feed(capture: MarkerCapture, text: String): Boolean {
        val bytes = text.toByteArray()
        return capture.append(bytes, bytes.size)
    }

    /** Feed [text] one byte at a time — the worst case for boundary handling. */
    private fun feedByteWise(capture: MarkerCapture, text: String): Boolean {
        var hit = false
        for (b in text.toByteArray()) {
            if (capture.append(byteArrayOf(b), 1)) hit = true
        }
        return hit
    }

    @Test
    fun findsMarkerWithinASingleChunk() {
        val capture = MarkerCapture(marker)
        assertTrue(feed(capture, "hello\n${marker}0\n"))
        assertTrue(capture.found)
    }

    @Test
    fun doesNotReportMarkerThatNeverArrives() {
        val capture = MarkerCapture(marker)
        assertFalse(feed(capture, "hello world\nno sentinel here\n"))
        assertFalse(capture.found)
        assertEquals("hello world\nno sentinel here\n", capture.text())
    }

    @Test
    fun findsMarkerSplitAcrossTwoChunks() {
        val capture = MarkerCapture(marker)
        val head = marker.substring(0, 8)
        val tail = marker.substring(8)
        assertFalse(feed(capture, "output\n$head"))
        assertTrue(feed(capture, "${tail}0\n"))
        assertTrue(capture.found)
    }

    @Test
    fun findsMarkerSplitOneByteAtATime() {
        val capture = MarkerCapture(marker)
        assertTrue(feedByteWise(capture, "streamed output\n${marker}0\n"))
        assertTrue(capture.found)
    }

    @Test
    fun ignoresAlmostMatchesThatResetMidway() {
        val capture = MarkerCapture(marker)
        // A prefix that starts like the marker but diverges must not trigger.
        assertFalse(feed(capture, "__ANDMX_EXIT_abc999_ not it\n"))
        assertFalse(capture.found)
        // The real marker still registers afterwards.
        assertTrue(feed(capture, "${marker}0\n"))
    }

    @Test
    fun preservesOutputExactlyWhenUnderTheCaptureLimit() {
        val capture = MarkerCapture(marker)
        val body = (1..200).joinToString("\n") { "line $it" }
        feed(capture, body)
        assertEquals(body, capture.text())
    }

    @Test
    fun boundsCaptureAndKeepsHeadAndTail() {
        val capture = MarkerCapture(marker, maxCapture = 1000, tailKeep = 100)
        feed(capture, "HEAD_SENTINEL")
        feed(capture, "x".repeat(50_000))
        feed(capture, "TAIL_SENTINEL")

        val text = capture.text()
        // Far smaller than the 50KB fed in: the middle is dropped, not retained.
        assertTrue("captured ${text.length} chars, expected bounded", text.length < 3_000)
        assertTrue("head must survive", text.startsWith("HEAD_SENTINEL"))
        assertTrue("tail must survive", text.endsWith("TAIL_SENTINEL"))
        assertTrue("truncation must be disclosed", text.contains("已省略"))
    }

    @Test
    fun stillDetectsMarkerAfterOverflow() {
        val capture = MarkerCapture(marker, maxCapture = 500, tailKeep = 50)
        feed(capture, "y".repeat(10_000))
        assertFalse(capture.found)
        assertTrue(feed(capture, "${marker}0\n"))
        assertTrue(capture.found)
    }

    @Test
    fun emptyAppendIsANoOp() {
        val capture = MarkerCapture(marker)
        assertFalse(capture.append(ByteArray(0), 0))
        assertFalse(capture.append(ByteArray(16), 0))
        assertEquals("", capture.text())
    }

    @Test
    fun honoursLengthRatherThanArraySize() {
        val capture = MarkerCapture(marker)
        val buf = "abcDISCARDED".toByteArray()
        capture.append(buf, 3)
        assertEquals("abc", capture.text())
    }

    @Test
    fun multiByteUtf8SurvivesChunking() {
        val capture = MarkerCapture(marker)
        val text = "编译成功,输出正常\n"
        feedByteWise(capture, text)
        assertEquals(text, capture.text())
    }
}
