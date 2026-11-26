package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andmx.ui2.markdown.MarkdownHighlight
import com.andmx.ui2.markdown.MarkdownView
import com.andmx.ui2.theme.Chrome

@Composable
fun StreamingText(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    highlightRanges: List<MarkdownHighlight> = emptyList(),
    selectionActions: SelectionActions? = null,
) {
    val contentColor = if (muted) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(modifier.fillMaxWidth()) {
        if (text.isNotBlank() || !isStreaming) {
            MarkdownView(
                markdown = text,
                streaming = isStreaming,
                contentColor = contentColor,
                bodySizeSp = if (muted) 13.5f else 15f,
                highlightRanges = highlightRanges,
                selectionActions = selectionActions,
            )
        }
        if (isStreaming) {
            DisableSelection {
                StreamingSignal()
            }
        }
    }
}

@Composable
private fun StreamingSignal() {
    Box(
        Modifier
            .padding(top = 7.dp, bottom = 4.dp)
            .size(width = 12.dp, height = 3.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant, Chrome.tile)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    )
}
