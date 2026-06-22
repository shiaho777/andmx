package com.andmx.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiMessageSerializerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun plainTextSerializesAsString() {
        val out = json.encodeToString(ApiMessage.serializer(), ApiMessage(role = "user", content = "hi"))
        assertTrue(out.contains("\"role\":\"user\""))
        assertTrue(out.contains("\"content\":\"hi\""))
    }

    @Test
    fun imagesSerializeAsContentArray() {
        val msg = ApiMessage(role = "user", content = "look", imageUrls = listOf("data:image/png;base64,AAA"))
        val out = json.encodeToString(ApiMessage.serializer(), msg)
        assertTrue(out.contains("\"type\":\"text\""))
        assertTrue(out.contains("\"type\":\"image_url\""))
        assertTrue(out.contains("data:image/png;base64,AAA"))
    }

    @Test
    fun toolCallsRoundTripField() {
        val msg = ApiMessage(
            role = "assistant",
            toolCalls = listOf(ApiToolCall(id = "c1", function = ApiFunctionCall("run_shell", "{\"command\":\"ls\"}"))),
        )
        val out = json.encodeToString(ApiMessage.serializer(), msg)
        assertTrue(out.contains("\"tool_calls\""))
        assertTrue(out.contains("run_shell"))
    }

    @Test
    fun deserializeStringContent() {
        val msg = json.decodeFromString(ApiMessage.serializer(), "{\"role\":\"assistant\",\"content\":\"hello\"}")
        assertEquals("assistant", msg.role)
        assertEquals("hello", msg.content)
    }
}
