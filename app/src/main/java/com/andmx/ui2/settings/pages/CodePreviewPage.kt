package com.andmx.ui2.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.markdown.CodeBlockThemed
import com.andmx.ui2.markdown.CodeTheme
import com.andmx.ui2.markdown.CodeThemes
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow
import com.andmx.ui2.settings.SwitchRow
import com.andmx.ui2.settings.backAppBar
import kotlinx.coroutines.launch

private const val SAMPLE = """fun greet(name: String): String {
    // 生成问候语
    val greeting = "Hello, ${'$'}name!"
    return greeting.repeat(2)
}"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodePreviewPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val s by store.settings.collectAsState(initial = ProviderSettings())
    val scope = rememberCoroutineScope()
    fun save(u: ProviderSettings) { scope.launch { store.update(u) } }

    Scaffold(topBar = { backAppBar("代码预览", onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SettingsGroup("实时预览") {
                Text(
                    "代码块将按当前界面主题使用对应配色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                CodeBlockThemed(
                    code = SAMPLE,
                    theme = CodeThemes.byId(if (dark) s.darkCodeTheme else s.lightCodeTheme),
                    showLineNumbers = s.showLineNumbers,
                    wrapLongLines = s.wrapLongLines,
                    fontSize = s.codeFontSize
                )
            }

            SettingsGroup("深色代码主题") {
                ThemeRow(
                    themes = CodeThemes.darkThemes,
                    selected = s.darkCodeTheme,
                    onSelect = { save(s.copy(darkCodeTheme = it)) }
                )
            }

            SettingsGroup("浅色代码主题") {
                ThemeRow(
                    themes = CodeThemes.lightThemes,
                    selected = s.lightCodeTheme,
                    onSelect = { save(s.copy(lightCodeTheme = it)) }
                )
            }

            SettingsGroup("显示") {
                SwitchRow(
                    title = "显示行号",
                    description = "在代码预览中显示每一行的行号。",
                    checked = s.showLineNumbers,
                    onCheckedChange = { save(s.copy(showLineNumbers = it)) }
                )
                SwitchRow(
                    title = "长行自动换行",
                    description = "内容过长时在预览区域内自动换行。",
                    checked = s.wrapLongLines,
                    onCheckedChange = { save(s.copy(wrapLongLines = it)) }
                )
            }

            SettingsGroup("代码字号") {
                StackedSettingRow(
                    title = "字号：${s.codeFontSize}sp",
                    description = "调整代码预览的默认字号。"
                ) {
                    Slider(
                        value = s.codeFontSize.toFloat(),
                        onValueChange = { save(s.copy(codeFontSize = it.toInt())) },
                        valueRange = 10f..20f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(
    themes: List<CodeTheme>,
    selected: String,
    onSelect: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(themes) { theme ->
            ThemeSwatch(theme, selected == theme.id) { onSelect(theme.id) }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: CodeTheme, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(theme.background)
                .padding(8.dp)
        ) {
            SwatchLine(theme.keyword, 0.5f)
            SwatchLine(theme.string, 0.8f)
            SwatchLine(theme.function, 0.35f)
            SwatchLine(theme.number, 0.6f)
        }
        Text(
            theme.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun SwatchLine(color: androidx.compose.ui.graphics.Color, widthFraction: Float) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth(widthFraction)
            .height(6.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}
