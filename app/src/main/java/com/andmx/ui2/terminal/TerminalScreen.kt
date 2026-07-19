package com.andmx.ui2.terminal

import androidx.compose.ui.text.font.FontFamily
import android.graphics.Typeface as AndroidTypeface
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
fun TerminalScreen(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    colors: TerminalColors = rememberTerminalColors(),
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val appSettings by settingsStore.settings.collectAsState(initial = ProviderSettings())
    val terminalFontFamily = remember(appSettings.terminalFontFamily) {
        resolveTerminalFontFamily(appSettings.terminalFontFamily)
    }
    val sessions = remember { mutableStateListOf<TerminalSession>() }
    var activeIndex by remember { mutableIntStateOf(0) }

    fun newSession() {
        val s = TerminalSession(context)
        sessions.add(s)
        activeIndex = sessions.lastIndex
        runCatching { s.start() }
    }

    fun closeSession(index: Int) {
        if (index !in sessions.indices) return
        val wasActive = index == activeIndex
        sessions[index].destroy()
        sessions.removeAt(index)
        activeIndex = when {
            sessions.isEmpty() -> 0
            index < activeIndex -> (activeIndex - 1).coerceAtLeast(0)
            wasActive -> activeIndex.coerceAtMost(sessions.lastIndex)
            else -> activeIndex.coerceAtMost(sessions.lastIndex)
        }
        if (sessions.isEmpty()) newSession()
    }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) newSession()
    }
    DisposableEffect(Unit) {
        onDispose { sessions.forEach { it.destroy() } }
    }

    val tabCount = sessions.size
    val selectedTab = when {
        tabCount <= 0 -> 0
        activeIndex < 0 -> 0
        activeIndex >= tabCount -> tabCount - 1
        else -> activeIndex
    }

    val active = sessions.getOrNull(selectedTab)
    val state = active?.let { it.state.collectAsState().value }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.chrome)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "终端",
                style = MaterialTheme.typography.titleSmall,
                color = colors.chromeContent,
                modifier = Modifier.padding(start = 12.dp),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { newSession() }) {
                Icon(Icons.Outlined.Add, "新建终端", tint = colors.chromeContent)
            }
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, "关闭终端", tint = colors.chromeContent)
                }
            }
        }

        if (tabCount > 1) {
            key(tabCount, selectedTab) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 8.dp,
                    containerColor = colors.chrome,
                    contentColor = colors.chromeContent,
                    divider = {},
                ) {
                    repeat(tabCount) { i ->
                        Tab(
                            selected = i == selectedTab,
                            onClick = { activeIndex = i },
                            selectedContentColor = colors.chromeContent,
                            unselectedContentColor = colors.chromeMuted,
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("终端 ${i + 1}", style = MaterialTheme.typography.labelMedium)
                                    Box(
                                        Modifier
                                            .padding(start = 6.dp)
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .clickable { closeSession(i) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            "关闭",
                                            Modifier.size(13.dp),
                                            tint = colors.chromeMuted,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = colors.keyBackground)

        if (state == null || active == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无终端会话", color = colors.chromeMuted)
            }
            return@Column
        }

        TerminalView(
            screen = state.screenText,
            coloredLines = state.coloredLines,
            colors = colors,
            fontFamily = terminalFontFamily,
            modifier = Modifier.weight(1f),
        )

        SpecialKeyRow(
            enabled = state.running,
            colors = colors,
            fontFamily = terminalFontFamily,
            onKey = { active.write(it) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.chrome)
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
                colors = colors,
                fontFamily = terminalFontFamily,
            modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                if (state.running) {
                    active.write(input + "\n")
                    input = ""
                }
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardReturn,
                    "发送",
                    tint = if (state.running) colors.chromeContent else colors.chromeMuted,
                )
            }
        }
    }
}

@Composable
private fun SpecialKeyRow(
    enabled: Boolean,
    colors: TerminalColors,
    fontFamily: FontFamily = FontFamily.Monospace,
    onKey: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.chrome)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        specialKeys.forEach { key ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.keyBackground)
                    .clickable(enabled = enabled) { onKey(key.bytes) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    key.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = fontFamily),
                    color = if (enabled) colors.keyContent else colors.chromeMuted,
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
    colors: TerminalColors,
    fontFamily: FontFamily = FontFamily.Monospace,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.inputBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                if (enabled) "输入命令…" else "终端未运行",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily),
                color = colors.chromeMuted,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = fontFamily,
                fontSize = 14.sp,
                color = colors.foreground,
            ),
            cursorBrush = SolidColor(colors.cursor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled) onSubmit() }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}


private fun resolveTerminalFontFamily(name: String): FontFamily {
    val raw = name.trim()
    if (raw.isEmpty()) return FontFamily.Monospace
    val first = raw.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return FontFamily.Monospace
    return try {
        val tf = AndroidTypeface.create(first, AndroidTypeface.NORMAL) ?: return FontFamily.Monospace
        FontFamily(tf)
    } catch (_: Throwable) {
        FontFamily.Monospace
    }
}
