package com.andmx.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ConversationTransfer(
    val format: String,
    val title: String,
    val messages: List<TransferMessage>,
) {
    companion object {
        private const val FORMAT = "andmx-conversation-v1"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_MESSAGES = 1000

        fun serialize(title: String, messages: List<MessageEntity>): String {
            val turns = messages.mapNotNull { message ->
                if (message.role in setOf("user", "assistant") && message.content.isNotBlank()) {
                    TransferMessage(message.role, message.content)
                } else null
            }
            val data = ConversationTransfer(FORMAT, title.take(200), turns)
            val encoded = Json.encodeToString(data)
            require(turns.size in 1..MAX_MESSAGES && encoded.toByteArray().size <= MAX_BYTES)
            return encoded
        }

        fun read(input: InputStream): ConversationTransfer {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(out.size() + size <= MAX_BYTES)
                out.write(buffer, 0, size)
            }
            val data = Json.decodeFromString<ConversationTransfer>(out.toString(Charsets.UTF_8.name()))
            require(data.format == FORMAT && data.title.length <= 200)
            require(data.messages.size in 1..MAX_MESSAGES)
            require(data.messages.all { it.role in setOf("user", "assistant") && it.text.isNotBlank() })
            return data
        }
    }
}

@Serializable
internal data class TransferMessage(val role: String, val text: String)
