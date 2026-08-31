package com.andmx.ui2.files

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDragBusTest {

    private val payload = FileDragBus.Payload(
        kind = FileDragBus.Kind.FILE,
        name = "Main.kt",
        path = "/sdcard/project/app/src/Main.kt",
        relativePath = "app/src/Main.kt",
    )

    @Test
    fun initialStateIsIdle() {
        assertFalse(FileDragBus.state.value.dragging)
        assertNull(FileDragBus.state.value.payload)
        assertFalse(FileDragBus.state.value.overComposer)
    }

    @Test
    fun startMarksDraggingWithPayload() {
        FileDragBus.start(payload, Offset(10f, 20f))
        try {
            val s = FileDragBus.state.value
            assertTrue(s.dragging)
            assertEquals(payload, s.payload)
            assertEquals(Offset(10f, 20f), s.position)
            assertFalse(s.overComposer)
        } finally {
            FileDragBus.cancel()
        }
    }

    @Test
    fun updateTracksPositionAndComposerHover() {
        FileDragBus.start(payload, Offset(0f, 0f))
        try {
            FileDragBus.update(Offset(30f, 40f), overComposer = true)
            val s = FileDragBus.state.value
            assertEquals(Offset(30f, 40f), s.position)
            assertTrue(s.overComposer)
        } finally {
            FileDragBus.cancel()
        }
    }

    @Test
    fun updateWithoutActiveDragIsIgnored() {
        FileDragBus.update(Offset(1f, 1f), overComposer = true)
        assertFalse(FileDragBus.state.value.dragging)
        assertFalse(FileDragBus.state.value.overComposer)
    }

    @Test
    fun finishOverComposerReturnsPayload() {
        FileDragBus.start(payload, Offset(0f, 0f))
        FileDragBus.update(Offset(5f, 5f), overComposer = true)
        assertEquals(payload, FileDragBus.finish())
        assertFalse(FileDragBus.state.value.dragging)
    }

    @Test
    fun finishOutsideComposerReturnsNull() {
        FileDragBus.start(payload, Offset(0f, 0f))
        FileDragBus.update(Offset(5f, 5f), overComposer = false)
        assertNull(FileDragBus.finish())
        assertFalse(FileDragBus.state.value.dragging)
    }

    @Test
    fun cancelResetsState() {
        FileDragBus.start(payload, Offset(0f, 0f))
        FileDragBus.cancel()
        assertFalse(FileDragBus.state.value.dragging)
        assertNull(FileDragBus.state.value.payload)
    }

    @Test
    fun directoryPayloadKeepsKind() {
        val dir = payload.copy(kind = FileDragBus.Kind.DIRECTORY, name = "src")
        FileDragBus.start(dir, Offset(0f, 0f))
        try {
            assertEquals(FileDragBus.Kind.DIRECTORY, FileDragBus.state.value.payload?.kind)
        } finally {
            FileDragBus.cancel()
        }
    }
}
