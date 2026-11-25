package com.andmx.ui2.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andmx.ui2.settings.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

internal fun browserUrl(raw: String): String? {
    val candidate = raw.trim()
    if (candidate.length !in 1..4096) return null
    return runCatching {
        val uri = java.net.URI(candidate)
        candidate.takeIf {
            uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.userInfo == null
        }
    }.getOrNull()
}

@Composable
internal fun BrowserDialog(
    initialUrl: String,
    onClose: () -> Unit,
    onPage: (String) -> Unit,
    onElement: (String) -> Unit,
    localDocument: File? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageCallback by rememberUpdatedState(onPage)
    val elementCallback by rememberUpdatedState(onElement)
    val localOrigin = remember(localDocument) { "preview-${UUID.randomUUID()}.invalid" }
    val localRoot = localDocument?.canonicalFile?.parentFile
    val localUrl = localDocument?.let { "https://$localOrigin/${Uri.encode(it.name)}" }
    var address by remember(initialUrl) { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(false) }
    var pendingCapture by remember { mutableStateOf<Bitmap?>(null) }
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    if (localDocument == null) browserUrl(request.url.toString()) == null
                    else request.url.host != localOrigin

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (localRoot == null) return null
                    val path = request.url.path.orEmpty().trimStart('/')
                    val target = localPreviewFile(localRoot, path)
                    val mime = target?.extension?.lowercase()?.let(localPreviewMime::get)
                    if (request.url.host != localOrigin || request.method != "GET" || target == null || mime == null) {
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), null)
                    }
                    return runCatching {
                        WebResourceResponse(
                            mime,
                            if (mime.startsWith("text/")) "UTF-8" else null,
                            200,
                            "OK",
                            mapOf(
                                "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'unsafe-inline'; " +
                                    "style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                                    "object-src 'none'; frame-src 'none'; worker-src 'none'; form-action 'none'; base-uri 'none'",
                                "Cache-Control" to "no-store",
                                "X-Content-Type-Options" to "nosniff",
                            ),
                            target.inputStream(),
                        )
                    }.getOrElse { WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null) }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (localDocument != null) return
                    browserUrl(url)?.let {
                        address = it
                        pageCallback(it)
                    }
                }
            }
            (localUrl ?: browserUrl(initialUrl))?.let(::loadUrl)
        }
    }
    LaunchedEffect(web, localDocument) {
        if (localDocument == null) return@LaunchedEffect
        var modified = localDocument.lastModified()
        while (true) {
            delay(1200)
            val current = localDocument.lastModified()
            if (current != modified) {
                modified = current
                web.reload()
            }
        }
    }
    val originalAgent = remember(web) { web.settings.userAgentString }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        val bitmap = pendingCapture
        pendingCapture = null
        if (bitmap != null) {
            if (uri == null) bitmap.recycle() else scope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            } == true
                        }.getOrDefault(false)
                    }
                    error = !saved
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }
    DisposableEffect(web) {
        onDispose {
            pendingCapture?.recycle()
            web.stopLoading()
            web.destroy()
        }
    }
    fun navigate() {
        val target = browserUrl(address)
        error = target == null
        if (target != null) web.loadUrl(target)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose) { Text(tr("关闭")) }
                    TextButton(onClick = { if (web.canGoBack()) web.goBack() }) { Text(tr("返回")) }
                    TextButton(onClick = { web.reload() }) { Text(tr("刷新")) }
                    TextButton(onClick = {
                        desktop = !desktop
                        web.settings.useWideViewPort = desktop
                        web.settings.loadWithOverviewMode = desktop
                        web.settings.userAgentString = if (desktop) originalAgent.replace("Mobile", "") else originalAgent
                        web.reload()
                    }) { Text(tr(if (desktop) "移动版" else "桌面版")) }
                }
                if (localDocument == null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(tr("网址")) },
                        singleLine = true,
                        isError = error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = ::navigate) { Text(tr("打开")) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { inspecting = !inspecting }) {
                        Text(tr(if (inspecting) "点击网页元素" else "选取元素"))
                    }
                    TextButton(onClick = {
                        if (pendingCapture == null && web.width > 0 && web.height > 0) {
                            val scale = minOf(1f, 1600f / maxOf(web.width, web.height))
                            pendingCapture = Bitmap.createBitmap(
                                (web.width * scale).toInt().coerceAtLeast(1),
                                (web.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            ).also { bitmap ->
                                val canvas = Canvas(bitmap)
                                canvas.scale(scale, scale)
                                web.draw(canvas)
                            }
                            capture.launch("web-preview.png")
                        }
                    }) { Text("PNG") }
                    TextButton(onClick = {
                        val print = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                        print?.print("AndMX Web", web.createPrintDocumentAdapter("AndMX Web"), PrintAttributes.Builder().build())
                    }) { Text("PDF") }
                }
                if (error) Text(tr("无法打开网址或保存页面。"), Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
                AndroidView(
                    factory = { web },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    update = { view ->
                        view.setOnTouchListener { _, event ->
                            if (!inspecting) return@setOnTouchListener false
                            if (event.action == MotionEvent.ACTION_UP) {
                                val density = context.resources.displayMetrics.density
                                val x = event.x / density
                                val y = event.y / density
                                view.evaluateJavascript(
                                    """(() => {
                                        const e = document.elementFromPoint($x, $y);
                                        if (!e) return "";
                                        return JSON.stringify({
                                            tag: e.tagName,
                                            id: e.id,
                                            class: String(e.className).slice(0, 200),
                                            text: String(e.innerText || e.textContent || "").trim().slice(0, 1000)
                                        });
                                    })()""".trimIndent(),
                                ) { raw ->
                                    val value = runCatching { Json.decodeFromString<String>(raw) }.getOrNull()
                                    val url = localDocument?.name ?: view.url?.let(::browserUrl)
                                    if (!value.isNullOrBlank() && url != null) {
                                        elementCallback("[Web page element from ${JsonPrimitive(url)}]\n${value.take(1800)}")
                                    }
                                }
                                inspecting = false
                            }
                            true
                        }
                    },
                )
            }
        }
    }
}

private val localPreviewMime = mapOf(
    "html" to "text/html", "htm" to "text/html", "css" to "text/css",
    "js" to "text/javascript", "mjs" to "text/javascript", "svg" to "image/svg+xml",
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
    "webp" to "image/webp", "gif" to "image/gif", "woff2" to "font/woff2",
)

internal fun localPreviewFile(root: File, path: String): File? {
    if (path.isBlank() || path.split('/').any { it.startsWith('.') }) return null
    return runCatching {
        val base = root.canonicalFile
        val requested = File(base, path)
        val target = requested.canonicalFile
        target.takeIf { it == requested.absoluteFile && it.toPath().startsWith(base.toPath()) && it.isFile }
    }.getOrNull()
}
