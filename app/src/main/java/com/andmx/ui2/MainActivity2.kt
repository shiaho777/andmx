package com.andmx.ui2

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.andmx.computeruse.MediaProjectionManagerHolder
import com.andmx.computeruse.ScreenCaptureService
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import com.andmx.mcp.McpOAuthFlow
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.chat.ChatScreen
import com.andmx.ui2.chat.InboundBus
import com.andmx.ui2.theme.AndMX2Theme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity2 : ComponentActivity() {
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        MediaProjectionManagerHolder.provideGrant(result.resultCode, result.data)
        if (MediaProjectionManagerHolder.isAuthorized) {
            ScreenCaptureService.start(this)
        }
    }

    private var projectionRequested = false

    override fun onStart() {
        super.onStart()
        if (!projectionRequested || MediaProjectionManagerHolder.isAuthorized) return
        projectionRequested = false
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        com.andmx.ui2.chat.TurnNotifier.appForeground = true
    }

    override fun onPause() {
        super.onPause()
        com.andmx.ui2.chat.TurnNotifier.appForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRequestProjection(intent)
        handleInboundIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestProjection(intent)
        handleInboundIntent(intent)

        setContent {
            val context = LocalContext.current
            val store = remember { SettingsStore(context) }
            val settings by store.settings.collectAsState(initial = ProviderSettings())

            val localeTag = settings.locale
            androidx.compose.runtime.SideEffect {
                com.andmx.ui2.settings.UiLang.apply(localeTag)
            }
            AndMX2Theme(themeMode = settings.themeMode) {
                val density = LocalDensity.current
                val fontScale = density.fontScale * (settings.uiFontSizePx.coerceIn(12, 20) / 14f)
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.andmx.ui2.markdown.LocalCodePreviewConfig provides
                            com.andmx.ui2.markdown.CodePreviewConfig(
                                lightTheme = settings.lightCodeTheme,
                                darkTheme = settings.darkCodeTheme,
                                showLineNumbers = settings.showLineNumbers,
                                wrapLongLines = settings.wrapLongLines,
                                fontSize = settings.codeFontSize
                            )
                    ) {
                        ChatScreen(modifier = Modifier.fillMaxSize())
                    }
                }
                }
            }
        }
    }

    private fun maybeRequestProjection(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_PROJECTION, false) != true) return
        intent.removeExtra(EXTRA_REQUEST_PROJECTION)
        if (MediaProjectionManagerHolder.isAuthorized) return
        projectionRequested = true
    }

    /**
     * 消费从系统进来的分享/外链/通知点击。处理完清掉对应 extra，
     * 避免配置变更重建 Activity 时重复注入。
     */
    private fun handleInboundIntent(intent: Intent?) {
        if (intent == null) return
        val oauthUri = intent.data
        if (intent.action == Intent.ACTION_VIEW &&
            oauthUri?.scheme == McpOAuthFlow.SCHEME &&
            oauthUri.host == McpOAuthFlow.HOST
        ) {
            intent.data = null
            intent.action = null
            lifecycleScope.launch {
                val result = McpOAuthFlow.complete(applicationContext, oauthUri)
                android.widget.Toast.makeText(
                    this@MainActivity2,
                    result.message,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        intent.getLongExtra(EXTRA_CONVERSATION_ID, 0L).takeIf { it > 0L }?.let {
            InboundBus.openConversation(it)
            intent.removeExtra(EXTRA_CONVERSATION_ID)
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                when {
                    type.startsWith("image/") -> {
                        val uri = streamUri(intent)
                        if (uri != null) {
                            val copied = copyInboundImage(uri)
                            if (copied != null) {
                                InboundBus.shareImage(Uri.fromFile(copied.first).toString(), copied.second)
                            } else {
                                // 复制失败时保留原始授权 URI，至少本次进程内还能读。
                                InboundBus.shareImage(uri.toString(), displayName(uri) ?: "分享图片")
                            }
                        }
                    }
                    type == "text/plain" || type.startsWith("text/") -> {
                        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        if (!text.isNullOrBlank()) InboundBus.shareText(text)
                    }
                }
                intent.removeExtra(Intent.EXTRA_TEXT)
                intent.removeExtra(Intent.EXTRA_STREAM)
                intent.action = null
            }
            Intent.ACTION_VIEW -> {
                val url = intent.dataString.orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    InboundBus.shareText(url)
                }
                intent.data = null
                intent.action = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * ACTION_SEND 的 URI 授权只在本次 Activity 生命周期内有效。先复制到
     * 私有 cache，用户之后才发送也不会失效。
     */
    private fun copyInboundImage(uri: Uri): Pair<File, String>? = runCatching {
        val name = displayName(uri)?.takeIf { it.isNotBlank() } ?: "分享图片"
        val ext = name.substringAfterLast('.', "").takeIf {
            it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit)
        } ?: "jpg"
        val dir = File(cacheDir, "inbound").apply { mkdirs() }
        val file = File(dir, "share-${UUID.randomUUID()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        file to name
    }.getOrNull()

    companion object {
        const val EXTRA_REQUEST_PROJECTION = "request_projection"
        const val EXTRA_CONVERSATION_ID = "conversationId"
    }
}

