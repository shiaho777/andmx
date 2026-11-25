package com.andmx.llm

import android.content.Context
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID

@Serializable
data class VideoAttachment(val id: String, val name: String, val mime: String, val bytes: Long)

object VideoAttachments {
    const val MAX_FILE_BYTES = 12L * 1024 * 1024
    private const val REQUEST_BYTES = 16L * 1024 * 1024
    private val supportedMimeTypes = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
    )
    private val idPattern = Regex("[a-f0-9-]{36}")
    @Volatile private var directory: File? = null

    fun initialize(context: Context) {
        directory = File(context.filesDir, "video-attachments")
    }

    fun mimeFor(name: String, mime: String? = null): String? =
        mime?.lowercase()?.takeIf { it in supportedMimeTypes.values }
            ?: supportedMimeTypes[name.substringAfterLast('.', "").lowercase()]

    fun save(context: Context, name: String, mime: String, input: InputStream): VideoAttachment {
        val root = File(context.filesDir, "video-attachments")
        check(root.isDirectory || root.mkdirs())
        val id = UUID.randomUUID().toString()
        val file = File(root, id)
        try {
            var bytes = 0L
            file.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= MAX_FILE_BYTES)
                    output.write(buffer, 0, count)
                }
            }
            require(bytes > 0)
            return VideoAttachment(id, name.substringAfterLast('/').take(160), mime, bytes)
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    fun decode(encoded: String): List<VideoAttachment> =
        runCatching { Json.decodeFromString<List<VideoAttachment>>(encoded) }.getOrDefault(emptyList())

    private fun dataUrl(video: VideoAttachment): String? {
        val root = directory ?: return null
        if (!idPattern.matches(video.id) || video.mime !in supportedMimeTypes.values) return null
        if (video.bytes !in 1..MAX_FILE_BYTES) return null
        return runCatching {
            val file = File(root, video.id)
            if (file.canonicalFile.parentFile != root.canonicalFile || file.length() != video.bytes) return null
            val bytes = ByteArray(video.bytes.toInt())
            java.io.DataInputStream(file.inputStream()).use { input ->
                input.readFully(bytes)
                if (input.read() != -1) return null
            }
            "data:${video.mime};base64,${Base64.getEncoder().encodeToString(bytes)}"
        }.getOrNull()
    }

    fun supportedBy(provider: ProviderDefinition, model: String): Boolean =
        provider.kind != ProviderKind.OPENAI_RESPONSES &&
            provider.models[model]?.inputModalities?.contains("video") == true

    internal fun project(
        request: ChatRequest,
        provider: ProviderDefinition,
        resolve: (VideoAttachment) -> String? = ::dataUrl,
    ): ChatRequest {
        if (request.messages.none { it.videos.isNotEmpty() }) return request
        val supportsVideo = supportedBy(provider, request.model)
        var remaining = REQUEST_BYTES
        val payloads = request.messages.asReversed().map { message ->
            if (!supportsVideo) return@map emptyList()
            message.videos.mapNotNull { video ->
                if (video.bytes !in 1..minOf(remaining, MAX_FILE_BYTES)) return@mapNotNull null
                resolve(video)?.also { remaining -= video.bytes }
            }
        }.asReversed()
        val messages = buildList {
            val toolVideos = mutableListOf<String>()
            request.messages.forEachIndexed { index, message ->
                val urls = payloads[index]
                val content = if (urls.size == message.videos.size) {
                    message.content
                } else {
                    val hint = if (supportsVideo) "Video unavailable or exceeds the request size limit."
                    else "This model or endpoint does not accept video attachments."
                    listOfNotNull(message.content, "[$hint]").joinToString("\n")
                }
                if (message.role == "tool") {
                    add(message.copy(content = content))
                    toolVideos += urls
                    if (toolVideos.isNotEmpty() && request.messages.getOrNull(index + 1)?.role != "tool") {
                        add(ApiMessage("user", "Video returned by the preceding tools.", wireVideoUrls = toolVideos.toList()))
                        toolVideos.clear()
                    }
                } else {
                    add(message.copy(content = content, wireVideoUrls = urls))
                }
            }
        }
        return request.copy(messages = messages)
    }
}
