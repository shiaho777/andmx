package com.andmx.agent.ext

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class ExtensionSandbox(
    private val context: Context,
    private val scope: CoroutineScope,
    private val allowedRequestUrls: Set<String> = emptySet(),
) {
    class JsBridge internal constructor(
        private val view: WebView,
        private val bridge: ExtensionBridge,
        private val scope: CoroutineScope,
    ) {
        @JavascriptInterface
        fun postMessage(message: String) {
            scope.launch {
                val response = bridge.handleMessage(message) ?: return@launch
                val script = "window.__andmxExtReceive&&window.__andmxExtReceive(${JSONObject.quote(response)})"
                view.post { runCatching { view.evaluateJavascript(script, null) } }
            }
        }
    }

    fun create(bridge: ExtensionBridge): WebView? = runCatching {
        WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.setGeolocationEnabled(false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url?.toString().orEmpty()
                    return if (shouldBlock(url, allowedRequestUrls)) emptyResponse() else null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = true
            }
            addJavascriptInterface(JsBridge(this, bridge, scope), BRIDGE_NAME)
        }
    }.getOrNull()

    fun loadPluginHtml(view: WebView, html: String) {
        view.loadDataWithBaseURL(null, wrapWithCsp(html), "text/html", "utf-8", null)
    }

    companion object {
        const val BRIDGE_NAME = "__andmxExt"
        const val CSP =
            "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "connect-src 'none'; img-src data:; frame-ancestors 'none'"
        const val MAX_HTML_BYTES = 1024 * 1024

        fun shouldBlock(url: String, allowedRequestUrls: Set<String>): Boolean =
            url !in allowedRequestUrls

        fun emptyResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        fun wrapWithCsp(html: String): String {
            val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">"
            val headStart = html.indexOf("<head", ignoreCase = true)
            if (headStart >= 0) {
                val headEnd = html.indexOf('>', headStart)
                if (headEnd >= 0) {
                    return html.substring(0, headEnd + 1) + meta + html.substring(headEnd + 1)
                }
            }
            return meta + html
        }
    }
}
