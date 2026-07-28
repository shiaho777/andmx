package com.andmx.exec

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Accumulates PTY output while watching for a sentinel [marker], without
 * rescanning what it has already seen.
 *
 * Two properties matter and neither is obvious from the call site, which is why
 * this is a separate, testable unit rather than a loop body:
 *
 * 1. **The marker may straddle a read boundary.** Only the trailing
 *    `marker.length - 1` bytes of each chunk can begin an unfinished match, so
 *    that suffix is carried into the next scan. Each byte is therefore examined
 *    a bounded number of times instead of the whole buffer being re-searched per
 *    read.
 * 2. **Output is bounded.** A runaway command must not exhaust the heap, but the
 *    tail is the interesting part (that's where errors and the marker live), so
 *    the first [maxCapture] bytes and the last [tailKeep] bytes are kept and the
 *    middle is dropped with an explicit note.
 */
internal class MarkerCapture(
    marker: String,
    private val maxCapture: Int = 256 * 1024,
    private val tailKeep: Int = 4096,
    initialCapacity: Int = 8192,
) {
    private val markerBytes: ByteArray = marker.toByteArray(StandardCharsets.UTF_8)
    private val head = ByteArrayOutputStream(initialCapacity)
    private var tail: ByteArrayOutputStream? = null
    private var droppedToTail = 0L

    /** Bytes retained from a previous chunk that could start a split marker. */
    private val carry = ByteArray(markerBytes.size.coerceAtLeast(1))
    private var carryLen = 0

    /** True once [marker] has been observed in the stream. */
    var found: Boolean = false
        private set

    /**
     * Absorb [length] bytes from [chunk]. Returns true once the marker has been
     * seen, so callers can stop reading.
     */
    fun append(chunk: ByteArray, length: Int): Boolean {
        if (length <= 0) return found
        capture(chunk, length)

        // Scan carry + chunk together so a marker split across reads is found.
        val scanLen = carryLen + length
        val scan = ByteArray(scanLen)
        System.arraycopy(carry, 0, scan, 0, carryLen)
        System.arraycopy(chunk, 0, scan, carryLen, length)
        if (indexOf(scan, scanLen, markerBytes) >= 0) {
            found = true
            return true
        }

        val keep = (markerBytes.size - 1).coerceAtMost(scanLen).coerceAtLeast(0)
        System.arraycopy(scan, scanLen - keep, carry, 0, keep)
        carryLen = keep
        return false
    }

    /** The captured output, with an explicit note if the middle was dropped. */
    fun text(): String {
        val prefix = String(head.toByteArray(), StandardCharsets.UTF_8)
        val overflow = tail ?: return prefix
        val suffixBytes = overflow.toByteArray()
        val omitted = droppedToTail - suffixBytes.size
        val suffix = String(suffixBytes, StandardCharsets.UTF_8)
        if (omitted <= 0L) return prefix + suffix
        return prefix + "\n…[输出过长,已省略 $omitted 字节]…\n" + suffix
    }

    private fun capture(chunk: ByteArray, length: Int) {
        val room = (maxCapture - head.size()).coerceAtLeast(0)
        if (room > 0) head.write(chunk, 0, length.coerceAtMost(room))
        if (length <= room) return

        val overflow = length - room
        val sink = tail ?: ByteArrayOutputStream(tailKeep * 2).also { tail = it }
        sink.write(chunk, room, overflow)
        droppedToTail += overflow
        if (sink.size() > tailKeep * 2) {
            val kept = sink.toByteArray().copyOfRange(sink.size() - tailKeep, sink.size())
            sink.reset()
            sink.write(kept, 0, kept.size)
        }
    }

    private fun indexOf(data: ByteArray, size: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        val first = needle[0]
        val last = size - needle.size
        var i = 0
        while (i <= last) {
            if (data[i] == first) {
                var j = 1
                while (j < needle.size && data[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }
}
