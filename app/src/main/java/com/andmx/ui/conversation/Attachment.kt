package com.andmx.ui.conversation

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A file attached to the composer; its text is folded into the model context. */
data class Attachment(
    val name: String,
    val content: String,
    val truncated: Boolean = false,
    /** For images: a data: URL fed to vision models. */
    val imageDataUrl: String? = null,
    val imageMeta: ImageAttachmentMeta? = null,
    /** Relative path in app-private storage for long-lived UI screenshot evidence. */
    val imageAssetPath: String? = null,
) {
    val isImage: Boolean get() = imageDataUrl != null
    val isUiReference: Boolean get() = isImage && name.looksLikeUiReferenceName()
}

data class ImageAttachmentMeta(
    val mime: String = "",
    val byteSize: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val summary: String
        get() = listOfNotNull(
            dimensionLabel.takeIf { it.isNotBlank() },
            mime.takeIf { it.isNotBlank() },
            byteSizeLabel.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    private val dimensionLabel: String
        get() = if (width > 0 && height > 0) "${width}x$height" else ""

    private val byteSizeLabel: String
        get() = byteSize.takeIf { it > 0 }?.let(::formatByteSize).orEmpty()
}

object Attachments {
    private const val LIMIT = 200 * 1024
    private const val IMAGE_LIMIT = 5 * 1024 * 1024

    /** Read a SAF/content [uri] into an [Attachment]. */
    fun fromUri(context: Context, uri: Uri): Attachment? = runCatching {
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "file"
        val mime = context.contentResolver.getType(uri) ?: ""
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (mime.startsWith("image/") && bytes.size <= IMAGE_LIMIT) {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val attachment = Attachment(
                name = name,
                content = imageContentLabel(name),
                imageDataUrl = "data:$mime;base64,$b64",
                imageMeta = imageMeta(mime, bytes),
            )
            return attachment.copy(
                imageAssetPath = UiReferenceAssets.persist(
                    context = context,
                    bytes = bytes,
                    referenceId = attachment.referenceId,
                    mime = mime,
                    name = name,
                ),
            )
        }
        val isBinary = bytes.take(4096).any { it.toInt() == 0 }
        if (isBinary) return Attachment(name, "(二进制文件,未内联)", truncated = true)
        val text = String(bytes.copyOf(minOf(bytes.size, LIMIT)), Charsets.UTF_8)
        Attachment(name, text, truncated = bytes.size > LIMIT)
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** Append text attachment contents to a message for the model (images go separately). */
    fun augment(text: String, attachments: List<Attachment>): String {
        val textAtt = attachments.filterNot { it.isImage }
        if (textAtt.isEmpty() && attachments.none { it.isImage }) return text
        return buildString {
            append(text)
            val imageAtt = attachments.filter { it.isImage }
            if (imageAtt.isNotEmpty()) {
                append("\n\n## 图片/UI 参考")
                append('\n').append(imageReferencePrompt(imageAtt))
            }
            for (a in textAtt) {
                append("\n\n附件 ").append(a.name).append(":\n```\n").append(a.content).append("\n```")
                if (a.truncated) append("\n(已截断)")
            }
        }
    }

    fun displaySummary(attachments: List<Attachment>): String =
        attachments.joinToString("") { "\n${if (it.isImage) "🖼" else "📎"} ${it.referenceLabel}" }

    fun displayText(text: String, attachments: List<Attachment>): String =
        if (attachments.isEmpty()) text else text + displaySummary(attachments)

    fun preflightSummary(attachments: List<Attachment>): AttachmentPreflightSummary {
        val imageCount = attachments.count { it.isImage }
        val uiReferenceCount = attachments.count { it.isUiReference }
        val metadataCount = attachments.count { it.imageMeta?.summary?.isNotBlank() == true }
        val assetCount = attachments.count { it.imageAssetPath?.isNotBlank() == true }
        val routeCommands = buildList {
            if (imageCount > 0) {
                add("/references")
                add("/trace")
            }
            if (attachments.isNotEmpty()) add("/evidence")
        }
        return AttachmentPreflightSummary(
            attachmentCount = attachments.size,
            imageCount = imageCount,
            uiReferenceCount = uiReferenceCount,
            metadataCount = metadataCount,
            assetCount = assetCount,
            routeCommands = routeCommands,
        )
    }

    fun localIntakeText(attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return ""
        val summary = preflightSummary(attachments)
        return buildString {
            appendLine("## 本地截图/附件接收")
            appendLine("- 已接收: ${summary.title}")
            if (summary.metadataCount > 0) {
                appendLine("- 元数据: ${summary.metadataCount} 个图片附件带有尺寸、MIME 或大小信息")
            }
            if (summary.assetCount > 0) {
                appendLine("- 本地资产: ${summary.assetCount} 张图片已保存到私有 UI 参考目录")
            }
            appendLine(
                if (summary.hasVisualReferences) {
                    "- 状态: 截图已进入本地参考链路, 配置模型前也会保留在参考板和证据账本。"
                } else {
                    "- 状态: 附件已进入用户消息, 配置模型后会随请求进入上下文。"
                },
            )
            if (summary.routeCommands.isNotEmpty()) {
                appendLine("- 本地入口: ${summary.routeCommands.joinToString(" ") { "`$it`" }}")
            }
        }.trimEnd()
    }

    fun imageReferencePrompt(attachments: List<Attachment>): String = buildString {
        val images = attachments.filter { it.isImage }
        appendLine("- 共 ${images.size} 张图片参考, 其中 ${images.count { it.isUiReference }} 张疑似界面截图。")
        appendLine("- 分析顺序: 布局结构 -> 可见控件 -> 状态语言 -> 交互路径 -> 工程实现线索 -> 可落地改进。")
        appendLine("- 若图片是 Codex/竞品界面, 先抽取模式和流程, 再映射到 AndMX 的移动工作台。")
        appendLine("- 对每张界面截图输出复刻执行单: 观察清单、目标 surface、目标文件、实现动作、验证门槛和下一入口。")
        appendLine("- 优先识别 Composer、会话流、Inspector、任务面板、命令面板、工作区、审批/安全和截图复刻流水线。")
        images.forEachIndexed { index, attachment ->
            appendLine("- 图 ${index + 1}: ${attachment.referenceLabel}")
        }
    }.trimEnd()

    fun referencesFromDisplay(text: String): List<UiReference> =
        text.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("🖼 ") -> parseDisplayReference(trimmed.removePrefix("🖼 ").trim(), image = true)
                    trimmed.startsWith("📎 ") -> parseDisplayReference(trimmed.removePrefix("📎 ").trim(), image = false)
                    else -> null
                }
            }
            .toList()

    private fun imageContentLabel(name: String): String =
        if (name.looksLikeUiReferenceName()) "(UI 截图参考)" else "(图片)"

    private fun imageMeta(mime: String, bytes: ByteArray): ImageAttachmentMeta {
        val bounds = runCatching {
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
            }
        }.getOrNull()
        return ImageAttachmentMeta(
            mime = mime,
            byteSize = bytes.size,
            width = bounds?.outWidth?.takeIf { it > 0 } ?: 0,
            height = bounds?.outHeight?.takeIf { it > 0 } ?: 0,
        )
    }
}

