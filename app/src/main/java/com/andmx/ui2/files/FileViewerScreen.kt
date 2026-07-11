package com.andmx.ui2.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private enum class FileKind { TEXT, MARKDOWN, IMAGE, BINARY }

private fun kindOf(name: String): FileKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "md", "markdown" -> FileKind.MARKDOWN
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> FileKind.IMAGE
        "zip", "tar", "gz", "jar", "apk", "so", "bin", "exe", "pdf", "class", "o" -> FileKind.BINARY
        else -> FileKind.TEXT
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(path: String, onBack: () -> Unit) {
    val file = remember(path) { File(path) }
    val kind = remember(path) { kindOf(file.name) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var wrap by remember { mutableStateOf(false) }
    var mdSource by remember { mutableStateOf(false) }
    var chunkIndex by remember { mutableIntStateOf(0) }

    val config = LocalCodePreviewConfig.current

    val fullContent by produceState(initialValue = null as String?, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (!file.exists()) null else file.readText() }.getOrNull()
        }
        value?.let { draft = it }
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
                    ) { Text("预览") }
                    SegmentedButton(
                        selected = mdSource, onClick = { mdSource = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("源码") }
                }
            }

            if (chunkCount > 1 && (kind == FileKind.TEXT || kind == FileKind.MARKDOWN) && !editing) {
                ChunkBar(
                    shownStart = shownStart, shownEnd = shownEnd, total = totalLines,
                    hasPrev = safeChunk > 0, hasNext = safeChunk < chunkCount - 1,
                    onPrev = { chunkIndex-- }, onNext = { chunkIndex++ }
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    kind == FileKind.BINARY ->
                        CenterHint("当前文件看起来像二进制内容，暂不支持预览。")
                    kind == FileKind.IMAGE ->
                        CenterHint("图片预览：${file.name}")
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
                        startLine = shownStart
                    )
                }
            }
        }
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
        Text(
            "已显示 $shownStart - $shownEnd / $total 行",
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
private fun CodeView(code: String, wrap: Boolean, showLineNumbers: Boolean, startLine: Int) {
    val theme = if (isSystemInDarkTheme()) CodeThemes.OneDarkPro else CodeThemes.GithubLight
    val content = if (wrap) code else code
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .let { if (wrap) it else it.horizontalScroll(rememberScrollState()) }
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        CodeBlockThemed(
            code = content,
            theme = theme,
            showLineNumbers = showLineNumbers,
            wrapLongLines = wrap,
            fontSize = 13
        )
    }
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
