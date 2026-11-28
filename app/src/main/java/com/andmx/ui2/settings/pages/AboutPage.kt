package com.andmx.ui2.settings.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.andmx.BuildConfig
import com.andmx.R
import com.andmx.ui2.product.PRODUCT_REPO
import com.andmx.ui2.product.productBuildChannelLabel
import com.andmx.ui2.settings.SettingsItem
import com.andmx.ui2.settings.SettingsScaffold
import com.andmx.ui2.settings.UiLang
import com.andmx.ui2.settings.tr

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = diagnostics()
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(title = tr("关于"), onBack = onBack) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.andmx_block_m_mascot),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "AndMX",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        tr("Android 原生的 AI 编程 Agent"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            InfoRow(tr("版本"), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow(tr("渠道"), productBuildChannelLabel)
            InfoRow(tr("构建类型"), BuildConfig.BUILD_TYPE)
            InfoRow(tr("包名"), BuildConfig.APPLICATION_ID)
            InfoRow(tr("系统版本"), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow(tr("设备"), "${Build.MANUFACTURER} ${Build.MODEL}")

            Text(
                tr("应用信息"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            Text(
                tr("AndMX 在 Android 上连接你选用的模型，在项目中查看文件、审阅改动和运行已授权的操作。可先体验离线样例，再从一个文本文件开始。"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            InfoRow(tr("项目许可证"), tr("项目源码授权核验中"))
            SettingsItem(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = tr("离线使用指南"),
                onClick = { showGuide = true },
            )
            AboutLink(context, "项目文档", "$PRODUCT_REPO#readme")
            AboutLink(context, "第三方组件和授权", "$PRODUCT_REPO/blob/main/THIRD_PARTY.md")
            AboutLink(context, "版本记录", "$PRODUCT_REPO/releases")
            AboutLink(context, "维护者与贡献者", "$PRODUCT_REPO/graphs/contributors")
            AboutLink(context, "贡献指南", "$PRODUCT_REPO/blob/main/CONTRIBUTING.md")
            AboutLink(context, "在 GitHub 反馈", "$PRODUCT_REPO/issues")
            SettingsItem(
                icon = Icons.Outlined.ContentCopy,
                title = tr("预览并复制诊断信息"),
                subtitle = tr("包括版本、平台和构建信息。由你选择是否粘贴到反馈中。"),
                onClick = { reviewing = true },
            )
        }
    }

    if (showGuide) {
        val language = UiLang.tag
        val guide = remember(context, language) {
            context.assets.open("product/readme.$language.txt").bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(tr("离线使用指南")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(guide) } },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text(tr("完成")) } },
        )
    }

    if (reviewing) {
        AlertDialog(
            onDismissRequest = { reviewing = false },
            title = { Text(tr("诊断信息")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { Text(diagnostics) }
            },
            confirmButton = {
                TextButton(onClick = {
                    copy(context, diagnostics)
                    reviewing = false
                    Toast.makeText(context, tr("已复制诊断信息"), Toast.LENGTH_SHORT).show()
                }) { Text(tr("复制诊断信息")) }
            },
            dismissButton = { TextButton(onClick = { reviewing = false }) { Text(tr("取消")) } },
        )
    }
}

@Composable
private fun AboutLink(context: Context, title: String, url: String) {
    SettingsItem(
        icon = Icons.AutoMirrored.Outlined.OpenInNew,
        title = tr(title),
        onClick = { openUrl(context, url) },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            tr(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun diagnostics(): String = buildString {
    appendLine("AndMX ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("buildType=${BuildConfig.BUILD_TYPE}")
    appendLine("package=${BuildConfig.APPLICATION_ID}")
    appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("AndMX diagnostics", text))
}