data class UiReference(
    val label: String,
    val image: Boolean,
    val meta: String = "",
    val assetPath: String = "",
)

data class AttachmentPreflightSummary(
    val attachmentCount: Int,
    val imageCount: Int,
    val uiReferenceCount: Int,
    val metadataCount: Int,
    val assetCount: Int = 0,
    val routeCommands: List<String>,
) {
    val hasVisualReferences: Boolean get() = imageCount > 0

    val title: String
        get() = listOfNotNull(
            "$attachmentCount 个附件",
            imageCount.takeIf { it > 0 }?.let { "$it 张图片" },
            uiReferenceCount.takeIf { it > 0 }?.let { "$it 个 UI 参考" },
        ).joinToString(" · ")

    val detail: String
        get() = if (routeCommands.isEmpty()) {
            ""
        } else {
            "发送后进入 ${routeCommands.joinToString("、")}"
        }
}

val Attachment.referenceLabel: String
    get() = listOfNotNull(
        (if (isUiReference) "$name · UI 参考" else name),
        imageMeta?.summary?.takeIf { it.isNotBlank() },
        referenceId.takeIf { it.isNotBlank() },
        imageAssetPath?.takeIf { it.isNotBlank() }?.let(UiReferenceAssets::marker),
    ).joinToString(" · ")

