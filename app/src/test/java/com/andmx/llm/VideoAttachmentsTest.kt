package com.andmx.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.wire.AdapterFactory
import com.andmx.ui2.chat.QueuedMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoAttachmentsTest {
    private val clip = VideoAttachment("clip-1", "walkthrough.mp4", "video/mp4", 3)
    private val dataUrl = "data:video/mp4;base64,YWJj"
    private val request = ChatRequest(model = "test", messages = listOf(
        ApiMessage("user", "Look at the video", videos = listOf(clip)),
    ))

    private fun provider(kind: ProviderKind, video: Boolean) = ProviderDefinition(
        id = "test",
        name = "Test",
        kind = kind,
        baseUrl = "https://example.com",
        models = mapOf("test" to ModelDefinition(inputModalities = if (video) listOf("text", "video") else listOf("text"))),
    )

    @Test
    fun projectsOnlyForCompatibleModelsAndWireFormats() {
        val formats = mapOf(ProviderKind.OPENAI to "video_url", ProviderKind.ANTHROPIC to "video")
        formats.forEach { (kind, block) ->
            val def = provider(kind, video = true)
            val projected = VideoAttachments.project(request, def) { dataUrl }
            val wire = AdapterFactory.forKind(kind).encodeRequest(projected, def)
            assertTrue(wire.contains("\"type\":\"$block\""))
            assertTrue(wire.contains("YWJj"))
        }
        listOf(provider(ProviderKind.OPENAI, false), provider(ProviderKind.OPENAI_RESPONSES, true)).forEach { def ->
            val projected = VideoAttachments.project(request, def) { error("Unsupported video was read") }
            val wire = AdapterFactory.forKind(def.kind).encodeRequest(projected, def)
            assertFalse(wire.contains("YWJj"))
            assertTrue(wire.contains("does not accept video"))
        }
    }

    @Test
    fun toolMediaFollowsTheWholeToolResultBatch() {
        val def = provider(ProviderKind.OPENAI, true)
        val input = request.copy(messages = listOf(
            ApiMessage("tool", "first", toolCallId = "1", videos = listOf(clip)),
            ApiMessage("tool", "second", toolCallId = "2", videos = listOf(clip)),
        ))
        val messages = VideoAttachments.project(input, def) { dataUrl }.messages
        assertEquals(listOf("tool", "tool", "user"), messages.map { it.role })
        assertEquals(listOf(dataUrl, dataUrl), messages.last().wireVideoUrls)
    }

    @Test
    fun restoresLegacyAndVideoQueueEntries() {
        val queued = QueuedMessage("watch this", videos = listOf(clip))
        val encoded = Json.encodeToString(queued)
        assertEquals(listOf(QueuedMessage("legacy"), queued), QueuedMessage.decode("[\"legacy\",$encoded]"))
    }

    @Test
    fun replaysPreviousVideosAsTextWithoutOpeningTheirFiles() {
        val def = provider(ProviderKind.OPENAI, true)
        val old = clip.copy(id = "previous")
        val input = request.copy(messages = listOf(
            ApiMessage("user", "old", videos = listOf(old)),
            ApiMessage("assistant", "reviewed"),
            ApiMessage("user", "new", videos = listOf(clip)),
            ApiMessage("user", "continuation reminder"),
        ), videoStartIndex = 2)
        val messages = VideoAttachments.project(input, def) {
            assertEquals(clip.id, it.id)
            dataUrl
        }.messages
        assertTrue(messages.first().wireVideoUrls.isEmpty())
        assertTrue(messages.first().content.orEmpty().contains("Previous video attachment: walkthrough.mp4"))
        assertEquals(listOf(dataUrl), messages[2].wireVideoUrls)
    }

    @Test
    fun streamsBothWireFormatsWithTheCorrectContentLength() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VideoAttachments.initialize(context)
        val video = "abc".byteInputStream().use { VideoAttachments.save(context, "clip.mp4", "video/mp4", it) }
        try {
            val input = request.copy(messages = listOf(ApiMessage("user", "看视频", videos = listOf(video))))
            for (kind in listOf(ProviderKind.OPENAI, ProviderKind.ANTHROPIC)) {
                val def = provider(kind, true)
                val adapter = AdapterFactory.forKind(kind)
                val body = VideoAttachments.requestBody(input, def) { adapter.encodeRequest(it, def) }
                val output = ByteArrayOutputStream()
                body.writeTo(output)
                assertEquals(output.size().toLong(), body.streamingLength)
                assertEquals(adapter.encodeRequest(VideoAttachments.project(input, def) { dataUrl }, def), output.toString("UTF-8"))
            }
        } finally {
            VideoAttachments.discard(video)
        }
    }

    @Test
    fun uploadsAtTheLimitWithoutWritingAFullVideoBufferAndRejectsOversize() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VideoAttachments.initialize(context)
        val limit = VideoAttachments.MAX_FILE_BYTES
        val video = repeatingInput(limit).use { VideoAttachments.save(context, "clip.mp4", "video/mp4", it) }
        try {
            val def = provider(ProviderKind.OPENAI, true)
            val input = request.copy(messages = listOf(ApiMessage("user", "new", videos = listOf(video))))
            val body = VideoAttachments.requestBody(input, def) { AdapterFactory.forKind(def.kind).encodeRequest(it, def) }
            var received = 0L
            val output = object : OutputStream() {
                override fun write(value: Int) { received++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    assertTrue(length <= 16 * 1024)
                    received += length
                }
            }
            body.writeTo(output)
            assertEquals(limit, video.bytes)
            assertEquals(received, body.streamingLength)
            assertTrue(received > limit)
            assertThrows(VideoAttachments.TooLarge::class.java) {
                repeatingInput(limit + 1).use { VideoAttachments.save(context, "large.mp4", "video/mp4", it) }
            }
            assertEquals(1, context.cacheDir.resolve("video-attachments").listFiles()?.size)
        } finally {
            VideoAttachments.discard(video)
        }
    }

    @Test
    fun keepsCurrentUserVideoWithinTheSharedRequestBudget() {
        val def = provider(ProviderKind.OPENAI, true)
        val large = clip.copy(bytes = VideoAttachments.MAX_FILE_BYTES)
        val input = request.copy(messages = listOf(
            ApiMessage("user", "first", videos = listOf(large)),
            ApiMessage("tool", "more", toolCallId = "1", videos = listOf(clip)),
        ))
        val messages = VideoAttachments.project(input, def) { dataUrl }.messages
        assertEquals(listOf(dataUrl), messages.first().wireVideoUrls)
        assertTrue(messages.last().wireVideoUrls.isEmpty())
        assertTrue(messages.last().content.orEmpty().contains("request size limit"))
    }

    private fun repeatingInput(size: Long) = object : InputStream() {
        private var remaining = size

        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining--
            return 42
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            bytes.fill(42, offset, offset + count)
            remaining -= count
            return count
        }
    }
}
