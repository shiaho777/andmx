package com.andmx.ui2.product

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andmx.BuildConfig
import com.andmx.ui2.settings.SettingsScaffold
import com.andmx.ui2.settings.tr
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val updateChannelAvailable = BuildConfig.GITHUB_PREVIEW && !BuildConfig.DEBUG

@Composable
internal fun PreviewUpdateNotice(preferences: ProductPreferences) {
    if (!updateChannelAvailable) return
    val app = LocalContext.current.applicationContext
    var release by remember { mutableStateOf<PreviewRelease?>(null) }
    var details by remember { mutableStateOf(false) }
    var changelog by remember { mutableStateOf(preferences.changelogPending()) }
    LaunchedEffect(changelog) {
        val now = System.currentTimeMillis()
        if (changelog || !preferences.automaticUpdates || now - preferences.lastCheckTime in 0 until 86400000L) {
            return@LaunchedEffect
        }
        try {
            release = PreviewUpdates(app).check()?.takeIf { it.versionCode != preferences.skippedVersion }
            preferences.lastCheckTime = now
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            preferences.lastCheckTime = now
        }
    }

    if (changelog) {
        val notes = remember(app) { bundledChangelog(app) }
        val scope = rememberCoroutineScope()
        var dismissing by remember { mutableStateOf(false) }
        val onClose = {
            if (!dismissing) {
                dismissing = true
                scope.launch {
                    if (withContext(Dispatchers.IO) { preferences.markChangelogSeen() }) changelog = false
                    dismissing = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("${tr("此版本的改动")} · ${BuildConfig.VERSION_NAME}") },
            text = { ReleaseNotes(notes) },
            confirmButton = { TextButton(onClick = onClose, enabled = !dismissing) { Text(tr("完成")) } },
        )
    } else if (details) {
        val close = {
            details = false
            release = null
        }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            PreviewUpdatePage(onBack = close, initialRelease = release)
        }
    } else {
        release?.let { available ->
            AlertDialog(
                onDismissRequest = { release = null },
                title = { Text(tr("有新的 AndMX 预览版")) },
                text = { Text(available.versionName) },
                confirmButton = {
                    TextButton(onClick = { details = true }) { Text(tr("查看更新")) }
                },
                dismissButton = {
                    TextButton(onClick = { release = null }) { Text(tr("稍后")) }
                },
            )
        }
    }
}

@Composable
internal fun PreviewUpdatePage(onBack: () -> Unit, initialRelease: PreviewRelease? = null) {
    val context = LocalContext.current
    val app = context.applicationContext
    val preferences = remember { ProductPreferences(app) }
    val updater = remember { PreviewUpdates(app) }
    val scope = rememberCoroutineScope()
    var automatic by remember { mutableStateOf(preferences.automaticUpdates) }
    var release by remember { mutableStateOf(initialRelease) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf<File?>(null) }
    var sourceDialog by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }

    fun runUpdate(action: suspend () -> Unit) {
        status = ""
        progress = ""
        operation = scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status = "暂时无法获取或验证更新，请重试。"
            } finally {
                operation = null
            }
        }
    }

    SettingsScaffold(title = tr("应用更新"), onBack = onBack) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleLarge)
            Text(tr(if (updateChannelAvailable) "GitHub 预览渠道" else "本地构建"))
            Text(tr("更新从 GitHub 获取，验证后交由 Android 安装。"))
            if (updateChannelAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("自动检查更新"), Modifier.weight(1f))
                    Switch(checked = automatic, onCheckedChange = {
                        automatic = it
                        preferences.automaticUpdates = it
                    })
                }
                OutlinedButton(onClick = {
                    runUpdate {
                        verified = null
                        release = updater.check()
                        preferences.lastCheckTime = System.currentTimeMillis()
                        if (release == null) status = "没有更新的预览版。"
                    }
                }, enabled = operation == null) { Text(tr("检查 GitHub 更新")) }
            }
            release?.let { selected ->
                Text(selected.versionName, style = MaterialTheme.typography.titleMedium)
                if (selected.notes.isNotBlank()) Text(selected.notes)
                if (verified == null) {
                    Button(enabled = operation == null, onClick = {
                        runUpdate {
                            verified = updater.download(selected) { bytes, total ->
                                scope.launch { progress = if (total > 0) "${bytes * 100 / total}%" else "${bytes / 1024} KB" }
                            }
                            progress = ""
                            status = "已验证下载内容和签名。"
                        }
                    }) { Text(tr("下载并验证")) }
                    TextButton(enabled = operation == null, onClick = {
                        preferences.skippedVersion = selected.versionCode
                        release = null
                        status = "已跳过此版本。手动检查仍可找到它。"
                    }) { Text(tr("跳过此版本")) }
                } else {
                    Button(enabled = operation == null, onClick = {
                        val file = verified ?: return@Button
                        if (!app.packageManager.canRequestPackageInstalls()) {
                            sourceDialog = true
                        } else {
                            runUpdate { context.startActivity(updater.installer(file, selected)) }
                        }
                    }) { Text(tr("打开系统安装器")) }
                }
            }
            if (progress.isNotEmpty() && operation != null) Text(progress)
            if (status.isNotEmpty()) Text(tr(status))
            operation?.let { running ->
                TextButton(onClick = {
                    running.cancel()
                    status = "已取消。"
                }) { Text(tr("取消")) }
            }
            TextButton(onClick = { showChanges = true }) { Text(tr("此版本的改动")) }
            TextButton(onClick = { openProductLink(context, "$PRODUCT_REPO/releases") }) {
                Text(tr("在浏览器打开 GitHub Releases"))
            }
        }
    }

    if (sourceDialog) {
        val close = { sourceDialog = false }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(tr("允许 AndMX 发起安装")) },
            text = { Text(tr("Android 需要为此来源单独授权。授权后返回这里打开安装器。")) },
            confirmButton = {
                TextButton(onClick = {
                    close()
                    openProductSettings(context, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                }) { Text(tr("打开系统设置")) }
            },
            dismissButton = { TextButton(onClick = close) { Text(tr("取消")) } },
        )
    }

    if (showChanges) {
        val notes = remember(app) { bundledChangelog(app) }
        AlertDialog(
            onDismissRequest = { showChanges = false },
            title = { Text(tr("此版本的改动")) },
            text = { ReleaseNotes(notes) },
            confirmButton = { TextButton(onClick = { showChanges = false }) { Text(tr("完成")) } },
        )
    }
}

private fun bundledChangelog(app: Context): String {
    val suffix = if (com.andmx.ui2.settings.UiLang.tag == "en") "en" else "zh"
    val text = app.assets.open("product/changelog.$suffix.txt").bufferedReader().use { it.readText() }
    val installedSection = text.removePrefix("## ").split("\n## ").firstOrNull { it.substringBefore('\n').trim() == BuildConfig.VERSION_NAME }
    return installedSection?.substringAfter('\n')?.trim() ?: tr("可在 GitHub Releases 查阅版本记录。")
}

@Composable
private fun ReleaseNotes(notes: String) {
    Column(Modifier.verticalScroll(rememberScrollState())) { Text(notes) }
}

internal fun openProductLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

internal fun openProductSettings(context: Context, action: String, packageUri: Boolean = true) {
    val uri = Uri.fromParts("package", context.packageName, null)
    val intent = Intent(action).apply { if (packageUri) data = uri }
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }
    }
}
