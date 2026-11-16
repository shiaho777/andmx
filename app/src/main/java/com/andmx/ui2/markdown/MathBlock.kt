package com.andmx.ui2.markdown

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.andmx.ui2.theme.AndMXType

@Composable
fun MathBlock(tex: String, display: Boolean) {
    var renderError by remember(tex, display) { mutableStateOf<String?>(null) }
    var heightDp by remember(tex, display) { mutableStateOf(120.dp) }
    val isDark = isSystemInDarkTheme()
    val color = MaterialTheme.colorScheme.onSurface
    val colorHex = remember(color) {
        String.format(
            "#%02X%02X%02X",
            (color.red * 255).toInt().coerceIn(0, 255),
            (color.green * 255).toInt().coerceIn(0, 255),
            (color.blue * 255).toInt().coerceIn(0, 255),
        )
    }
    if (renderError != null) {
        Text(
            tex,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = AndMXType.micro.fontSize,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp),
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun onHeight(h: Int) {
                                post { heightDp = h.coerceIn(24, 640).dp }
                            }

                            @android.webkit.JavascriptInterface
                            fun onError(message: String) {
                                post { renderError = message }
                            }
                        },
                        "AndroidKatex",
                    )
                }
            },
            update = { webView ->
                val tag = tex + "|" + display + "|" + colorHex
                if (webView.tag != tag) {
                    webView.tag = tag
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/katex/",
                        katexHtml(tex, display, colorHex, isDark),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
        )
    }
}

private fun katexHtml(tex: String, display: Boolean, colorHex: String, isDark: Boolean): String {
    val encoded = android.util.Base64.encodeToString(
        tex.toByteArray(Charsets.UTF_8),
        android.util.Base64.NO_WRAP,
    )
    val scheme = if (isDark) "dark" else "light"
    return """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="$scheme">
<link rel="stylesheet" href="katex.min.css">
<script src="katex.min.js"></script>
<style>html,body{margin:0;padding:0;background:transparent;color:$colorHex}#m{padding:4px 2px;overflow-x:auto;overflow-y:hidden}.katex-display{margin:0}.katex-display>.katex{white-space:normal}</style>
</head><body><div id="m"></div>
<script>
function report(){var h=Math.ceil(document.body.scrollHeight);AndroidKatex.onHeight(h);}
try {
  var src = decodeURIComponent(escape(atob("$encoded")));
  katex.render(src, document.getElementById('m'), {displayMode:$display, throwOnError:true});
  report();
  if (document.fonts && document.fonts.ready) { document.fonts.ready.then(report); }
  window.addEventListener('load', report);
} catch (e) { AndroidKatex.onError(String(e && e.message || e)); }
</script></body></html>"""
}