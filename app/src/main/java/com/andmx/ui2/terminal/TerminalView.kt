package com.andmx.ui2.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.term.StyledSpan
import com.andmx.term.TerminalEmulator

internal fun splitTerminalLines(screen: String): List<String> =
    if (screen.isEmpty()) listOf(" ") else screen.split("\n")

@Composable
fun TerminalView(
    screen: String,
    coloredLines: List<List<Pair<String, Int>>> = emptyList(),
    modifier: Modifier = Modifier,
    colors: TerminalColors = rememberTerminalColors(),
    fontFamily: FontFamily = FontFamily.Monospace,
    styledLines: List<List<StyledSpan>> = emptyList(),
    followOutput: Boolean = true,
    trimUnusedRows: Boolean = false,
) {
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    var fontSize by remember { mutableFloatStateOf(12f) }

    val plainLines = remember(styledLines.isEmpty(), coloredLines.isEmpty(), screen) {
        if (styledLines.isEmpty() && coloredLines.isEmpty()) splitTerminalLines(screen) else emptyList()
    }
    val lineCount = when {
        trimUnusedRows -> (when {
            styledLines.isNotEmpty() -> styledLines.indexOfLast { it.isNotEmpty() }
            coloredLines.isNotEmpty() -> coloredLines.indexOfLast { it.isNotEmpty() }
            else -> plainLines.indexOfLast { it.isNotBlank() }
        } + 1).coerceAtLeast(1)
        styledLines.isNotEmpty() -> styledLines.size
        coloredLines.isNotEmpty() -> coloredLines.size
        else -> plainLines.size
    }

    LaunchedEffect(followOutput, screen, lineCount) {
        if (followOutput && lineCount > 0) listState.scrollToItem(lineCount - 1)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    fontSize = (fontSize * zoom).coerceIn(7f, 22f)
                }
            }
            .horizontalScroll(hScroll)
            .padding(12.dp)
    ) {
        SelectionContainer {
            LazyColumn(state = listState) {
                items(lineCount, key = { it }) { index ->
                    val styled = styledLines.getOrNull(index)
                    val colored = coloredLines.getOrNull(index)
                    val plain = plainLines.getOrNull(index)
                    val line = remember(styled, colored, plain, colors) {
                        when {
                            styled != null -> buildStyledLine(styled, colors)
                            colored != null -> buildColoredLine(colored, colors)
                            else -> AnnotatedString(plain.orEmpty())
                        }
                    }
                    Text(
                        text = line,
                        fontFamily = fontFamily,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.3f).sp,
                        color = colors.foreground,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private fun buildColoredLine(
    spans: List<Pair<String, Int>>,
    colors: TerminalColors,
): AnnotatedString =
    buildAnnotatedString {
        if (spans.isEmpty()) {
            append(' ')
            return@buildAnnotatedString
        }
        spans.forEach { (text, colorIndex) ->
            if (colorIndex == 0) {
                append(text)
            } else {
                pushStyle(SpanStyle(color = Color(colors.colorArgb(colorIndex))))
                append(text)
                pop()
            }
        }
    }

private fun buildStyledLine(
    spans: List<StyledSpan>,
    colors: TerminalColors,
): AnnotatedString =
    buildAnnotatedString {
        if (spans.isEmpty()) {
            append(' ')
            return@buildAnnotatedString
        }
        spans.forEach { span ->
            pushStyle(span.toSpanStyle(colors))
            append(span.text)
            pop()
        }
    }

private fun StyledSpan.toSpanStyle(colors: TerminalColors): SpanStyle {
    var fgColor = resolveColor(fg, colors.foreground, colors)
    var bgColor = resolveColor(bg, colors.background, colors)
    if (inverse) {
        val tmp = fgColor
        fgColor = bgColor
        bgColor = tmp
    }
    if (dim) fgColor = fgColor.copy(alpha = fgColor.alpha * 0.6f)
    return SpanStyle(
        color = fgColor,
        background = if (bg != 0 || inverse) bgColor else Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

private fun resolveColor(index: Int, default: Color, colors: TerminalColors): Color = when {
    index == 0 -> default
    index <= 16 -> Color(colors.colorArgb(index))
    index >= TerminalEmulator.TRUECOLOR_FLAG -> Color(0xFF000000.toInt() or (index - TerminalEmulator.TRUECOLOR_FLAG))
    else -> Color(TerminalEmulator.colorArgb256(index - 17))
}
