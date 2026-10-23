package com.andmx.ui2

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.chat.ChatScreen
import com.andmx.ui2.theme.AndMX2Theme
import dagger.hilt.android.AndroidEntryPoint

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestProjection(intent)

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

    companion object {
        const val EXTRA_REQUEST_PROJECTION = "request_projection"
    }
}

