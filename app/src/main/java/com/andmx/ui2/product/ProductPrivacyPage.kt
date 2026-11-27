package com.andmx.ui2.product

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.andmx.computeruse.AndmxAccessibilityService
import com.andmx.computeruse.MediaProjectionManagerHolder
import com.andmx.ui2.files.hasAllFilesAccess
import com.andmx.ui2.settings.SettingsPage
import com.andmx.ui2.settings.SettingsScaffold
import com.andmx.ui2.settings.tr

private data class ProductPermissionStatus(
    val files: Boolean,
    val mic: Boolean,
    val notifications: Boolean,
    val install: Boolean,
    val accessibility: Boolean,
    val capture: Boolean,
)

@Composable
internal fun ProductPrivacyPage(onBack: () -> Unit, navigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    var status by remember(context) { mutableStateOf(permissionStatus(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = permissionStatus(context) }

    SettingsScaffold(title = tr("隐私与权限"), onBack = onBack) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(tr("离线样例保存在应用内。首次真实任务只会在确认后发送所选文件的副本和任务说明。"))
            PrivacyItem(
                "模型请求",
                "对话内容及你选用的附件或工具结果可能发送到所配置的模型端点。可在模型设置中更换供应商。",
                actionLabel = "模型设置",
                onClick = { navigate(SettingsPage.MODEL) },
            )
            PrivacyItem(
                "MCP 服务器",
                "启用的 MCP 服务器会收到分配给它的调用参数。可在 MCP 设置中停用服务器。",
                actionLabel = "管理 MCP 服务器",
                onClick = { navigate(SettingsPage.MCP) },
            )
            PrivacyItem(
                "浏览器控制",
                "网页及抓取功能连接你访问的网站。网页被选作任务输入时，内容可能随模型请求发送。",
                actionLabel = "浏览器控制",
                onClick = { navigate(SettingsPage.BROWSER) },
            )
            PrivacyItem(
                "文件访问",
                "首次试用只读取系统选择器授予的文件。工作区的直接路径访问可能需要所有文件访问权限。",
                if (Build.VERSION.SDK_INT >= 30) status.files else null,
                "打开系统设置",
                {
                    val action = if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    openProductSettings(context, action)
                },
            )
            PrivacyItem(
                "麦克风",
                "语音输入使用麦克风，并在设备上转录。你仍可通过键盘输入。",
                status.mic,
                "打开系统设置",
                { openProductSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
            )
            PrivacyItem(
                "Android 无障碍",
                "Android Use 可以通过无障碍服务执行点按、滑动和输入。你可在 Android 设置中关闭服务。",
                status.accessibility,
                "打开无障碍设置",
                { openProductSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, packageUri = false) },
            )
            PrivacyItem(
                "屏幕捕获",
                "Android Use 会在使用时请求系统授权。捕获的画面可能成为任务的工具结果。",
                status.capture,
                "打开 Android Use",
                { navigate(SettingsPage.COMPUTER) },
            )
            PrivacyItem(
                "通知",
                "允许通知可在任务完成时收到提醒。关闭后仍可在工作台查看结果。",
                status.notifications,
                "打开系统设置",
                {
                    if (Build.VERSION.SDK_INT >= 26) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }.onFailure { openProductSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
                    }
                },
            )
            PrivacyItem(
                "应用安装",
                "安装更新或项目 APK 时，Android 会打开系统安装器，并要求你确认。",
                status.install,
                "打开应用更新",
                { navigate(SettingsPage.UPDATES) },
            )
            PrivacyItem(
                "联网更新",
                "检查应用更新会连接 GitHub。可在应用更新中关闭自动检查。",
                actionLabel = "应用更新",
                onClick = { navigate(SettingsPage.UPDATES) },
            )
            TextButton(onClick = { openProductLink(context, "$PRODUCT_REPO/blob/main/PRIVACY.md") }) {
                Text(tr("查看完整隐私说明"))
            }
        }
    }
}

@Composable
private fun PrivacyItem(
    title: String,
    description: String,
    granted: Boolean? = null,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Text(tr(title), style = MaterialTheme.typography.titleMedium)
        Text(tr(description), style = MaterialTheme.typography.bodyMedium)
        if (granted != null) {
            Text(tr(if (granted) "已启用" else "已关闭"), style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onClick) { Text(tr(actionLabel)) }
    }
}

private fun permissionStatus(context: Context): ProductPermissionStatus {
    val accessibility = context.getSystemService(AccessibilityManager::class.java)
    val serviceClass = AndmxAccessibilityService::class.java.name
    return ProductPermissionStatus(
        files = hasAllFilesAccess(),
        mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        install = context.packageManager.canRequestPackageInstalls(),
        accessibility = accessibility?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == serviceClass } == true,
        capture = MediaProjectionManagerHolder.isAuthorized,
    )
}
