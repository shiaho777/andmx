package com.andmx.ui2.product

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val LocalOpenProductIntro = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun ProductIntroHost(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext
    val preferences = remember { ProductPreferences(app) }
    var firstRun by rememberSaveable { mutableStateOf(preferences.shouldShowIntro()) }
    var replay by rememberSaveable { mutableStateOf(false) }
    if (firstRun) {
        FirstTaskScreen(resumeIntro = true) {
            preferences.completeIntro()
            firstRun = false
        }
    } else {
        CompositionLocalProvider(LocalOpenProductIntro provides { replay = true }) {
            content()
            if (replay) {
                Dialog(onDismissRequest = { replay = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        FirstTaskScreen { replay = false }
                    }
                }
            } else {
                PreviewUpdateNotice(preferences)
            }
        }
    }
}
