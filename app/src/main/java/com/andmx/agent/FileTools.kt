package com.andmx.agent

import android.content.Context
import com.andmx.workspace.ChangeTracker
import com.andmx.workspace.WorkspaceAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

class ReadFileTool(context: Context, private val readFileState: ReadFileState? = null) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "read_file"
    override val description =
        "读取当前工作区中某个文件的文本内容。大文件会被截断:用 offset/limit 分页读取。"
    override val risk = ToolRisk.READ
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "文件路径，相对或绝对") }
            putJsonObject("offset") { put("type", "integer"); put("description", "起始行号(从 0 开始),默认 0"); put("default", 0) }
            putJsonObject("limit") { put("type", "integer"); put("description", "最多返回的行数,默认 2000"); put("default", 2000) }
        }
        putJsonArray("required") { add("path") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.str("path") ?: return ToolResult("缺少参数 path", isError = true)
        val offset = (args["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_LINE_LIMIT).coerceIn(1, MAX_LINE_LIMIT)
        val resolved = access.resolvePath(path)
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
            return readPdf(resolved)
        }
        return runCatching {
            val text = access.readText(path)
            val lines = text.split('\n')
            val total = lines.size
            val mtime = access.statMtime(resolved)
            if (total <= limit) {
                // 上游 cat -n 输出：行号从 1 起，右对齐宽度随总行数。
                val numbered = lines.mapIndexed { i, l -> "${i + 1}\t$l" }.joinToString("\n")
                readFileState?.record(resolved, text, mtimeMs = mtime, sizeBytes = text.toByteArray().size.toLong())
                ToolResult(numbered)
            } else {
                val end = (offset + limit).coerceAtMost(total)
                val sliceLines = lines.subList(offset, end)
                val numbered = sliceLines.mapIndexed { i, l -> "${offset + i + 1}\t$l" }.joinToString("\n")
                val slice = sliceLines.joinToString("\n")
                readFileState?.record(resolved, slice, offset, limit, mtimeMs = mtime, sizeBytes = text.toByteArray().size.toLong())
                val note = buildString {
                    append("\n\n... (已截断: 显示第 ${offset + 1}-$end 行, 共 $total 行)")
                    if (end < total) append("; 用 offset=$end 继续读取后续内容")
                    if (offset > 0) append("; 用 offset=${(offset - limit).coerceAtLeast(0)} 读取前面内容")
                }
                ToolResult(numbered + note)
            }
        }.getOrElse { ToolResult("读取失败: ${it.message}", isError = true) }
    }

    /** 本地 PDF 逐页渲染（android.graphics.pdf.PdfRenderer，无三方依赖）。 */
    private fun readPdf(resolved: String): ToolResult {
        val host = access.hostFile(resolved)
            ?: return ToolResult("远端工作区暂不支持 PDF 读取", isError = true)
        if (!host.exists()) return ToolResult("文件不存在: $resolved", isError = true)
        return runCatching {
            val pfd = android.os.ParcelFileDescriptor.open(host, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val totalPages = renderer.pageCount
            val images = mutableListOf<String>()
            try {
                val pages = totalPages.coerceAtMost(MAX_PDF_PAGES)
                for (i in 0 until pages) {
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
                "[pdf ${resolved.substringAfterLast('/')} · $totalPages 页, 渲染前 ${images.size} 页]",
                imageUrls = images,
            )
        }.getOrElse { ToolResult("PDF 读取失败: ${it.message}", isError = true) }
    }

    companion object {
        const val DEFAULT_LINE_LIMIT = 2000
        const val MAX_LINE_LIMIT = 5000
        const val MAX_PDF_PAGES = 8
        const val IMAGE_MAX_DIM = 2000
        const val IMAGE_RAW_MAX_BYTES = 4 * 1024 * 1024
        val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }
}

class WriteFileTool(context: Context, private val readFileState: ReadFileState? = null) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "write_file"
    override val description = "创建或覆盖当前工作区中的文件。会生成可在 diff 中审查的变更。"
    override val risk = ToolRisk.WRITE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "文件路径") }
            putJsonObject("content") { put("type", "string"); put("description", "文件完整内容") }
        }
        putJsonArray("required") { add("path"); add("content") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.str("path") ?: return ToolResult("缺少参数 path", isError = true)
        val content = args.str("content") ?: return ToolResult("缺少参数 content", isError = true)
        return runCatching {
            val resolved = access.resolvePath(path)
            val existed = access.exists(resolved)
            if (existed && readFileState != null) {
                val mtime = access.statMtime(resolved)
                val size = access.readBytes(resolved, 32 * 1024 * 1024)?.size?.toLong()
                when (val stale = readFileState.staleness(resolved, mtime, size)) {
                    "未读取" -> return@runCatching ToolResult(
                        "文件已存在但本会话未读取过，请先用 read_file 读取后再覆盖: $resolved",
                        isError = true,
                    )
                    null -> {}
                    else -> return@runCatching ToolResult(
                        "文件在读取后被修改（$stale），请先重新 read_file 再写入: $resolved",
                        isError = true,
                    )
                }
            }
            val old = if (existed) access.readText(resolved) else ""
            access.writeText(resolved, content)
            ChangeTracker.record(resolved, old, content, existedBefore = existed)
            readFileState?.record(resolved, content, sourceTool = ReadFileState.WRITE_TOOL)
            ToolResult("已写入 $resolved (${content.length} 字符)")
        }.getOrElse { ToolResult("写入失败: ${it.message}", isError = true) }
    }
}

