package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.andmx.term.TerminalSession
import com.andmx.ui.theme.AndmxCodeTextStyle
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/**
 * A real interactive shell into the proot guest. Output is rendered by an
 * ANSI buffer; the input row sends lines to the PTY, and quick keys cover the
 * common control sequences.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalPane(session: TerminalSession, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = AndmxTheme.colors

    androidx.compose.runtime.LaunchedEffect(session) { session.start() }

    val state by session.state.collectAsState()
    val scroll = rememberScrollState()
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the input field when the terminal pane becomes visible,
    // so the user can start typing immediately without tapping.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300) // wait for layout to settle
        runCatching { focusRequester.requestFocus() }
    }

    // Measure the monospace cell so we can map pane size -> terminal rows/cols.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cell = remember(measurer) {
        val m = measurer.measure("MMMMMMMMMM", style = AndmxCodeTextStyle)
        (m.size.width / 10f) to m.size.height.toFloat()
    }

    // Autoscroll to the bottom as new output arrives.
    LaunchedEffect(state.revision) { scroll.animateScrollTo(scroll.maxValue) }

    fun sendInput() {
        val text = input.trim()
        if (text.isNotEmpty()) {
            session.write(text + "\n")
        }
        input = ""
    }

    Column(modifier = modifier.fillMaxSize().background(colors.sunken)) {
        // Status bar — hidden in compact mode to save vertical space.
        if (!compact) {
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("guest@alpine", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(state.status, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        // console output — in compact mode this fills all remaining space and
        // the input field is overlaid transparently on top of it, so tapping
        // anywhere in the output focuses the hidden input and routes keystrokes
        // to the PTY. No separate input row or quick-key bar is shown.
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { sz ->
                    val (cw, ch) = cell
                    if (cw > 0 && ch > 0) {
                        val padPx = with(density) { (Spacing.md * 2).toPx() }
                        val cols = ((sz.width - padPx) / cw).toInt().coerceIn(20, 400)
                        val rows = ((sz.height - padPx) / ch).toInt().coerceIn(4, 200)
                        if (rows != session.rows || cols != session.cols) session.resize(rows, cols)
                    }
                }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .verticalScroll(scroll),
        ) {
            Text(text = session.screen, style = AndmxCodeTextStyle, color = colors.textPrimary)
            // Compact: transparent input overlaid on the output so the user
            // taps the screen to type, no extra UI chrome.
            if (compact) {
                BasicTextField(
                    value = input,
                    onValueChange = { newText ->
                        if (newText.contains('\n')) {
                            input = newText.replace("\n", "")
                            sendInput()
                        } else {
                            input = newText
                        }
                    },
                    textStyle = AndmxCodeTextStyle.copy(color = androidx.compose.ui.graphics.Color.Transparent),
                    cursorBrush = SolidColor(colors.accent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendInput() }),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester),
                )
            }
        }

        if (!compact) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        // quick control keys
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            KeyChip("Ctrl-C") { session.write("\u0003") }
            KeyChip("Ctrl-D") { session.write("\u0004") }
            KeyChip("Tab") { session.write("\t") }
            KeyChip("↑") { session.write("\u001b[A") }
            KeyChip("Esc") { session.write("\u001b") }
            KeyChip("清屏") { session.write("clear\n") }
        }

        // input line. BasicTextField is placed directly (not wrapped in a
        // clickable Box) so touches reach it natively. A transparent clickable
        // overlay sits on top ONLY when the field is empty (placeholder showing)
        // to grab the tap and route it to focusRequester — once the user starts
        // typing, the overlay disappears and the field handles its own touch.
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Keyboard, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BasicTextField(
                    value = input,
                    onValueChange = { newText ->
                        // Many soft keyboards (especially Chinese IMEs) insert a
                        // newline character when the user presses Send/Enter,
                        // instead of firing the onSend callback. Detect that here
                        // and treat it as a send action.
                        if (newText.contains('\n')) {
                            input = newText.replace("\n", "")
                            sendInput()
                        } else {
                            input = newText
                        }
                    },
                    textStyle = AndmxCodeTextStyle.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendInput() }),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp && e.key == Key.Enter) {
                                sendInput(); true
                            } else false
                        },
                )
                // Placeholder overlay — only visible when empty, and clickable
                // to focus the field beneath it.
                if (input.isEmpty()) {
                    Text(
                        "输入命令,回车执行",
                        style = AndmxCodeTextStyle,
                        color = colors.textTertiary,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { runCatching { focusRequester.requestFocus() } },
                    )
                }
            }
        }
        } // end if (!compact)
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Text(
        text = label,
        style = AndmxTheme.typography.labelSmall,
        color = colors.textSecondary,
        modifier = Modifier
            .padding(end = Spacing.xs)
            .clip(Radii.sm)
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}
