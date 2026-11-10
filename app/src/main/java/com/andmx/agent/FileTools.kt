package com.andmx.agent

import android.content.Context
import com.andmx.workspace.ChangeTracker
import com.andmx.workspace.WorkspaceAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.wirePath(): String? = str("file_path") ?: str("path")

private fun JsonObject.requireWirePath(): ToolResult? {
    val raw = wirePath() ?: return ToolResult("file_path is required", isError = true)
    if (raw.isEmpty()) return ToolResult("Tool path must not be empty", isError = true)
    return null
}

internal fun invalidateWorkspaceIndex(
    context: Context,
    access: WorkspaceAccess,
    guestPath: String,
) {
    val host = runCatching { access.hostFile(guestPath) }.getOrNull() ?: return
    com.andmx.workspace.WorkspaceIndex.get(context)
        .invalidatePath(host.absolutePath, access.hostPath)
}

private fun JsonObject.semanticBool(key: String): Boolean {
    val prim = this[key] as? JsonPrimitive ?: return false
    prim.booleanOrNull?.let { return it }
    prim.longOrNull?.let { return it == 1L }
    return when (prim.content.trim().lowercase()) {
        "true", "1", "yes", "y", "on" -> true
        else -> false
    }
}

private fun toRangeOffsetLine(offset: Int?): Int {
    if (offset == null || offset <= 1) return 0
    return offset - 1
}

private fun formatWriteModelContent(filePath: String, created: Boolean, userModified: Boolean = false): String {
    val note = if (userModified) WRITE_USER_MODIFIED_NOTE else ""
    val suffix = if (userModified) "" else ReadTextFormatter.FRESHNESS_SUFFIX
    return if (created) {
        "File created successfully at: $filePath$note$suffix"
    } else {
        "The file $filePath has been updated successfully.$note$suffix"
    }
}

private fun formatEditModelContent(filePath: String, replaceAll: Boolean, userModified: Boolean = false): String {
    val modifiedNote = if (userModified) {
        ".  The user modified your proposed changes before accepting them. "
    } else {
        ""
    }
    val freshness = if (userModified) "" else ReadTextFormatter.FRESHNESS_SUFFIX
    return if (replaceAll) {
        "The file $filePath has been updated$modifiedNote. All occurrences were successfully replaced.$freshness"
    } else {
        "The file $filePath has been updated successfully$modifiedNote.$freshness"
    }
}

private fun ambiguousEditMessage(matchCount: Int, oldString: String): String {
    if (matchCount > 0) {
        return "Found $matchCount matches of the string to replace, but replace_all is false. " +
            "To replace all occurrences, set replace_all to true. " +
            "To replace only one occurrence, please provide more context to uniquely identify the instance.\n" +
            "String: $oldString"
    }
    return "old_string is not unique in the file. Provide more surrounding context or set replace_all to true."
}

private suspend fun missingPathMessage(access: WorkspaceAccess, resolved: String): String {
    val name = resolved.substringAfterLast('/')
    val parent = resolved.substringBeforeLast('/').ifBlank { "/" }
    val suggestion = runCatching { ReadPathHint.suggest(access.list(parent), name) }.getOrNull()
    val cwd = runCatching { access.guestCwd() }.getOrDefault("/")
    val hint = if (suggestion != null) " Did you mean $suggestion?" else ""
    return "File does not exist. Note: your current working directory is $cwd.$hint"
}

private const val WRITE_USER_MODIFIED_NOTE = " The user modified your proposed content before accepting it."
private const val EDIT_NOT_READ = "File has not been read yet. Read it first before writing to it."
private const val EDIT_STALE =
    "File has been modified since read, either by the user or by a linter. Read it again before attempting to write it."
private const val MAX_EDIT_FILE_SIZE_BYTES = 1024L * 1024L * 1024L

