package com.andmx.ui2.ext

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.andmx.agent.ext.ExtensionSlotRegistry
import com.andmx.agent.ext.SlotDeclaration
import com.andmx.ui2.settings.tr
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ExtensionSlotIds {
    const val COMPOSER_STATUS = "composer.status"
    const val COMPOSER_MENTION = "composer.mention"
    const val SIDE_PANE_LAUNCHER = "sidePane.launcher"
    const val NAV_PRIMARY = "nav.primary"
    const val FILE_VIEWER = "file.viewer"
}

val LocalExtensionSlots: ProvidableCompositionLocal<StateFlow<List<SlotDeclaration>>> =
    compositionLocalOf { ExtensionSlotRegistry.declarations }

fun declarationsForSlot(all: List<SlotDeclaration>, slotId: String): List<SlotDeclaration> =
    all.filter { it.slotId == slotId }

fun slotEntryKey(declaration: SlotDeclaration): String =
    declaration.pluginId + "|" + declaration.htmlAsset

fun extensionOf(fileName: String): String =
    fileName.substringAfterLast('.', "").lowercase()

fun viewerExtensions(htmlAsset: String): List<String> =
    htmlAsset.substringAfterLast('/')
        .removeSuffix(".html")
        .split('.')
        .drop(1)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

fun matchesFileViewer(declaration: SlotDeclaration, fileName: String): Boolean {
    val ext = extensionOf(fileName)
    return ext.isNotEmpty() && ext in viewerExtensions(declaration.htmlAsset)
}

fun fileViewerDeclaration(all: List<SlotDeclaration>, fileName: String): SlotDeclaration? =
    declarationsForSlot(all, ExtensionSlotIds.FILE_VIEWER)
        .firstOrNull { matchesFileViewer(it, fileName) }

object SlotDeclarationCodec {
    fun encode(declaration: SlotDeclaration): String = buildJsonObject {
        put("pluginId", declaration.pluginId)
        put("slotId", declaration.slotId)
        put("title", declaration.title)
        put("htmlAsset", declaration.htmlAsset)
    }.toString()

    fun decode(raw: String): SlotDeclaration? = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        SlotDeclaration(
            pluginId = obj.stringValue("pluginId"),
            slotId = obj.stringValue("slotId"),
            title = obj.stringValue("title"),
            htmlAsset = obj.stringValue("htmlAsset"),
        )
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String {
        val primitive = getValue(key).jsonPrimitive
        if (!primitive.isString) throw IllegalArgumentException("field $key must be a string")
        return primitive.content
    }
}

@Composable
fun ExtensionSlotHost(
    slotId: String,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
) {
    val declarations by LocalExtensionSlots.current.collectAsState()
    val matched = remember(declarations, slotId) { declarationsForSlot(declarations, slotId) }
    if (matched.isEmpty()) return
    var expandedKey by remember(slotId) { mutableStateOf<String?>(null) }
    if (inline) {
        matched.forEach { declaration ->
            val key = slotEntryKey(declaration)
            SlotEntryChip(
                title = declaration.title,
                expanded = expandedKey == key,
                onClick = { expandedKey = if (expandedKey == key) null else key },
            )
        }
        matched.firstOrNull { slotEntryKey(it) == expandedKey }?.let { declaration ->
            ExtensionSlotWebView(
                declaration,
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
    } else {
        Column(modifier) {
            matched.forEach { declaration ->
                val key = slotEntryKey(declaration)
                SlotEntryRow(
                    title = declaration.title,
                    expanded = expandedKey == key,
                    onClick = { expandedKey = if (expandedKey == key) null else key },
                )
                if (expandedKey == key) {
                    ExtensionSlotWebView(
                        declaration,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotEntryRow(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Extension,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlotEntryChip(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (expanded) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Extension,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ExtensionSlotWebView(
    declaration: SlotDeclaration,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val html = remember(declaration.htmlAsset) {
        runCatching {
            context.assets.open(declaration.htmlAsset).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }
    if (html == null) {
        Text(
            tr("插件页面不可用：${declaration.title}"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(8.dp),
        )
        return
    }
    AndroidView(
        modifier = modifier.heightIn(min = 80.dp, max = 400.dp),
        factory = { ctx -> createSlotWebView(ctx, html) },
        onRelease = { view -> view.destroy() },
    )
}

internal fun createSlotWebView(context: Context, html: String): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.blockNetworkLoads = true
        settings.blockNetworkImage = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true
        }
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }