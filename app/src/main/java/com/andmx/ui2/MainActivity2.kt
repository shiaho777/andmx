package com.andmx.ui2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.chat.ChatScreen
import com.andmx.ui2.theme.AndMX2Theme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val store = remember { SettingsStore(context) }
            val settings by store.settings.collectAsState(initial = ProviderSettings())

            AndMX2Theme(themeMode = settings.themeMode) {
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