private val READ_PROVIDER_DESCRIPTION = listOf(
    "Reads a file from the local filesystem.",
    "",
    "- `file_path` must be an absolute path.",
    "- Reads up to 2000 lines by default.",
    "- When you already know which part of the file you need, only read that part. This can be important for larger files.",
    "- Results are returned using cat -n format, with line numbers starting at 1",
    "- Reads images (PNG, JPG, …) and presents them visually.",
    "- Reads videos (MP4, MOV, WEBM, …). This Android build has no video input block, so videos are sampled as frames. That is a substitution, not the upstream video block.",
    "- Reading a directory, a missing file, or an empty file returns an error or system reminder rather than content.",
    "- Do NOT re-read a file you just edited to verify — Edit/Write would have errored if the change failed, and the harness tracks file state for you.",
).joinToString("\n")

private val WRITE_PROVIDER_DESCRIPTION = listOf(
    "Writes a file to the local filesystem, overwriting if one exists.",
    "",
    "When to use: creating a new file, or fully replacing one you've already Read. Overwriting an existing file you haven't Read will fail. For partial changes, use Edit instead.",
).joinToString("\n")

private val EDIT_PROVIDER_DESCRIPTION = listOf(
    "Performs exact string replacement in a file.",
    "",
    "- You must Read the file in this conversation before editing, or the call will fail.",
    "- `old_string` must match the file exactly, including indentation, and be unique — the edit fails otherwise. Strip the Read line prefix (line number + tab) before matching.",
    "- `replace_all: true` replaces every occurrence instead.",
).joinToString("\n")

