package com.andmx.ui2.chat

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlin.math.roundToInt

data class SelectionActions(
    val onCopy: (String) -> Unit,
    val onQuote: (String) -> Unit,
    val onSideChat: ((String) -> Unit)? = null,
    val onAnnotate: ((String) -> Unit)? = null,
)

private data class ActiveSelection(val text: String, val anchor: Offset)

@Composable
fun SelectableWithToolbar(
    actions: SelectionActions,
    content: @Composable () -> Unit,
) {
    val platformToolbar = LocalTextToolbar.current
    val clipboard = LocalClipboardManager.current
    var active by remember { mutableStateOf<ActiveSelection?>(null) }
    var containerTopLeft by remember { mutableStateOf(Offset.Zero) }
    val toolbar = remember(platformToolbar, clipboard) {
        SelectionTextToolbar(
            delegate = platformToolbar,
            clipboard = clipboard,
            onSelection = { text, rect -> active = ActiveSelection(text, Offset(rect.center.x, rect.top)) },
            onCleared = { active = null },
        )
    }
    val lift = with(LocalDensity.current) { 52.dp.toPx() }
    DisposableEffect(toolbar) {
        onDispose { toolbar.hide() }
    }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        SelectionContainer {
            Box(Modifier.onGloballyPositioned { containerTopLeft = it.boundsInWindow().topLeft }) {
                content()
            }
        }
    }
    var annotating by remember { mutableStateOf<String?>(null) }
    val selection = active
    if (selection != null) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(
                (selection.anchor.x - containerTopLeft.x).roundToInt().coerceAtLeast(0),
                (selection.anchor.y - containerTopLeft.y - lift).roundToInt(),
            ),
            onDismissRequest = { active = null },
        ) {
            androidx.compose.foundation.text.selection.DisableSelection {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { actions.onCopy(selection.text); active = null }) { Text(tr("复制")) }
                        TextButton(onClick = { actions.onQuote(selection.text); active = null }) { Text(tr("引用")) }
                        val annotate = actions.onAnnotate
                        if (annotate != null) {
                            TextButton(onClick = { annotating = selection.text; active = null }) { Text(tr("批注")) }
                        }
                        val side = actions.onSideChat
                        if (side != null) {
                            TextButton(onClick = { side(selection.text); active = null }) { Text(tr("侧聊")) }
                        }
                    }
                }
            }
        }
    }
    val annotateText = annotating
    if (annotateText != null) {
        var comment by remember(annotateText) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { annotating = null },
            title = { Text(tr("批注选中内容")) },
            text = {
                Column {
                    Text(
                        ChatFind.quote(annotateText).lineSequence().take(4).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        placeholder = { Text(tr("写下你的点评…")) },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onAnnotate?.invoke(ChatFind.annotate(annotateText, comment))
                        annotating = null
                        active = null
                    },
                    enabled = comment.isNotBlank(),
                ) { Text(tr("插入批注")) }
            },
            dismissButton = {
                TextButton(onClick = { annotating = null }) { Text(tr("取消")) }
            },
        )
    }
}

private class SelectionTextToolbar(
    private val delegate: TextToolbar,
    private val clipboard: ClipboardManager,
    private val onSelection: (String, Rect) -> Unit,
    private val onCleared: () -> Unit,
) : TextToolbar {

    override val status: TextToolbarStatus
        get() = delegate.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val text = if (onCopyRequested != null) extractSelectionText(onCopyRequested) else null
        if (!text.isNullOrBlank()) onSelection(text, rect)
    }

    override fun hide() {
        onCleared()
        delegate.hide()
    }

    private fun extractSelectionText(copy: () -> Unit): String? {
        val native = clipboard.nativeClipboard
        val previous = runCatching { native.primaryClip }.getOrNull()
        return runCatching {
            copy()
            val text = clipboard.getText()?.text
            if (previous != null) native.setPrimaryClip(previous)
            else native.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            text
        }.getOrNull()
    }
}
