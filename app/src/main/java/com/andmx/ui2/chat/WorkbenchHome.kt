package com.andmx.ui2.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andmx.ui2.settings.tr
import com.andmx.ui2.theme.Chrome

@Composable
fun WorkbenchHome(
    suggestions: List<StarterSuggestion>,
    onPickSuggestion: (String) -> Unit,
    onFiles: () -> Unit,
    onTerminal: () -> Unit,
    onBrowser: () -> Unit,
    onWorkflows: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = Chrome.maxReadingWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Chrome.gutter, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchTool(Icons.Outlined.FolderOpen, tr("文件"), onFiles, Modifier.weight(1f))
                WorkbenchTool(Icons.Outlined.Terminal, tr("终端"), onTerminal, Modifier.weight(1f))
                WorkbenchTool(Icons.Outlined.Language, tr("浏览器"), onBrowser, Modifier.weight(1f))
                WorkbenchTool(Icons.Outlined.AccountTree, tr("工作流"), onWorkflows, Modifier.weight(1f))
            }
            if (suggestions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("任务建议"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StarterSuggestions(suggestions, onPickSuggestion)
                }
            }
        }
    }
}

@Composable
private fun WorkbenchTool(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .clip(Chrome.control)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 3.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            label,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