class ReadFileTool(context: Context, private val readFileState: ReadFileState? = null) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "Read"
    override val description = READ_PROVIDER_DESCRIPTION
    override val risk = ToolRisk.READ
    override val timeoutMs: Long = 30_000
    override val maxOutputBytes: Int = ReadTextFormatter.MAX_FILE_BYTES
    override val permissionName: String = "read"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "The absolute path to the file to read")
            }
            putJsonObject("offset") {
                put("type", "integer")
                put("description", "The line number to start reading from. Only provide if the file is too large to read at once")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "The number of lines to read. Only provide if the file is too large to read at once.")
            }
        }
        putJsonArray("required") { add("file_path") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        args.requireWirePath()?.let { return it }
        val path = args.wirePath()!!
        val rawOffset = args["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if ("offset" in args && (rawOffset == null || rawOffset < 0)) {
            return ToolResult("offset must be a nonnegative integer", isError = true)
        }
        val offset = toRangeOffsetLine(rawOffset)
        val rawLimit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if ("limit" in args && (rawLimit == null || rawLimit < 1)) {
            return ToolResult("limit must be a positive integer", isError = true)
        }
        val limit = rawLimit?.coerceAtMost(MAX_LINE_LIMIT)
        val resolved = access.resolvePath(path)
        if (!access.exists(resolved)) {
            return ToolResult(missingPathMessage(access, resolved), isError = true)
        }
        if (access.isDirectory(resolved)) {
            return ToolResult("Error: $resolved is a directory, not a file.", isError = true)
        }
        val ext = resolved.substringAfterLast('.', "").lowercase()
        // 上游 read-image：图片经 image content 通道返回给模型。
        if (ext in IMAGE_EXTS) {
            val bytes = access.readBytes(resolved, 20 * 1024 * 1024)
                ?: return ToolResult("读取图片失败或过大: $resolved", isError = true)
            // 上游 read-image：限 2000px 边长、base64 ≤ ~5MB，超限先降采样。
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) {
                return ToolResult("无法解码图片: $resolved", isError = true)
            }
            val needsScale = bounds.outWidth > IMAGE_MAX_DIM || bounds.outHeight > IMAGE_MAX_DIM ||
                bytes.size > IMAGE_RAW_MAX_BYTES
            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
                "webp" -> "image/webp"; "bmp" -> "image/bmp"; else -> "image/png"
            }
            if (!needsScale) {
                val dataUrl = "data:$mime;base64," +
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                return ToolResult(
                    "[image ${resolved.substringAfterLast('/')} ${bounds.outWidth}x${bounds.outHeight}]",
                    imageUrls = listOf(dataUrl),
                )
            }
            var sample = 1
            while (bounds.outWidth / sample > IMAGE_MAX_DIM || bounds.outHeight / sample > IMAGE_MAX_DIM) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return ToolResult("无法解码图片: $resolved", isError = true)
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
            bmp.recycle()
            val dataUrl = "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            return ToolResult(
                "[image ${resolved.substringAfterLast('/')} ${bounds.outWidth}x${bounds.outHeight} · 已降采样]",
                imageUrls = listOf(dataUrl),
            )
        }
        // PDF → PdfRenderer 逐页渲染为 PNG（本地访客路径；远端不支持）。
        if (ext == "pdf") {
            return readPdf(resolved, args["pages"]?.jsonPrimitive?.contentOrNull)
        }
        // 视频 → MediaMetadataRetriever 抽帧（上游 read-video 等价；无 provider
        // video block，用 3 帧截图代替）。
        if (ext in VIDEO_EXTS) {
            return readVideo(resolved)
        }
        return readTextFile(path, resolved, offset, limit)
    }

    private suspend fun readTextFile(path: String, resolved: String, offset: Int, limit: Int?): ToolResult {
        val mtime = access.statMtime(resolved)
        val size = access.statSize(resolved)
        val full = offset == 0 && limit == null
        if (full && readFileState?.unchangedFullRead(resolved, mtime, size) == true) {
            return ToolResult(ReadTextFormatter.UNCHANGED)
        }
        if (size != null && size > ReadTextFormatter.MAX_FILE_BYTES) {
            if (!full) {
                val host = access.hostFile(resolved)
                if (host != null) {
                    val windowLimit = limit ?: DEFAULT_LINE_LIMIT
                    val (slice, total) = readHostWindow(host, offset, windowLimit)
                    val view = ReadTextFormatter.formatWindow(slice, total, offset)
                    rememberText(resolved, view, offset, mtime, size)
                    return ToolResult(view.output, isError = view.isError)
                }
            }
            return ToolResult(ReadTextFormatter.tooLarge(size), isError = true)
        }
        return runCatching {
            val text = access.readText(path)
            val view = ReadTextFormatter.format(text, offset, limit)
            rememberText(resolved, view, offset, mtime, size ?: text.toByteArray(Charsets.UTF_8).size.toLong())
            ToolResult(view.output, isError = view.isError)
        }.getOrElse { ToolResult("读取失败: ${it.message}", isError = true) }
    }

    private fun rememberText(
        resolved: String,
        view: ReadTextFormatter.View,
        offset: Int,
        mtime: Long?,
        size: Long?,
    ) {
        if (view.isError || view.output == ReadTextFormatter.UNCHANGED) return
        if (view.partial && view.stored.isEmpty()) return
        if (view.partial) {
            readFileState?.record(
                resolved,
                view.stored,
                offset = offset,
                limit = view.stored.lineSequence().count().coerceAtLeast(1),
                mtimeMs = mtime,
                sizeBytes = size,
            )
        } else {
            readFileState?.record(resolved, view.stored, mtimeMs = mtime, sizeBytes = size)
        }
    }

    private fun readHostWindow(file: java.io.File, offset: Int, limit: Int): Pair<List<String>, Int> {
        val out = ArrayList<String>(limit.coerceAtMost(4096))
        var total = 0
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (total >= offset && out.size < limit) out += line
                total++
            }
        }
        return out to total
    }

    /**
     * 本地 PDF 逐页渲染（android.graphics.pdf.PdfRenderer，无三方依赖）。
     * `pages` 对齐上游契约："1-5" / "3" / "10-20"，1-indexed，单次 ≤20 页。
     */
    private fun readPdf(resolved: String, pagesSpec: String?): ToolResult {
        val host = access.hostFile(resolved)
            ?: return ToolResult("远端工作区暂不支持 PDF 读取", isError = true)
        if (!host.exists()) return ToolResult("文件不存在: $resolved", isError = true)
        return runCatching {
            val pfd = android.os.ParcelFileDescriptor.open(host, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val totalPages = renderer.pageCount
            val pageIndices = parsePdfPages(pagesSpec, totalPages)
                ?: return@runCatching ToolResult(
                    "Invalid pages parameter: \"$pagesSpec\". " +
                        "Use formats like \"1-5\", \"3\", or \"10-20\". Pages are 1-indexed.",
                    isError = true,
                )
            val images = mutableListOf<String>()
            try {
                for (i in pageIndices) {
                    val page = renderer.openPage(i)
                    val scale = 1024f / page.width.coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, bos)
                    images += "data:image/png;base64," +
                        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                    bmp.recycle()
                }
            } finally {
                renderer.close(); pfd.close()
            }
            ToolResult(
                "[pdf ${resolved.substringAfterLast('/')} · $totalPages 页, 渲染第 ${pageIndices.first() + 1}-${pageIndices.last() + 1} 页]",
                imageUrls = images,
            )
        }.getOrElse { ToolResult("PDF 读取失败: ${it.message}", isError = true) }
    }

    /** 上游 read-pdf 页选："1-5"/"3"/"10-20" → 0-indexed 页码表，越界收敛到 totalPages。 */
    private fun parsePdfPages(spec: String?, totalPages: Int): List<Int>? {
        if (spec.isNullOrBlank()) return (0 until totalPages.coerceAtMost(MAX_PDF_PAGES)).toList()
        val s = spec.trim()
        val m = Regex("""^(\d+)(?:-(\d+))?$""").matchEntire(s) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: a
        if (a < 1 || b < a) return null
        val lo = a - 1
        val hi = (b - 1).coerceAtMost(totalPages - 1)
        if (lo > hi) return null
        return (lo..hi).toList().take(MAX_PDF_PAGES)
    }

    /** 本地视频抽 3 帧（头/中/尾）为 PNG——上游 read-video 的移动端等价。 */
    private fun readVideo(resolved: String): ToolResult {
        val host = access.hostFile(resolved)
            ?: return ToolResult("远端工作区暂不支持视频读取", isError = true)
        if (!host.exists()) return ToolResult("文件不存在: $resolved", isError = true)
        return runCatching {
            val mmr = android.media.MediaMetadataRetriever()
            val images = mutableListOf<String>()
            try {
                mmr.setDataSource(host.absolutePath)
                val durMs = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull() ?: 0L
                for (t in listOf(0L, durMs / 2, (durMs - 100).coerceAtLeast(0))) {
                    val bmp = mmr.getFrameAtTime(
                        t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ) ?: continue
                    val scale = minOf(1f, 1024f / bmp.width.coerceAtLeast(1))
                    val scaled = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true,
                    ) else bmp
                    val bos = java.io.ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
                    images += "data:image/jpeg;base64," +
                        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                    if (scaled !== bmp) bmp.recycle()
                    scaled.recycle()
                }
            } finally {
                runCatching { mmr.release() }
            }
            if (images.isEmpty()) return ToolResult("视频抽帧失败: $resolved", isError = true)
            ToolResult(
                "[video ${resolved.substringAfterLast('/')} · ${images.size} 帧]",
                imageUrls = images,
            )
        }.getOrElse { ToolResult("视频读取失败: ${it.message}", isError = true) }
    }

    companion object {
        const val DEFAULT_LINE_LIMIT = 2000
        const val MAX_LINE_LIMIT = 5000
        const val MAX_PDF_PAGES = 20
        const val IMAGE_MAX_DIM = 2000
        const val IMAGE_RAW_MAX_BYTES = 4 * 1024 * 1024
        val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        val VIDEO_EXTS = setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v")
    }
}

