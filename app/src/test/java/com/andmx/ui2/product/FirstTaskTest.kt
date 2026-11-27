package com.andmx.ui2.product

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstTaskTest {
    @Test
    fun sendsOnlyTheSelectedFileAndInstructionWithoutTools() = runBlocking {
        val client = object : LlmApi {
            override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
                assertEquals("selected-model", request.model)
                assertNull(request.tools)
                assertEquals(listOf("system", "user"), request.messages.map { it.role })
                val message = Json.parseToJsonElement(checkNotNull(request.messages.last().content)).jsonObject
                assertEquals(setOf("instruction", "file"), message.keys)
                assertEquals("change status", message.getValue("instruction").jsonPrimitive.content)
                assertEquals(SAMPLE_BEFORE, message.getValue("file").jsonPrimitive.content)
                return Result.success(ApiMessage("assistant", "{\"content\":\"reviewed\"}"))
            }
        }
        assertEquals("reviewed", requestRevision(client, "selected-model", "change status", SAMPLE_BEFORE))
    }

    @Test
    fun rejectsTruncatedMalformedOrOversizedProposals() = runBlocking {
        val invalid = listOf(
            ApiMessage("assistant", "{\"content\":\"x\"}", finishReason = "length"),
            ApiMessage("assistant", "{\"content\":\"x\",\"extra\":true}"),
            ApiMessage("assistant", "{\"content\":2}"),
            ApiMessage("assistant", "{\"content\":\"${"x".repeat(FIRST_TASK_LIMIT + 1)}\"}"),
        )
        for (message in invalid) {
            val client = object : LlmApi {
                override suspend fun chat(request: ChatRequest) = Result.success(message)
            }
            assertTrue(runCatching { requestRevision(client, "model", "edit", SAMPLE_BEFORE) }.isFailure)
        }
    }
}
