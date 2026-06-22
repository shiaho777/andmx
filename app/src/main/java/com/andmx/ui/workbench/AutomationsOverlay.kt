package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.andmx.settings.Automation
import com.andmx.settings.SettingsStore
import com.andmx.ui.rememberScreenHeightDp
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Saved one-tap prompts — the 自动化 page. Running one sends it as a turn. */
@Composable
fun AutomationsOverlay(onRun: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val list by store.automations.collectAsStateSafe()

    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().imePadding().navigationBarsPadding().clip(Radii.lg).background(colors.surfaceElevated).padding(Spacing.xl),
        ) {
            Text("自动化 · 保存的提示", style = AndmxTheme.typography.titleLarge, color = colors.textPrimary)
            Spacer(Modifier.height(Spacing.md))

            // Cap to ~45% of screen height so the dialog's fixed input rows + this
            // list never overflow a short landscape viewport.
            val listMaxHeight = (rememberScreenHeightDp() * 0.45f).dp
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMaxHeight)) {
                if (list.isEmpty()) {
                    item { Text("还没有自动化。下面新建一个。", style = AndmxTheme.typography.bodySmall, color = colors.textTertiary) }
                }
                items(list) { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = AndmxTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.prompt, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("运行", style = AndmxTheme.typography.labelMedium, color = colors.accent,
                            modifier = Modifier.clip(Radii.sm).clickable { onRun(a.prompt); onDismiss() }.padding(horizontal = Spacing.sm, vertical = Spacing.xs))
                        Text("删除", style = AndmxTheme.typography.labelSmall, color = colors.warning,
                            modifier = Modifier.clip(Radii.sm).clickable { scope.launch { store.saveAutomations(list - a) } }.padding(horizontal = Spacing.sm, vertical = Spacing.xs))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))
            EditLine("标题", title) { title = it }
            Spacer(Modifier.height(Spacing.sm))
            EditLine("提示内容", prompt) { prompt = it }
            Spacer(Modifier.height(Spacing.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Text(
                    "添加", style = AndmxTheme.typography.labelLarge, color = colors.onAccent,
                    modifier = Modifier.clip(Radii.sm).background(colors.sendActive)
                        .clickable {
                            if (title.isNotBlank() && prompt.isNotBlank()) {
                                scope.launch { store.saveAutomations(list + Automation(title.trim(), prompt.trim())) }
                                title = ""; prompt = ""
                            }
                        }
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun EditLine(label: String, value: String, onChange: (String) -> Unit) {
    val colors = AndmxTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = AndmxTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().border(1.dp, colors.border, Radii.sm).padding(horizontal = Spacing.md, vertical = Spacing.md),
        )
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.collectAsStateSafe(): State<List<T>> =
    collectAsState(initial = emptyList())
