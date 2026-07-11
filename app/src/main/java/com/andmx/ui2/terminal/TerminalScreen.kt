package com.andmx.ui2.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.term.TerminalSession

private data class SpecialKey(val label: String, val bytes: String)

private val specialKeys = listOf(
    SpecialKey("Tab", "\t"),
    SpecialKey("Esc", "\u001b"),
    SpecialKey("^C", "\u0003"),
    SpecialKey("^D", "\u0004"),
    SpecialKey("^Z", "\u001a"),
    SpecialKey("^L", "\u000c"),
    SpecialKey("←", "\u001b[D"),
    SpecialKey("↑", "\u001b[A"),
    SpecialKey("↓", "\u001b[B"),
    SpecialKey("→", "\u001b[C"),
    SpecialKey("Home", "\u001b[H"),
    SpecialKey("End", "\u001b[F")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sessions = remember { mutableStateListOf<TerminalSession>() }
    var activeIndex by remember { mutableIntStateOf(0) }

    fun newSession() {
        val s = TerminalSession(context)
        sessions.add(s)
        activeIndex = sessions.size - 1
        runCatching { s.start() }
    }
    fun closeSession(index: Int) {
        if (index !in sessions.indices) return
        sessions[index].destroy()
        sessions.removeAt(index)
        activeIndex = activeIndex.coerceAtMost((sessions.size - 1).coerceAtLeast(0))
    }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) newSession()
    }
    DisposableEffect(Unit) {
        onDispose { sessions.forEach { it.destroy() } }
    }

    val active = sessions.getOrNull(activeIndex)
    val state = active?.let { it.state.collectAsState().value } ?: null

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("终端") },
            actions = {
                IconButton(onClick = { newSession() }) {
                    Icon(Icons.Outlined.Add, "新建终端")
                }
            }
        )

        if (sessions.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = activeIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0)),
                edgePadding = 8.dp,
                divider = {}
            ) {
                sessions.forEachIndexed { i, _ ->
                    Tab(
                        selected = i == activeIndex,
                        onClick = { activeIndex = i },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("终端 ${i + 1}", style = MaterialTheme.typography.labelMedium)
                                Box(
                                    Modifier
                                        .padding(start = 6.dp)
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .clickable { closeSession(i) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(13.dp))
                                }
                            }
                        }
                    )
                }
            }
        }

        if (state == null || active == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无终端会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        TerminalView(
            screen = state.screenText,
            coloredLines = state.coloredLines,
            modifier = Modifier.weight(1f)
        )

        SpecialKeyRow(enabled = state.running, onKey = { active.write(it) })

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var input by remember { mutableStateOf("") }
            CommandInput(
                value = input,
                onValueChange = { input = it },
                onSubmit = {
                    active.write(input + "\n")
                    input = ""
                },
                enabled = state.running,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                if (state.running) { active.write(input + "\n"); input = "" }
            }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "发送")
            }
        }
    }
}

@Composable
private fun SpecialKeyRow(enabled: Boolean, onKey: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        specialKeys.forEach { key ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled) { onKey(key.bytes) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    key.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun CommandInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                if (enabled) "输入命令…" else "终端未运行",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled) onSubmit() }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
