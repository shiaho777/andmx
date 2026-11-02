package com.andmx.ui2.settings.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.BuildConfig
import com.andmx.ui2.settings.SettingsItem
import com.andmx.ui2.settings.SettingsScaffold
import com.andmx.ui2.settings.tr

private const val REPO_URL = "https://github.com/shiaho777/andmx"
private const val RELEASES_URL = "$REPO_URL/releases"

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = diagnostics()

    SettingsScaffold(title = "关于", onBack = onBack) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "AndMX",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                tr("Android 原生的 AI 编程 Agent"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )

            InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("构建变体", BuildConfig.FLAVOR)
            InfoRow("构建类型", BuildConfig.BUILD_TYPE)
            InfoRow("包名", BuildConfig.APPLICATION_ID)
            InfoRow("系统版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")

            Text(
                tr("应用信息"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            SettingsItem(
                icon = Icons.Outlined.OpenInNew,
                title = "查看最新版本",
                subtitle = "在浏览器打开 GitHub Releases",
                onClick = { openUrl(context, RELEASES_URL) },
            )
            SettingsItem(
                icon = Icons.Outlined.Info,
                title = "查看 GitHub 仓库",
                subtitle = REPO_URL,
                onClick = { openUrl(context, REPO_URL) },
            )
            SettingsItem(
                icon = Icons.Outlined.ContentCopy,
                title = "复制诊断信息",
                subtitle = "版本、设备与构建变体，便于反馈问题",
                onClick = {
                    copy(context, diagnostics)
                    Toast.makeText(context, tr("已复制诊断信息"), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
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
    appendLine("flavor=${BuildConfig.FLAVOR} buildType=${BuildConfig.BUILD_TYPE}")
    appendLine("package=${BuildConfig.APPLICATION_ID}")
    appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("locale=${java.util.Locale.getDefault()}")
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
