package com.andmx.ui.workbench

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/**
 * In-app browser — the agent/user's fourth hand, mirroring Codex's browser
 * surface. A URL bar drives a WebView. Note: only HTTPS loads (cleartext is
 * disabled on the proot/targetSdk-28 flavor by default).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserPane(
    state: BrowserPaneState,
    initialUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun normalize(raw: String): String {
        val t = raw.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
    }

    LaunchedEffect(initialUrl) {
        val next = initialUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        state.load(normalize(next))
    }

    fun syncNav(view: WebView?) {
        state.canGoBack = view?.canGoBack() == true
        state.canGoForward = view?.canGoForward() == true
        view?.url?.let {
            state.currentUrl = it
            state.urlInput = it
        }
    }

    fun openExternal() {
        val url = state.currentUrl.takeIf { it.isNotBlank() } ?: state.urlInput
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        // address bar
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserIcon(Icons.AutoMirrored.Outlined.ArrowBack, "后退", enabled = state.canGoBack) {
                webView?.let { if (it.canGoBack()) it.goBack() }
            }
            Spacer(Modifier.width(Spacing.xs))
            BrowserIcon(Icons.AutoMirrored.Outlined.ArrowForward, "前进", enabled = state.canGoForward) {
                webView?.let { if (it.canGoForward()) it.goForward() }
            }
            Spacer(Modifier.width(Spacing.sm))
            Box(
                Modifier.weight(1f).clip(Radii.pill).background(colors.sunken)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                BasicTextField(
                    value = state.urlInput,
                    onValueChange = { state.urlInput = it },
                    singleLine = true,
                    textStyle = AndmxTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { state.load(normalize(state.urlInput)) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            BrowserIcon(
                if (state.loading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                if (state.loading) "停止加载" else "刷新",
            ) {
                if (state.loading) webView?.stopLoading() else webView?.reload()
            }
            Spacer(Modifier.width(Spacing.xs))
            BrowserIcon(Icons.Outlined.ContentCopy, "复制网址") {
                clipboard.setText(AnnotatedString(state.currentUrl.ifBlank { state.urlInput }))
            }
            Spacer(Modifier.width(Spacing.xs))
            BrowserIcon(Icons.Outlined.OpenInBrowser, "系统浏览器打开", onClick = ::openExternal)
        }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent)
        } else {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        // Surface a main-frame load error instead of leaving a blank pane.
        val error = state.loadError
        if (error != null) {
            Box(
                Modifier.fillMaxWidth().background(colors.sunken).padding(Spacing.lg),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(
                        "页面加载失败",
                        style = AndmxTheme.typography.titleSmall,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "$error",
                        style = AndmxTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "重试",
                        style = AndmxTheme.typography.labelLarge,
                        color = colors.onAccent,
                        modifier = Modifier.clip(Radii.pill).background(colors.sendActive)
                            .clickable { state.load(state.pendingLoad) }
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    )
                }
            }
        }

        // Preview-first empty state: when no URL has been loaded, explain that
        // this pane mirrors what the agent is browsing (Codex in-app browser
        // positioning), rather than presenting a blank search box.
        if (state.isEmpty && error == null) {
            Box(
                Modifier.fillMaxSize().background(colors.canvas),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 360.dp).padding(Spacing.xxl),
                ) {
                    Icon(
                        Icons.Outlined.Language,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "预览 agent 正在浏览的页面",
                        style = AndmxTheme.typography.titleSmall,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "当 agent 使用 browse 工具抓取网页时,这里会同步显示它正在看的页面。你也可以在地址栏输入网址手动浏览。",
                        style = AndmxTheme.typography.bodySmall,
                        color = colors.textTertiary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        if (!state.isEmpty && error == null) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Core settings: JS + DOM storage + viewport for modern sites.
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    settings.userAgentString = settings.userAgentString + " AndMX/1.0"

                    // WebChromeClient is REQUIRED: without it, pages that use
                    // alert/confirm/console/JS dialogs or need geolocation will
                    // hang on a blank screen.
                    webChromeClient = android.webkit.WebChromeClient()

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            state.loading = true
                            url?.let { state.currentUrl = it; state.urlInput = it }
                            syncNav(view)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            state.loading = false
                            url?.let { state.currentUrl = it; state.urlInput = it }
                            syncNav(view)
                        }
                        // Intercept http:// → https:// upgrade for safety.
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ): Boolean {
                            request?.url?.let { u ->
                                if (u.scheme == "http" || u.scheme == "https") return false
                            }
                            return true
                        }
                        // Surface load errors so the UI isn't just a blank pane.
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            // Only treat the main frame as a load failure; sub-resource
                            // errors (ads/tracker) shouldn't blank the page.
                            if (request?.isForMainFrame == true) {
                                state.loading = false
                                state.loadError = error?.description?.toString()
                            }
                        }
                    }

                    // Fallback for GPU-render failures (seen on emulators / devices
                    // where Mesa render nodes are inaccessible): force software
                    // layer so the WebView still paints instead of showing blank.
                    runCatching { setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null) }

                    loadUrl(state.pendingLoad)
                    webView = this
                }
            },
            update = { view ->
                webView = view
                syncNav(view)
                if (state.pendingLoad != state.lastLoaded) {
                    state.lastLoaded = state.pendingLoad
                    view.loadUrl(state.pendingLoad)
                }
            },
        )
        } // end if !isEmpty
    }
}

@Stable
class BrowserPaneState(startUrl: String = "") {
    var urlInput by mutableStateOf(startUrl)
    var currentUrl by mutableStateOf(startUrl)
    var pendingLoad by mutableStateOf(startUrl)
    var lastLoaded by mutableStateOf("")
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** Set on a main-frame load failure so the UI can surface it. */
    var loadError by mutableStateOf<String?>(null)

    /** True when no URL has been loaded yet — used to show the preview empty state. */
    val isEmpty: Boolean get() = currentUrl.isBlank() && pendingLoad.isBlank()

    fun load(url: String) {
        urlInput = url
        currentUrl = url
        pendingLoad = url
        loadError = null
    }

    fun restore(url: String) {
        urlInput = url
        currentUrl = url
        pendingLoad = url
        lastLoaded = ""
        loading = false
        canGoBack = false
        canGoForward = false
    }

    fun persistableUrl(): String = currentUrl.ifBlank { pendingLoad.ifBlank { urlInput } }
}

@Composable
fun rememberBrowserPaneState(startUrl: String = ""): BrowserPaneState =
    remember { BrowserPaneState(startUrl) }

@Composable
private fun BrowserIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AndmxTheme.colors
    Icon(
        icon,
        contentDescription = label,
        tint = if (enabled) colors.textSecondary else colors.textTertiary,
        modifier = Modifier.size(18.dp).clip(Radii.sm)
            .clickable(enabled = enabled, onClick = onClick),
    )
}
