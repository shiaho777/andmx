package com.andmx.llm

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.wire.AdapterFactory
import com.andmx.ui2.chat.QueuedMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun keepsMostRecentVideosWithinRequestBudget() {
        val def = provider(ProviderKind.OPENAI, true)
        val large = clip.copy(bytes = VideoAttachments.MAX_FILE_BYTES)
        val input = request.copy(messages = listOf(
            ApiMessage("user", "old", videos = listOf(large)),
            ApiMessage("user", "new", videos = listOf(large)),
        ))
        val messages = VideoAttachments.project(input, def) { dataUrl }.messages
        assertTrue(messages.first().wireVideoUrls.isEmpty())
        assertTrue(messages.first().content.orEmpty().contains("request size limit"))
        assertEquals(listOf(dataUrl), messages.last().wireVideoUrls)
    }
}
