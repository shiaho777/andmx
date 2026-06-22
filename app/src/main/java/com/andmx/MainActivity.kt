package com.andmx

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.andmx.computeruse.MediaProjectionManagerHolder
import com.andmx.settings.SettingsStore
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.workbench.WorkbenchScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Shared intent text from other apps (deep sharing). */
    private var sharedText by mutableStateOf<String?>(null)

    /** MediaProjection consent launcher — registered once at activity creation. */
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Forward the user's consent result to the holder so ScreenCaptor can
        // build a VirtualDisplay from it. data==null ⇒ user denied.
        MediaProjectionManagerHolder.provideGrant(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // ── Deep sharing: parse incoming share intent (Android advantage) ──
        handleShareIntent(intent)

        setContent {
            val store = remember { SettingsStore(applicationContext) }
            val settings by store.settings.collectAsState(initial = com.andmx.settings.ProviderSettings())

            val dark = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            val accent = remember(settings.accent) {
                runCatching { Color(android.graphics.Color.parseColor(settings.accent)) }
                    .getOrDefault(com.andmx.ui.theme.AndmxPalette.Blue)
            }

            AndmxTheme(darkTheme = dark, accent = accent) {
                com.andmx.ui.setup.SetupGate {
                    WorkbenchScreen(
                        isDark = dark,
                        sharedText = sharedText,
                        onSharedTextConsumed = { sharedText = null },
                        // Launch the system projection-consent dialog when the UI requests it.
                        onRequestScreenCapture = {
                            if (!MediaProjectionManagerHolder.isAuthorized) {
                                val mgr = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                                    as android.media.projection.MediaProjectionManager
                                projectionLauncher.launch(mgr.createScreenCaptureIntent())
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (dark) com.andmx.ui.theme.AndmxPalette.DarkCanvas else com.andmx.ui.theme.AndmxPalette.LightCanvas),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Parse share intent using AndroidContextProvider. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val provider = com.andmx.android.AndroidContextProvider(applicationContext)
        sharedText = provider.parseShareIntent(intent)
    }
}