class WriteFileTool(
    context: Context,
    private val readFileState: ReadFileState? = null,
    private val conversationId: Long = 0L,
) : Tool {
    private val access = WorkspaceAccess(context)
    private val appContext = context.applicationContext
    override val name = "Write"
    override val description = WRITE_PROVIDER_DESCRIPTION
    override val risk = ToolRisk.WRITE
    override val timeoutMs: Long = 30_000
    override val maxOutputBytes: Int = 1_000_000
    override val permissionName: String = "edit"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "The absolute path to the file to write (must be absolute, not relative)")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "The content to write to the file")
            }
        }
        putJsonArray("required") { add("file_path"); add("content") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        args.requireWirePath()?.let { return it }
        val path = args.wirePath()!!
        val content = args.str("content") ?: return ToolResult("content is required", isError = true)
        return runCatching {
            val resolved = access.resolvePath(path)
            val existed = access.exists(resolved)
            if (existed && readFileState != null) {
                val mtime = access.statMtime(resolved)
                val size = access.readBytes(resolved, 32 * 1024 * 1024)?.size?.toLong()
                when (val stale = readFileState.staleness(resolved, mtime, size)) {
                    "未读取" -> return@runCatching ToolResult(EDIT_NOT_READ, isError = true)
                    null -> {}
                    else -> return@runCatching ToolResult(EDIT_STALE, isError = true)
                }
            }
            val old = if (existed) access.readText(resolved) else ""
            access.writeText(resolved, content)
            ChangeTracker.record(conversationId, resolved, old, content, existedBefore = existed)
            invalidateWorkspaceIndex(appContext, access, resolved)
            rememberWritten(resolved, content, ReadFileState.WRITE_TOOL)
            ToolResult(formatWriteModelContent(path, created = !existed))
        }.getOrElse { ToolResult("写入失败: ${it.message}", isError = true) }
    }

    private suspend fun rememberWritten(path: String, content: String, source: String) {
        val mtime = access.statMtime(path)
        val size = access.statSize(path) ?: content.toByteArray(Charsets.UTF_8).size.toLong()
        readFileState?.record(path, content, sourceTool = source, mtimeMs = mtime, sizeBytes = size)
    }
}

