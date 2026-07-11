package com.andmx.ui2.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.term.TerminalEmulator

private val TermBg = Color(0xFF1A1A1F)
private val TermFg = Color(0xFFD4D4D4)

@Composable
fun TerminalView(
    screen: String,
    coloredLines: List<List<Pair<String, Int>>> = emptyList(),
    modifier: Modifier = Modifier
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var fontSize by remember { mutableFloatStateOf(12f) }

    LaunchedEffect(screen) {
        vScroll.animateScrollTo(vScroll.maxValue)
    }

    val annotated = remember(coloredLines, screen) {
        if (coloredLines.isEmpty()) AnnotatedString(screen.ifEmpty { " " })
        else buildColored(coloredLines)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TermBg)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    fontSize = (fontSize * zoom).coerceIn(7f, 22f)
                }
            }
            .verticalScroll(vScroll)
            .horizontalScroll(hScroll)
            .padding(12.dp)
    ) {
        SelectionContainer {
            Text(
                text = annotated,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.3f).sp,
                color = TermFg,
                softWrap = false
            )
        }
    }
}

private fun buildColored(lines: List<List<Pair<String, Int>>>): AnnotatedString =
    buildAnnotatedString {
        lines.forEachIndexed { idx, spans ->
            if (idx > 0) append('\n')
            if (spans.isEmpty()) { append(' '); return@forEachIndexed }
            spans.forEach { (text, colorIndex) ->
                if (colorIndex == 0) {
                    append(text)
                } else {
                    pushStyle(SpanStyle(color = Color(TerminalEmulator.colorArgb(colorIndex))))
                    append(text)
                    pop()
                }
            }
        }
    }
