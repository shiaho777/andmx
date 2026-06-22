package com.andmx.ui.workbench

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing
import java.io.File

internal data class UiReferencePreviewInfo(
    val relativePath: String,
    val exists: Boolean,
    val byteSize: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
) {
    val title: String get() = relativePath.substringAfterLast('/').ifBlank { "UI 参考" }
    val detail: String
        get() = listOfNotNull(
            "${width}x$height".takeIf { width > 0 && height > 0 },
            byteSize.takeIf { it > 0L }?.let(::formatPreviewBytes),
        ).joinToString(" · ")
}

internal fun uiReferencePreviewInfo(filesDir: File, relativePath: String): UiReferencePreviewInfo {
    val clean = relativePath.trim().removePrefix("asset:").trimStart('/')
    val file = File(filesDir, clean)
    val root = filesDir.canonicalFile
    val canonical = runCatching { file.canonicalFile }.getOrNull()
    if (clean.isBlank() || canonical == null || (canonical.path != root.path && !canonical.path.startsWith(root.path + File.separator))) {
        return UiReferencePreviewInfo(clean, exists = false)
    }
    val bounds = runCatching {
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(canonical.path, this)
        }
    }.getOrNull()
    return UiReferencePreviewInfo(
        relativePath = clean,
        exists = canonical.isFile,
        byteSize = canonical.takeIf { it.isFile }?.length() ?: 0L,
        width = bounds?.outWidth?.takeIf { it > 0 } ?: 0,
        height = bounds?.outHeight?.takeIf { it > 0 } ?: 0,
    )
}

@Composable
fun UiReferencePreviewPane(
    assetPath: String?,
    onRunCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cleanPath = assetPath?.trim().orEmpty()
    val info = remember(context.filesDir, cleanPath) { uiReferencePreviewInfo(context.filesDir, cleanPath) }
    val imageBitmap = remember(context.filesDir, info.relativePath, info.exists) {
        if (!info.exists) null else runCatching {
            BitmapFactory.decodeFile(File(context.filesDir, info.relativePath).path)?.asImageBitmap()
        }.getOrNull()
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        Row(
            Modifier.fillMaxWidth().height(42.dp).padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(
                info.title,
                style = AndmxTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ReferenceAction("复制路径", Icons.Outlined.ContentCopy) {
                if (info.relativePath.isNotBlank()) clipboard.setText(AnnotatedString("asset:${info.relativePath}"))
            }
            Spacer(Modifier.width(Spacing.xs))
            ReferenceAction("/trace", Icons.Outlined.TrackChanges) { onRunCommand("/trace") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        if (cleanPath.isBlank()) {
            EmptyReferencePreview("还没有选中 UI 参考", "点击消息里的截图参考, 或运行 /references 查看已接收截图。", onRunCommand)
            return@Column
        }
        if (!info.exists || imageBitmap == null) {
            EmptyReferencePreview("本地截图资产不可用", "路径: ${info.relativePath.ifBlank { cleanPath }}", onRunCommand)
            return@Column
        }

        Column(Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(Radii.md)
                    .background(colors.sunken)
                    .border(1.dp, colors.border, Radii.md),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "UI 参考截图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(Spacing.md),
                )
            }
            Column(
                Modifier.fillMaxWidth().clip(Radii.sm).background(colors.surface)
                    .border(1.dp, colors.border, Radii.sm)
                    .padding(Spacing.md),
            ) {
                Text(info.relativePath, style = AndmxTheme.typography.bodySmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (info.detail.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(info.detail, style = AndmxTheme.typography.labelSmall, color = colors.textSecondary)
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ReferenceTextAction("/references") { onRunCommand("/references") }
                    ReferenceTextAction("/screenshot-extract") { onRunCommand("/screenshot-extract") }
                    ReferenceTextAction("/visual-check") { onRunCommand("/visual-check") }
                }
            }
        }
    }
}

@Composable
private fun EmptyReferencePreview(title: String, detail: String, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = AndmxTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(Modifier.height(Spacing.sm))
            Text(detail, style = AndmxTheme.typography.bodySmall, color = colors.textSecondary)
            Spacer(Modifier.height(Spacing.md))
            ReferenceTextAction("/references") { onRunCommand("/references") }
        }
    }
}

@Composable
private fun ReferenceAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Box(Modifier.size(28.dp).clip(Radii.sm).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ReferenceTextAction(label: String, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Text(
        label,
        style = AndmxTheme.typography.labelSmall,
        color = colors.accent,
        modifier = Modifier.clip(Radii.sm).clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

private fun formatPreviewBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return "${String.format(java.util.Locale.US, "%.1f", value).removeSuffix(".0")} ${units[unit]}"
}