class EditFileTool(
    context: Context,
    private val readFileState: ReadFileState? = null,
    private val conversationId: Long = 0L,
) : Tool {
    private val access = WorkspaceAccess(context)
    private val appContext = context.applicationContext
    override val name = "Edit"
    override val description = EDIT_PROVIDER_DESCRIPTION
    override val risk = ToolRisk.WRITE
    override val timeoutMs: Long = 30_000
    override val maxOutputBytes: Int = 1_000_000
    override val permissionName: String = "edit"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "The absolute path to the file to modify")
            }
            putJsonObject("old_string") {
                put("type", "string")
                put("description", "The text to replace")
            }
            putJsonObject("new_string") {
                put("type", "string")
                put("description", "The text to replace it with (must be different from old_string)")
            }
            putJsonObject("replace_all") {
                put("type", "boolean")
                put("description", "Replace all occurrences of old_string (default false)")
                put("default", false)
            }
        }
        putJsonArray("required") { add("file_path"); add("old_string"); add("new_string") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        args.requireWirePath()?.let { return it }
        val path = args.wirePath()!!
        val oldStr = args.str("old_string") ?: args.str("old_str")
            ?: return ToolResult("old_string is required", isError = true)
        val newStr = args.str("new_string") ?: args.str("new_str")
            ?: return ToolResult("new_string is required", isError = true)
        val replaceAll = args.semanticBool("replace_all")
        if (oldStr == newStr) {
            return ToolResult(
                "No changes to make: old_string and new_string are exactly the same.",
                isError = true,
            )
        }
        return runCatching {
            val resolved = access.resolvePath(path)
            if (!access.exists(resolved)) {
                if (oldStr.isEmpty()) {
                    access.writeText(resolved, newStr)
                    ChangeTracker.record(conversationId, resolved, "", newStr, existedBefore = false)
                    invalidateWorkspaceIndex(appContext, access, resolved)
                    rememberWritten(resolved, newStr, "edit_file")
                    return@runCatching ToolResult(formatEditModelContent(path, replaceAll))
                }
                return@runCatching ToolResult(missingPathMessage(access, resolved), isError = true)
            }
            val size = access.statSize(resolved)
            if (size != null && size > MAX_EDIT_FILE_SIZE_BYTES) {
                return@runCatching ToolResult(
                    "File is too large to edit (1GB). Maximum editable file size is 1GB.",
                    isError = true,
                )
            }
            val original = access.readText(resolved)
            if (oldStr.isEmpty()) {
                if (original.trim().isNotEmpty()) {
                    return@runCatching ToolResult(
                        "Cannot create new file - file already exists.",
                        isError = true,
                    )
                }
                val blocked = readStateFailure(resolved, original)
                if (blocked != null) return@runCatching blocked
                access.writeText(resolved, newStr)
                ChangeTracker.record(conversationId, resolved, original, newStr, existedBefore = true)
                invalidateWorkspaceIndex(appContext, access, resolved)
                rememberWritten(resolved, newStr, "edit_file")
                return@runCatching ToolResult(formatEditModelContent(path, replaceAll))
            }
            if (resolved.endsWith(".ipynb")) {
                return@runCatching ToolResult(
                    "File is a Jupyter Notebook. Use the NotebookEdit to edit this file.",
                    isError = true,
                )
            }
            val blocked = readStateFailure(resolved, original)
            if (blocked != null) return@runCatching blocked
            val match = EditMatcher.findEditMatch(original, oldStr, replaceAll)
            val actualOld = when (match) {
                is EditMatcher.Result.NotFound -> return@runCatching ToolResult(
                    "String to replace not found in file.\nString: $oldStr",
                    isError = true,
                )
                is EditMatcher.Result.Ambiguous -> return@runCatching ToolResult(
                    ambiguousEditMessage(match.candidateCount, oldStr),
                    isError = true,
                )
                is EditMatcher.Result.Matched -> match.actualString
            }
            val count = if (actualOld.isEmpty()) 0 else original.split(actualOld).size - 1
            if (!replaceAll && count > 1) {
                return@runCatching ToolResult(ambiguousEditMessage(count, oldStr), isError = true)
            }
            val normalizedNew = EditMatcher.normalizeReplacementForMatch(
                (match as EditMatcher.Result.Matched).strategy, newStr,
            )
            val actualNew = EditMatcher.preserveQuoteStyle(oldStr, actualOld, normalizedNew)
            val updated = if (replaceAll) original.replace(actualOld, actualNew) else original.replaceFirst(actualOld, actualNew)
            access.writeText(resolved, updated)
            ChangeTracker.record(conversationId, resolved, original, updated, existedBefore = true)
            invalidateWorkspaceIndex(appContext, access, resolved)
            rememberWritten(resolved, updated, "edit_file")
            ToolResult(formatEditModelContent(path, replaceAll))
        }.getOrElse { ToolResult("编辑失败: ${it.message}", isError = true) }
    }

    private suspend fun readStateFailure(resolved: String, original: String): ToolResult? {
        val state = readFileState ?: return null
        val mtime = access.statMtime(resolved)
        return when (state.staleness(resolved, mtime, original.toByteArray().size.toLong())) {
            "未读取" -> ToolResult(EDIT_NOT_READ, isError = true)
            null -> null
            else -> ToolResult(EDIT_STALE, isError = true)
        }
    }

    private suspend fun rememberWritten(path: String, content: String, source: String) {
        val mtime = access.statMtime(path)
        val size = access.statSize(path) ?: content.toByteArray(Charsets.UTF_8).size.toLong()
        readFileState?.record(path, content, sourceTool = source, mtimeMs = mtime, sizeBytes = size)
    }
}
