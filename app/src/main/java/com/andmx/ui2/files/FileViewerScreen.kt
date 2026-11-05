package com.andmx.ui2.files

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.andmx.ui2.markdown.CodeBlockThemed
import com.andmx.ui2.markdown.CodeThemes
import com.andmx.ui2.markdown.LocalCodePreviewConfig
import com.andmx.ui2.markdown.MarkdownView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import java.io.File

private const val CHUNK = 256 * 1024
private const val MAX_PDF_PAGES = 10

data class FileReviewAnchor(
    val path: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val title: String? = null,
    val body: String? = null,
    val priority: Int? = null,
)

private enum class FileKind { TEXT, MARKDOWN, IMAGE, PDF, AUDIO, VIDEO, OFFICE, BINARY }

private data class PdfPreview(val pages: List<ImageBitmap>, val totalPages: Int)

private fun kindOf(name: String): FileKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "md", "markdown" -> FileKind.MARKDOWN
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> FileKind.IMAGE
        "pdf" -> FileKind.PDF
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> FileKind.AUDIO
        "mp4", "webm", "mkv", "mov", "3gp", "m4v", "avi" -> FileKind.VIDEO
        "docx", "pptx", "xlsx" -> FileKind.OFFICE
        "zip", "tar", "gz", "jar", "apk", "so", "bin", "exe", "class", "o" -> FileKind.BINARY
        else -> FileKind.TEXT
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(path: String, onBack: () -> Unit, review: FileReviewAnchor? = null) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    val kind = remember(path) { kindOf(file.name) }
    val activeReview = remember(review, path) {
        review?.takeIf { anchor ->
            anchor.path == null ||
                file.absolutePath == anchor.path ||
                file.name == File(anchor.path).name
        }
    }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var wrap by remember { mutableStateOf(false) }
    var mdSource by remember { mutableStateOf(false) }
    var chunkIndex by remember { mutableIntStateOf(0) }

    val config = LocalCodePreviewConfig.current

    val fullContent by produceState(initialValue = null as String?, key1 = path) {
        if (kind == FileKind.IMAGE || kind == FileKind.PDF || kind == FileKind.BINARY ||
            kind == FileKind.AUDIO || kind == FileKind.VIDEO || kind == FileKind.OFFICE
        ) {
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching { if (!file.exists()) null else file.readText() }.getOrNull()
        }
        value?.let { draft = it }
    }

    val imageBitmap by produceState(initialValue = null as ImageBitmap?, key1 = path, key2 = kind) {
        if (kind != FileKind.IMAGE) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val pdfPreview by produceState(initialValue = null as PdfPreview?, key1 = path, key2 = kind) {
        if (kind != FileKind.PDF) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                try {
                    val total = renderer.pageCount
                    val count = minOf(total, MAX_PDF_PAGES)
                    val pages = (0 until count).mapNotNull { index ->
                        val page = renderer.openPage(index)
                        try {
                            val targetWidth = 720
                            val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                width,
                                height,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            bitmap.asImageBitmap()
                        } finally {
                            page.close()
                        }
                    }
                    PdfPreview(pages, total)
                } finally {
                    renderer.close()
                    pfd.close()
                }
            }.getOrNull()
        }
    }

    val officeText by produceState(initialValue = null as String?, key1 = path, key2 = kind) {
        if (kind != FileKind.OFFICE) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { OfficeTextExtractor.extract(file) }.getOrNull()
        }
    }

    val totalLen = fullContent?.length ?: 0
    val chunkCount = ((totalLen + CHUNK - 1) / CHUNK).coerceAtLeast(1)
    val safeChunk = chunkIndex.coerceIn(0, chunkCount - 1)
    val viewContent = fullContent?.let {
        if (it.length > CHUNK) it.substring(safeChunk * CHUNK, ((safeChunk + 1) * CHUNK).coerceAtMost(it.length))
        else it
    }
    val startLine = safeChunk * CHUNK
    val totalLines = fullContent?.count { it == '\n' }?.plus(1) ?: 0
    val shownStart = fullContent?.let { it.substring(0, safeChunk * CHUNK).count { c -> c == '\n' } + 1 } ?: 0
    val shownEnd = viewContent?.count { it == '\n' }?.plus(shownStart) ?: 0
    val reviewRange = activeReview?.startLine?.let { start ->
        val end = activeReview.endLine ?: start
        if (end >= start) start..end else null
    }
    val reviewScrollLine = reviewRange?.first
    val localReviewRange = reviewRange?.let { range ->
        val start = (range.first - shownStart + 1).coerceAtLeast(1)
        val end = (range.last - shownStart + 1).coerceAtMost(shownEnd - shownStart + 1)
        if (start <= end) start..end else null
    }
    val reviewScrollLocal = reviewScrollLine?.let { (it - shownStart + 1).coerceAtLeast(1) }

    LaunchedEffect(fullContent, reviewScrollLine) {
        val content = fullContent ?: return@LaunchedEffect
        val line = reviewScrollLine ?: return@LaunchedEffect
        if (content.length <= CHUNK) return@LaunchedEffect
        val offset = lineStartOffset(content, line)
        chunkIndex = (offset / CHUNK).coerceIn(0, chunkCount - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            file.absolutePath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    if (kind == FileKind.OFFICE) {
                        IconButton(onClick = { openFileExternally(context, file) }) {
                            Icon(Icons.Outlined.OpenInNew, "用其他应用打开")
                        }
                    }
                    if (kind == FileKind.TEXT || kind == FileKind.MARKDOWN) {
                        IconButton(onClick = { wrap = !wrap }) {
                            Icon(
                                Icons.Outlined.WrapText, "自动换行",
                                tint = if (wrap) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (editing) {
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { file.writeText(draft) } }
                                    editing = false
                                }
                            }) { Icon(Icons.Outlined.Check, "保存") }
                        } else {
                            IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "编辑") }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (kind == FileKind.MARKDOWN && !editing) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    SegmentedButton(
                        selected = !mdSource, onClick = { mdSource = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text(tr("预览")) }
                    SegmentedButton(
                        selected = mdSource, onClick = { mdSource = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text(tr("源码")) }
                }
            }

            if (chunkCount > 1 && (kind == FileKind.TEXT || kind == FileKind.MARKDOWN) && !editing) {
                ChunkBar(
                    shownStart = shownStart, shownEnd = shownEnd, total = totalLines,
                    hasPrev = safeChunk > 0, hasNext = safeChunk < chunkCount - 1,
                    onPrev = { chunkIndex-- }, onNext = { chunkIndex++ }
                )
            }

            if (activeReview != null &&
                (!activeReview.title.isNullOrBlank() || !activeReview.body.isNullOrBlank())
            ) {
                ReviewAnnotationCard(activeReview)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    kind == FileKind.AUDIO || kind == FileKind.VIDEO ->
                        MediaPreview(file, audio = kind == FileKind.AUDIO)
                    kind == FileKind.OFFICE && officeText != null ->
                        OfficePreview(officeText!!)
                    kind == FileKind.OFFICE ->
                        CenterHint("无法提取这个 Office 文档的文本。可能已加密，或文件结构不受支持。")
                    kind == FileKind.BINARY ->
                        CenterHint("当前文件看起来像二进制内容，暂不支持预览。")
                    kind == FileKind.IMAGE && imageBitmap != null ->
                        Image(
                            bitmap = imageBitmap!!,
                            contentDescription = file.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentScale = ContentScale.Fit,
                        )
                    kind == FileKind.IMAGE ->
                        CenterHint("无法解码这张图片（SVG 等矢量格式暂不支持预览）。")
                    kind == FileKind.PDF && pdfPreview != null -> {
                        val preview = pdfPreview!!
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(preview.pages) { index, page ->
                                Image(
                                    bitmap = page,
                                    contentDescription = "第 ${index + 1} 页",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    contentScale = ContentScale.FillWidth,
                                )
                            }
                            if (preview.totalPages > preview.pages.size) {
                                item {
                                    Text(
                                        tr("仅显示前 ${preview.pages.size} / ${preview.totalPages} 页"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    kind == FileKind.PDF ->
                        CenterHint("无法打开这个 PDF 文件。")
                    fullContent == null ->
                        CenterHint("文件不存在，或当前环境无法访问该路径。")
                    fullContent!!.isEmpty() ->
                        CenterHint("文件为空")
                    editing ->
                        EditView(draft = draft, onChange = { draft = it })
                    kind == FileKind.MARKDOWN && !mdSource ->
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).padding(16.dp)
                        ) { MarkdownView(markdown = viewContent ?: "") }
                    else -> CodeView(
                        code = viewContent ?: "",
                        wrap = wrap,
                        showLineNumbers = config.showLineNumbers,
                        startLine = shownStart,
                        highlightRange = localReviewRange,
                        scrollToLine = reviewScrollLocal,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfficePreview(text: String) {
    Column(Modifier.fillMaxSize()) {
        Text(
            tr("仅提取文本，版式、图片与图表不会显示。"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun MediaPreview(file: File, audio: Boolean) {
    val context = LocalContext.current
    var failed by remember(file) { mutableStateOf(false) }
    val view = remember(file) { mutableStateOf<android.widget.VideoView?>(null) }
    DisposableEffect(file) {
        onDispose { view.value?.stopPlayback() }
    }
    if (failed) {
        CenterHint("系统无法播放这个文件（缺少解码器，或文件已损坏）。")
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (!file.exists()) {
            CenterHint("文件不存在，或当前环境无法访问该路径。")
            return@Column
        }
        if (audio) {
            Text(
                "🎵 ${file.name}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setMediaController(
                        android.widget.MediaController(ctx).also { it.setAnchorView(this) },
                    )
                    setVideoURI(android.net.Uri.fromFile(file))
                    setOnPreparedListener { player ->
                        player.isLooping = false
                        start()
                    }
                    setOnErrorListener { _, _, _ ->
                        failed = true
                        true
                    }
                    view.value = this
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        )
    }
}

@Composable
private fun ChunkBar(
    shownStart: Int, shownEnd: Int, total: Int,
    hasPrev: Boolean, hasNext: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onPrev, enabled = hasPrev) {
            Text("‹", style = MaterialTheme.typography.titleLarge)
        }
        Text(tr("已显示 $shownStart - $shownEnd / $total 行"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onNext, enabled = hasNext) {
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun CodeView(
    code: String,
    wrap: Boolean,
    showLineNumbers: Boolean,
    startLine: Int,
    highlightRange: IntRange? = null,
    scrollToLine: Int? = null,
) {
    val theme = if (isSystemInDarkTheme()) CodeThemes.OneDarkPro else CodeThemes.GithubLight
    val content = if (wrap) code else code
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(scrollToLine, code) {
        val line = scrollToLine ?: return@LaunchedEffect
        val lineHeight = with(density) { 19.5.sp.toPx() }
        scrollState.scrollTo(((line - 1) * lineHeight).toInt().coerceAtLeast(0))
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .verticalScroll(scrollState)
            .padding(12.dp)
    ) {
        CodeBlockThemed(
            code = content,
            theme = theme,
            showLineNumbers = showLineNumbers,
            wrapLongLines = wrap,
            fontSize = 13,
            lineNumberStart = startLine,
            highlightRange = highlightRange,
        )
    }
}

@Composable
private fun ReviewAnnotationCard(review: FileReviewAnchor) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            review.priority?.let { priority ->
                val color = when (priority) {
                    0 -> Color(0xFFE03131)
                    1 -> Color(0xFFFF8A30)
                    2 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "P$priority",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = color,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                review.title?.takeIf { it.isNotBlank() } ?: tr("代码评论"),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        review.body?.takeIf { it.isNotBlank() }?.let { body ->
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun lineStartOffset(text: String, line: Int): Int {
    if (line <= 1) return 0
    var current = 1
    for (index in text.indices) {
        if (text[index] == '\n') {
            current++
            if (current == line) return index + 1
        }
    }
    return text.length
}

@Composable
private fun EditView(draft: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = draft,
        onValueChange = onChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    )
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openFileExternally(context: android.content.Context, file: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(
            context,
            "没有可打开该文件的应用。",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
