package com.andmx.ui.conversation

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing
import java.io.File

internal data class ToolImageArtifact(
    val key: String,
    val label: String,
    val assetPath: String? = null,
    val dataUrl: String? = null,
    val imageUrl: String? = null,
)

internal data class ToolArtifactSummary(
    val images: List<ToolImageArtifact> = emptyList(),
)

internal fun toolArtifactSummary(item: ChatItem.ToolUse): ToolArtifactSummary {
    val output = item.output.orEmpty()
    val explicitImages = item.imageUrls.mapIndexed { index, url ->
        ToolImageArtifact(
            key = "explicit:$index:$url",
            label = "图像 ${index + 1}",
            dataUrl = url.takeIf { it.startsWith("data:image/") },
            imageUrl = url.takeUnless { it.startsWith("data:image/") },
        )
    }
    val references = messageReferences(output)
        .filter { it.kind == MessageReferenceKind.UI_REFERENCE && it.assetPath.isNotBlank() }
        .mapIndexed { index, ref ->
            ToolImageArtifact(
                key = "ref:$index:${ref.assetPath}",
                label = ref.label.ifBlank { "参考 ${index + 1}" },
                assetPath = ref.assetPath,
            )
        }
    val assetMarkers = assetMarkerRegex.findAll(output)
        .mapNotNull { match ->
            UiReferenceAssets.pathFromMarker(match.value)?.takeIf { it.isNotBlank() }
        }
        .mapIndexed { index, assetPath ->
            ToolImageArtifact(
                key = "asset:$index:$assetPath",
                label = "参考 ${index + 1}",
                assetPath = assetPath,
            )
        }
    val dataUrls = dataImageRegex.findAll(output)
        .mapIndexed { index, match ->
            ToolImageArtifact(
                key = "data:$index:${match.range.first}",
                label = "图像 ${index + 1}",
                dataUrl = match.value.replace("\n", "").replace(" ", ""),
            )
        }
    return ToolArtifactSummary(
        images = (explicitImages + references + assetMarkers + dataUrls).distinctBy { it.key },
    )
}

@Composable
internal fun ToolArtifactGallery(
    artifacts: List<ToolImageArtifact>,
    onOpenReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artifacts.isEmpty()) return
    val colors = AndmxTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        artifacts.forEach { artifact ->
            val bitmap = rememberToolArtifactBitmap(artifact)
            Column(
                modifier = Modifier
                    .size(width = 124.dp, height = 104.dp)
                    .border(1.dp, colors.border, Radii.sm)
                    .background(colors.codeBackground, Radii.sm)
                    .clickable(enabled = !artifact.assetPath.isNullOrBlank()) {
                        artifact.assetPath?.let(onOpenReference)
                    }
                    .padding(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(colors.sunken, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = artifact.label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    artifact.label,
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun rememberToolArtifactBitmap(artifact: ToolImageArtifact): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    return remember(context.filesDir, artifact.key, artifact.assetPath, artifact.dataUrl, artifact.imageUrl) {
        when {
            !artifact.dataUrl.isNullOrBlank() -> decodeDataUrl(artifact.dataUrl)
            !artifact.imageUrl.isNullOrBlank() -> decodeImageUrl(artifact.imageUrl)
            !artifact.assetPath.isNullOrBlank() -> decodeAssetFile(context.filesDir, artifact.assetPath)
            else -> null
        }
    }
}

private val dataImageRegex = Regex("""data:image/[^;]+;base64,[A-Za-z0-9+/=\n\r]+""")
private val assetMarkerRegex = Regex("""asset:[A-Za-z0-9/_\-.]+""")

private fun decodeDataUrl(dataUrl: String?): androidx.compose.ui.graphics.ImageBitmap? =
    dataUrl?.substringAfter("base64,", "")
        ?.takeIf { it.isNotBlank() }
        ?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }

private fun decodeAssetFile(filesDir: File, relativePath: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val cleanPath = relativePath?.trim().orEmpty()
    if (cleanPath.isBlank()) return null
    return runCatching {
        BitmapFactory.decodeFile(File(filesDir, cleanPath).path)?.asImageBitmap()
    }.getOrNull()
}

private fun decodeImageUrl(url: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val cleanUrl = url?.trim().orEmpty()
    if (cleanUrl.isBlank()) return null
    if (cleanUrl.startsWith("data:image/")) return decodeDataUrl(cleanUrl)
    val filePath = when {
        cleanUrl.startsWith("file://") -> cleanUrl.removePrefix("file://")
        cleanUrl.startsWith("/") -> cleanUrl
        else -> return null
    }
    return runCatching {
        BitmapFactory.decodeFile(filePath)?.asImageBitmap()
    }.getOrNull()
}