val Attachment.referenceId: String
    get() {
        if (!isImage) return ""
        val seed = listOf(
            name,
            imageMeta?.summary.orEmpty(),
            imageDataUrl.orEmpty(),
        ).joinToString("|")
        if (seed.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return "ref:" + digest.take(4).joinToString("") { "%02x".format(it) }
    }

val Attachment.composerKindLabel: String
    get() = when {
        isUiReference -> "UI 参考"
        isImage -> "图片"
        truncated -> "附件"
        else -> "文件"
    }

val Attachment.composerMetaLabel: String
    get() = when {
        isImage -> imageMeta?.summary.orEmpty()
        truncated -> "未内联"
        else -> ""
    }

object UiReferenceAssets {
    private const val DIRECTORY = "ui-references"
    private val safeExt = Regex("""[a-z0-9]{1,8}""")
    private val safeRef = Regex("""[a-f0-9]{8}""")

    fun relativePath(referenceId: String, mime: String = "", name: String = ""): String {
        val stem = safeReferenceStem(referenceId)
        val ext = extensionFor(mime, name)
        return "$DIRECTORY/ref-$stem.$ext"
    }

    fun marker(relativePath: String): String =
        "asset:${relativePath.trim().removePrefix("asset:")}"

    fun pathFromMarker(segment: String): String? {
        val value = segment.trim()
        if (!value.startsWith("asset:", ignoreCase = true)) return null
        return value.substringAfter(':').trim().takeIf { it.isNotBlank() }
    }

    fun persist(
        context: Context,
        bytes: ByteArray,
        referenceId: String,
        mime: String,
        name: String,
    ): String? = runCatching {
        val relative = relativePath(referenceId, mime, name)
        val file = File(context.filesDir, relative)
        file.parentFile?.mkdirs()
        if (!file.exists() || file.length() != bytes.size.toLong()) {
            file.writeBytes(bytes)
        }
        relative
    }.getOrNull()

    private fun safeReferenceStem(referenceId: String): String {
        val raw = referenceId.removePrefix("ref:").lowercase(Locale.US)
        if (raw.matches(safeRef)) return raw
        val digest = MessageDigest.getInstance("SHA-256").digest(referenceId.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun extensionFor(mime: String, name: String): String {
        val fromMime = mime.substringAfter('/', "")
            .substringBefore(';')
            .lowercase(Locale.US)
            .let {
                when (it) {
                    "jpeg", "jpg" -> "jpg"
                    "png" -> "png"
                    "webp" -> "webp"
                    "gif" -> "gif"
                    "heic" -> "heic"
                    "heif" -> "heif"
                    "bmp" -> "bmp"
                    else -> it.takeIf { ext -> ext.matches(safeExt) }
                }
            }
        val fromName = name.substringAfterLast('.', "")
            .lowercase(Locale.US)
            .takeIf { it.matches(safeExt) }
        return fromMime ?: fromName ?: "img"
    }
}

private fun String.looksLikeUiReferenceName(): Boolean {
    val n = lowercase()
    return listOf(
        "screenshot",
        "screen shot",
        "截屏",
        "截图",
        "界面",
        "ui",
        "ux",
        "codex",
    ).any { n.contains(it) }
}

private fun parseDisplayReference(raw: String, image: Boolean): UiReference {
    val parts = raw.split(" · ").map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return UiReference(raw.trim(), image = image)
    var labelEnd = parts.size
    while (labelEnd > 0 && parts[labelEnd - 1].isReferenceMetaSegment()) {
        labelEnd -= 1
    }
    val label = parts.take(labelEnd).joinToString(" · ").ifBlank { raw.trim() }
    val metaParts = parts.drop(labelEnd)
    val assetPath = metaParts.asSequence()
        .mapNotNull(UiReferenceAssets::pathFromMarker)
        .firstOrNull()
        .orEmpty()
    val meta = metaParts.filter { UiReferenceAssets.pathFromMarker(it) == null }.joinToString(" · ")
    return UiReference(label = label, image = image, meta = meta, assetPath = assetPath)
}

private fun String.isReferenceMetaSegment(): Boolean {
    val value = trim()
    return value.matches(Regex("""\d+x\d+""")) ||
        value.matches(Regex("""[a-z0-9.+-]+/[a-z0-9.+-]+""", RegexOption.IGNORE_CASE)) ||
        value.matches(Regex("""\d+(?:\.\d+)?\s?(?:B|KB|MB|GB)""", RegexOption.IGNORE_CASE)) ||
        value.matches(Regex("""ref:[a-f0-9]{8}""", RegexOption.IGNORE_CASE)) ||
        UiReferenceAssets.pathFromMarker(value) != null
}

private fun formatByteSize(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "${String.format(Locale.US, "%.1f", value).removeSuffix(".0")} ${units[unitIndex]}"
}
