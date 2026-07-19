package com.andmx.ui2.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController

@Composable
fun rememberClearFocusScrollConnection(): NestedScrollConnection {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(focusManager, keyboard) {
        ClearFocusScrollConnection(focusManager, keyboard)
    }
}

private class ClearFocusScrollConnection(
    private val focusManager: FocusManager,
    private val keyboard: SoftwareKeyboardController?,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y != 0f || available.x != 0f) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        return Offset.Zero
    }
}

fun Modifier.clearFocusOnScroll(connection: NestedScrollConnection): Modifier =
    this.nestedScroll(connection)

@Composable
fun Modifier.clearFocusOnBlankTap(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return this.pointerInput(focusManager, keyboard) {
        detectTapGestures(
            onTap = {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            },
        )
    }
}
