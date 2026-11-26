package com.andmx.llm

import android.content.Context
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

@Serializable
data class VideoAttachment(val id: String, val name: String, val mime: String, val bytes: Long)

object VideoAttachments {
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_REQUEST_BYTES = MAX_FILE_BYTES
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
    @Volatile private var legacyDirectory: File? = null

    fun initialize(context: Context) {
        directory = File(context.cacheDir, "video-attachments")
        legacyDirectory = File(context.filesDir, "video-attachments")
    }

    fun mimeFor(name: String, mime: String? = null): String? =
        mime?.lowercase()?.takeIf { it in supportedMimeTypes.values }
            ?: supportedMimeTypes[name.substringAfterLast('.', "").lowercase()]

    fun isVideo(name: String, mime: String?): Boolean =
        mime?.startsWith("video/", ignoreCase = true) == true || mimeFor(name, mime) != null

    fun save(context: Context, name: String, mime: String, input: InputStream): VideoAttachment {
        val root = File(context.cacheDir, "video-attachments")
        check(root.mkdirs() || root.isDirectory)
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
                    if (bytes > MAX_FILE_BYTES) throw TooLarge()
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

    class TooLarge : IOException()

    private fun fileFor(video: VideoAttachment): File? {
        if (!idPattern.matches(video.id) || video.mime !in supportedMimeTypes.values) return null
        if (video.bytes !in 1..MAX_FILE_BYTES) return null
        return listOfNotNull(directory, legacyDirectory).firstNotNullOfOrNull { root ->
            runCatching {
                val file = File(root, video.id)
                file.takeIf { it.isFile && it.length() == video.bytes && it.canonicalFile.parentFile == root.canonicalFile }
            }.getOrNull()
        }
    }

    fun isAvailable(video: VideoAttachment): Boolean = fileFor(video) != null

    fun discard(video: VideoAttachment) {
        fileFor(video)?.delete()
    }

    fun supportedBy(provider: ProviderDefinition, model: String): Boolean =
        provider.kind != ProviderKind.OPENAI_RESPONSES &&
            provider.models[model]?.inputModalities?.contains("video") == true

    internal fun project(
        request: ChatRequest,
        provider: ProviderDefinition,
        resolve: (VideoAttachment) -> String?,
    ): ChatRequest {
        if (request.messages.none { it.videos.isNotEmpty() }) return request
        val supportsVideo = supportedBy(provider, request.model)
        val start = request.videoStartIndex ?: request.messages.indexOfLast { it.role == "user" }.coerceAtLeast(0)
        var remaining = MAX_REQUEST_BYTES
        val payloads = request.messages.mapIndexed { index, message ->
            if (!supportsVideo || index < start || (message.role != "user" && message.role != "tool")) {
                return@mapIndexed emptyList()
            }
            message.videos.mapNotNull { video ->
                if (video.bytes !in 1..minOf(remaining, MAX_FILE_BYTES)) return@mapNotNull null
                resolve(video)?.also { remaining -= video.bytes }
            }
        }
        val messages = buildList {
            val toolVideos = mutableListOf<String>()
            request.messages.forEachIndexed { index, message ->
                val urls = payloads[index]
                val content = if (urls.size == message.videos.size) {
                    message.content
                } else {
                    val hint = when {
                        index < start -> "Previous video attachment: ${message.videos.joinToString { it.name }}"
                        !supportsVideo -> "This model or endpoint does not accept video attachments."
                        else -> "Video unavailable or exceeds the request size limit."
                    }
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

    internal fun requestBody(
        request: ChatRequest,
        provider: ProviderDefinition,
        encode: (ChatRequest) -> String,
    ): VideoRequestBody {
        val parts = mutableListOf<Pair<String, VideoRequestBody.Video>>()
        val projected = project(request, provider) { video ->
            val file = fileFor(video) ?: return@project null
            val marker = UUID.randomUUID().toString()
            parts += marker to VideoRequestBody.Video(file, video.bytes)
            "data:${video.mime};base64,$marker"
        }
        val json = encode(projected)
        val videos = parts.mapNotNull { (marker, video) ->
            json.indexOf(marker).takeIf { it >= 0 }?.let { Triple(it, marker.length, video) }
        }.sortedBy { it.first }
        var offset = 0
        val chunks = videos.map { (index, markerLength, video) ->
            val prefix = json.substring(offset, index).toByteArray()
            offset = index + markerLength
            prefix to video
        }
        return VideoRequestBody(chunks, json.substring(offset).toByteArray())
    }
}

internal class VideoRequestBody(
    private val chunks: List<Pair<ByteArray, Video>>,
    private val tail: ByteArray,
) {
    data class Video(val file: File, val bytes: Long)

    val streamingLength: Long? = chunks.takeIf { it.isNotEmpty() }?.sumOf { (prefix, video) ->
        prefix.size + ((video.bytes + 2) / 3) * 4
    }?.plus(tail.size)

    fun writeTo(output: OutputStream) {
        chunks.forEach { (prefix, video) ->
            output.write(prefix)
            val target = object : FilterOutputStream(output) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                override fun close() = Unit
            }
            video.file.inputStream().use { input ->
                Base64.getEncoder().wrap(target).use { encoded ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = video.bytes
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("Video attachment changed during upload")
                        encoded.write(buffer, 0, count)
                        remaining -= count
                    }
                    if (input.read() != -1) throw IOException("Video attachment changed during upload")
                }
            }
        }
        output.write(tail)
    }
}