class EditFileTool(context: Context, private val readFileState: ReadFileState? = null) : Tool {
    private val access = WorkspaceAccess(context)
    override val name = "edit_file"
    override val description =
        "对工作区中已存在文件做精确替换:把 old_str 首次出现替换为 new_str。old_str 必须在文件中唯一且完整匹配。"
    override val risk = ToolRisk.WRITE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "文件路径") }
            putJsonObject("old_str") { put("type", "string"); put("description", "要被替换的原文，必须唯一匹配") }
            putJsonObject("new_str") { put("type", "string"); put("description", "替换后的新文本") }
            putJsonObject("replace_all") { put("type", "boolean"); put("description", "是否替换全部匹配"); put("default", false) }
        }
        putJsonArray("required") { add("path"); add("old_str"); add("new_str") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.str("path") ?: return ToolResult("缺少参数 path", isError = true)
        val oldStr = args.str("old_str") ?: return ToolResult("缺少参数 old_str", isError = true)
        val newStr = args.str("new_str") ?: return ToolResult("缺少参数 new_str", isError = true)
        val replaceAll = args["replace_all"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        return runCatching {
            val resolved = access.resolvePath(path)
            if (!access.exists(resolved)) return@runCatching ToolResult("文件不存在: $resolved", isError = true)
            val original = access.readText(resolved)
            if (readFileState != null) {
                val mtime = access.statMtime(resolved)
                when (val stale = readFileState.staleness(resolved, mtime, original.toByteArray().size.toLong())) {
                    "未读取" -> return@runCatching ToolResult(
                        "文件本会话未读取过，请先用 read_file 读取后再编辑: $resolved",
                        isError = true,
                    )
                    null -> {}
                    else -> return@runCatching ToolResult(
                        "文件在读取后被修改（$stale），请先重新 read_file 再编辑: $resolved",
                        isError = true,
                    )
                }
            }
            val count = original.split(oldStr).size - 1
            if (count == 0) return@runCatching ToolResult("未找到匹配的 old_str", isError = true)
            if (!replaceAll && count > 1) {
                return@runCatching ToolResult("old_str 出现 $count 次，请提供更唯一的匹配或设置 replace_all=true", isError = true)
            }
            val updated = if (replaceAll) original.replace(oldStr, newStr) else original.replaceFirst(oldStr, newStr)
            access.writeText(resolved, updated)
            ChangeTracker.record(resolved, original, updated, existedBefore = true)
            readFileState?.record(resolved, updated, sourceTool = "edit_file")
            ToolResult("已编辑 $resolved" + if (replaceAll) " (替换 $count 处)" else "")
        }.getOrElse { ToolResult("编辑失败: ${it.message}", isError = true) }
    }
}
