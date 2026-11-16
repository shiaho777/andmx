package com.andmx.ui2.markdown

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object MarkdownImageLoader {
    private const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024
    private const val DISK_LIMIT_BYTES = 50L * 1024 * 1024
    private const val MEMORY_LIMIT_BYTES = 32 * 1024 * 1024
    private const val MAX_EDGE = 2048

    private val memoryCache = object : LruCache<String, ImageBitmap>(MEMORY_LIMIT_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width.toLong() * value.height * 4).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    suspend fun load(context: Context, url: String): ImageBitmap? = runCatching {
        val target = normalize(url) ?: return@runCatching null
        if (!MarkdownImages.isSupportedUrl(target)) return@runCatching null
        memoryCache.get(target)?.let { return@runCatching it }
        val deferred = CompletableDeferred<ImageBitmap?>()
        val existing = inFlight.putIfAbsent(target, deferred)
        if (existing != null) return@runCatching existing.await()
        try {
            val bitmap = withContext(Dispatchers.IO) { loadBlocking(context.applicationContext, target) }
            if (bitmap != null) memoryCache.put(target, bitmap)
            deferred.complete(bitmap)
            bitmap
        } catch (t: Throwable) {
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(target, deferred)
        }
    }.getOrNull()

    private fun normalize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.contains("://")) trimmed else "file://" + trimmed
    }

    private fun loadBlocking(context: Context, target: String): ImageBitmap? {
        val bytes: ByteArray = when {
            target.startsWith("http://") || target.startsWith("https://") -> {
                val dir = cacheDir(context) ?: return null
                val file = File(dir, MarkdownImages.cacheKey(target))
                val cached = readCacheFile(file)
                if (cached != null) cached else {
                    val downloaded = download(target) ?: return null
                    if (downloaded.size <= MAX_DOWNLOAD_BYTES) {
                        runCatching {
                            FileOutputStream(file).use { it.write(downloaded) }
                            evictDisk(dir)
                        }
                    }
                    downloaded
                }
            }
            target.startsWith("file://") -> {
                val file = regularFile(target.removePrefix("file://")) ?: return null
                if (file.length() > MAX_DOWNLOAD_BYTES) return null
                file.readBytes()
            }
            else -> {
                val file = regularFile(target) ?: return null
                if (file.length() > MAX_DOWNLOAD_BYTES) return null
                file.readBytes()
            }
        }
        return decode(bytes)
    }

    private fun regularFile(path: String): File? = runCatching {
        val file = File(path)
        if (file.isFile) file else null
    }.getOrNull()

    private fun cacheDir(context: Context): File? = runCatching {
        val dir = File(context.filesDir, "cache/md-images")
        if (dir.isDirectory || dir.mkdirs()) dir else null
    }.getOrNull()

    private fun readCacheFile(file: File): ByteArray? = runCatching {
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_DOWNLOAD_BYTES) return@runCatching null
        file.setLastModified(System.currentTimeMillis())
        file.readBytes()
    }.getOrNull()

    private fun evictDisk(dir: File) = runCatching {
        val files = dir.listFiles()?.filter { it.isFile } ?: return@runCatching
        val entries = files.map {
            MarkdownImages.CacheEntry(it.name, it.length(), it.lastModified())
        }
        MarkdownImages.evictOldest(entries, DISK_LIMIT_BYTES).forEach { name ->
            File(dir, name).delete()
        }
    }

    private fun download(url: String): ByteArray? = runCatching {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
            }
            try {
                when (conn.responseCode) {
                    in 300..399 -> {
                        val location = conn.getHeaderField("Location") ?: return@runCatching null
                        current = URL(URL(current), location).toString()
                        val scheme = URL(current).protocol.lowercase()
                        if (scheme != "http" && scheme != "https") return@runCatching null
                    }
                    200 -> {
                        if (conn.contentLengthLong > MAX_DOWNLOAD_BYTES) return@runCatching null
                        return@runCatching conn.inputStream.use { readLimited(it) }
                    }
                    else -> return@runCatching null
                }
            } finally {
                conn.disconnect()
            }
        }
        null
    }.getOrNull()

    private fun readLimited(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_DOWNLOAD_BYTES) throw IOException("image exceeds size limit")
            out.write(buf, 0, n)
        }
        return if (total == 0L) null else out.toByteArray()
    }

    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
            inSampleSize = MarkdownImages.sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}
