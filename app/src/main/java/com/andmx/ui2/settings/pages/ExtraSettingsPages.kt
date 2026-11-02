package com.andmx.ui2.settings.pages

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.agent.WebFetchCache
import com.andmx.agent.hooks.HookTrustStore
import com.andmx.agent.hooks.ProjectHooks
import com.andmx.agent.plugins.PluginSystem
import com.andmx.data.CronAutomationEntity
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.chat.SessionShortcuts
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.SwitchRow
import com.andmx.data.CronStore
import com.andmx.exec.PersistentShell
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.ui2.settings.backAppBar
import com.andmx.ui2.settings.tr
import com.andmx.workspace.ProjectManager
import com.andmx.workspace.WorkspaceAccess
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CronStore(context) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val items by produceState(emptyList<CronAutomationEntity>(), tick) {
        value = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) }
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var cron by remember { mutableStateOf("0 9 * * *") }
    var recurring by remember { mutableStateOf(true) }
    var conversationId by remember { mutableStateOf(0L) }
    var createNote by remember { mutableStateOf("") }
    val conversations by produceState(emptyList<com.andmx.data.ConversationEntity>(), tick) {
        value = withContext(Dispatchers.IO) {
            val dao = com.andmx.data.AndmxDatabase.get(context).dao()
            (dao.conversationsByArchived(false) + dao.conversationsByArchived(true))
                .sortedByDescending { maxOf(it.updatedAt, it.createdAt) }
                .take(30)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { backAppBar("自动化", onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(tr("当前工作区里已保存的定时任务。到点后在绑定的会话里跑一轮。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(tr("新建"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(tr("标题")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(tr("到点要做的事")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = cron,
                onValueChange = { cron = it },
                label = { Text(tr("cron，分 时 日 月 周")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("重复"), modifier = Modifier.weight(1f))
                Switch(checked = recurring, onCheckedChange = { recurring = it })
            }
            if (conversations.isNotEmpty()) {
                Text(tr("跑在：${conversations.firstOrNull { it.id == conversationId }?.title ?: conversations.first().title}"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                conversations.take(8).forEach { conv ->
                    TextButton(onClick = { conversationId = conv.id }) {
                        Text(if (conv.id == conversationId || (conversationId == 0L && conv.id == conversations.first().id)) "● ${conv.title}" else conv.title)
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    val target = if (conversationId > 0L) conversationId else conversations.firstOrNull()?.id ?: 0L
                    if (target <= 0L || title.isBlank() || prompt.isBlank()) {
                        createNote = "需要标题、内容和一个已有会话"
                        return@OutlinedButton
                    }
                    scope.launch {
                        val (_, err) = withContext(Dispatchers.IO) {
                            store.create(target, title.trim(), prompt.trim(), cron.trim(), null, recurring, null, null, null, "")
                        }
                        createNote = err ?: "已添加"
                        if (err == null) {
                            title = ""
                            prompt = ""
                            tick++
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("添加定时任务")) }
            if (createNote.isNotBlank()) Text(createNote, modifier = Modifier.padding(top = 8.dp))
            if (items.isEmpty()) {
                Text(tr("还没有定时任务。"), modifier = Modifier.padding(top = 16.dp))
            }
            items.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                buildString {
                                    append(item.cronExpr.ifBlank { "闲时" })
                                    append(" · ")
                                    append(item.lifecycleStatus)
                                    if (item.nextRunAt > 0) append(" · 下次 ${fmt.format(Date(item.nextRunAt))}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = item.enabled && item.lifecycleStatus == "active",
                            onCheckedChange = { on ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        store.update(item.id, null, null, null, null, null, null, null, on)
                                    }
                                    tick++
                                }
                            },
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(item.id) }
                            tick++
                        }
                    }) { Text(tr("删除")) }
                }
            }
        }
    }
}

private val hookEvents = listOf(
    "SessionStart",
    "UserPromptSubmit",
    "PreToolUse",
    "PermissionRequest",
    "PostToolUse",
    "PostToolUseFailure",
    "PreCompact",
    "PostCompact",
    "Stop",
)

private data class UserHookDraft(
    val event: String,
    val command: String,
    val matcher: String,
    val name: String,
    val timeoutMs: String = "60000",
    val async: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HooksPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val fs = remember { GuestFs(ProotRuntime(context)) }
    val system = remember { PluginSystem(context, fs, PersistentShell(context)) }
    val scope = rememberCoroutineScope()
    val path = "/root/.andmx/hooks.json"
    var drafts by remember { mutableStateOf(listOf<UserHookDraft>()) }
    var note by remember { mutableStateOf("") }
    val projectManager = remember { ProjectManager(context) }
    val access = remember { WorkspaceAccess(context) }
    val hostPath by projectManager.hostPath.collectAsState()
    val trustRevision by HookTrustStore.revision.collectAsState()
    var projectRefresh by remember { mutableIntStateOf(0) }
    var projectLoaded by remember { mutableStateOf(false) }
    val pluginHooks by produceState(emptyMap<String, List<PluginSystem.HookEntry>>()) {
        value = withContext(Dispatchers.IO) { runCatching { system.allHooks() }.getOrDefault(emptyMap()) }
    }
    val projectSnapshot by produceState<ProjectHooks.Snapshot?>(null, hostPath, trustRevision, projectRefresh) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ProjectHooks.load(access, ProjectHooks.workspaceKey(projectManager))
            }.getOrNull()
        }
        projectLoaded = true
    }
    androidx.compose.runtime.LaunchedEffect(path) {
        drafts = withContext(Dispatchers.IO) { readUserHooks(fs, path) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { backAppBar("Hooks", onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(tr("用户钩子写在 hooks.json。匹配器是工具名上的正则，留空则对该事件的全部工具生效。下次会话加载时读入。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                tr("项目钩子 (.zcode/hooks.json)"),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                tr("项目钩子随仓库分发，默认不执行。信任绑定到当前文件内容，文件一旦修改会重新要求审查。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            val snapshot = projectSnapshot
            when {
                hostPath.isNullOrBlank() -> {
                    Text(tr("未打开工作区。"), modifier = Modifier.padding(top = 8.dp))
                }
                !projectLoaded -> {
                    Text(tr("正在读取项目钩子…"), modifier = Modifier.padding(top = 8.dp))
                }
                snapshot == null -> {
                    Text(tr("当前项目没有 .zcode/hooks.json。"), modifier = Modifier.padding(top = 8.dp))
                }
                else -> {
                    val previousHash = HookTrustStore.trustedHash(snapshot.workspaceKey)
                    val status = when {
                        snapshot.trusted -> tr("项目钩子已信任，将随会话加载执行。")
                        previousHash != null -> tr("钩子文件已变更，需要重新审查后才能执行。")
                        else -> tr("项目钩子未信任，不会执行。审查后点击信任。")
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        tr("文件指纹") + " " + snapshot.fingerprint.take(16),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (snapshot.hooks.isEmpty()) {
                        Text(tr("文件里没有可执行的有效钩子。"), modifier = Modifier.padding(top = 8.dp))
                    }
                    snapshot.hooks.forEach { hook ->
                        Text(
                            "${hook.event} · ${hook.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (hook.matcher.isNotBlank()) {
                            Text(tr("匹配器 ${hook.matcher}"), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            hook.command,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !snapshot.trusted && snapshot.hooks.isNotEmpty(),
                            onClick = {
                                HookTrustStore.trust(snapshot.workspaceKey, snapshot.fingerprint)
                                note = "已信任项目钩子"
                            },
                        ) { Text(tr("信任并启用")) }
                        OutlinedButton(
                            enabled = snapshot.trusted || previousHash != null,
                            onClick = {
                                HookTrustStore.revoke(snapshot.workspaceKey)
                                note = "已撤销项目钩子信任"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text(tr("撤销信任")) }
                        TextButton(onClick = { projectRefresh++ }) { Text(tr("刷新")) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            drafts.forEachIndexed { index, draft ->
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { next -> drafts = drafts.replaceAt(index, draft.copy(name = next)) },
                    label = { Text(tr("名称")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    singleLine = true,
                )
                Text(tr("事件"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                hookEvents.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { event ->
                            TextButton(onClick = { drafts = drafts.replaceAt(index, draft.copy(event = event)) }) {
                                Text(if (draft.event == event) "● $event" else event)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.matcher,
                    onValueChange = { next -> drafts = drafts.replaceAt(index, draft.copy(matcher = next)) },
                    label = { Text(tr("匹配器")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.command,
                    onValueChange = { next -> drafts = drafts.replaceAt(index, draft.copy(command = next)) },
                    label = { Text(tr("命令")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = draft.timeoutMs,
                    onValueChange = { next -> drafts = drafts.replaceAt(index, draft.copy(timeoutMs = next.filter { it.isDigit() })) },
                    label = { Text("超时（秒）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("异步"), modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.async,
                        onCheckedChange = { on -> drafts = drafts.replaceAt(index, draft.copy(async = on)) },
                    )
                }
                TextButton(onClick = { drafts = drafts.filterIndexed { i, _ -> i != index } }) { Text(tr("删除")) }
            }
            Row(Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = {
                    drafts = drafts + UserHookDraft("PreToolUse", "", "", "")
                }) { Text(tr("添加")) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { writeUserHooks(fs, path, drafts) }
                            note = if (saved) "已写入 $path" else "写入失败"
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(tr("保存")) }
            }
            if (note.isNotBlank()) Text(note, modifier = Modifier.padding(top = 8.dp))
            Text(tr("插件钩子"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
            if (pluginHooks.isEmpty()) {
                Text(tr("没有已启用的插件钩子。"), modifier = Modifier.padding(top = 8.dp))
            }
            pluginHooks.forEach { (event, entries) ->
                Text(event, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                entries.forEach { hook ->
                    Text(hook.name.ifBlank { hook.command }, style = MaterialTheme.typography.bodyMedium)
                    if (hook.matcher.isNotBlank()) {
                        Text(tr("匹配器 ${hook.matcher}"), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr("超时 ${hook.timeoutMs} ms"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        hook.command,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun List<UserHookDraft>.replaceAt(index: Int, value: UserHookDraft): List<UserHookDraft> =
    mapIndexed { i, item -> if (i == index) value else item }

private fun readUserHooks(fs: GuestFs, path: String): List<UserHookDraft> {
    if (!fs.exists(path)) return emptyList()
    val raw = runCatching { fs.readText(path) }.getOrDefault("")
    val arr = runCatching { JSONArray(raw) }.getOrElse {
        runCatching { JSONObject(raw).optJSONArray("hooks") }.getOrNull()
    } ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val command = obj.optString("command")
            if (command.isBlank()) continue
            add(
                UserHookDraft(
                    event = obj.optString("event"),
                    command = command,
                    matcher = obj.optString("matcher"),
                    name = obj.optString("name"),
                    timeoutMs = (
                        obj.optLong("timeout", 0L).takeIf { it > 0 }
                            ?: (obj.optLong("timeout_ms", obj.optLong("timeoutMs", 60_000L)) / 1000L)
                        ).coerceAtLeast(1L).toString(),
                    async = obj.optBoolean("async"),
                ),
            )
        }
    }
}

private fun writeUserHooks(fs: GuestFs, path: String, drafts: List<UserHookDraft>): Boolean {
    val arr = JSONArray()
    drafts.filter { it.command.isNotBlank() && it.event.isNotBlank() }.forEach { draft ->
        arr.put(
            JSONObject()
                .put("event", draft.event.trim())
                .put("command", draft.command)
                .put("matcher", draft.matcher.trim())
                .put("name", draft.name)
                .put("timeout", draft.timeoutMs.toLongOrNull()?.coerceAtLeast(1L) ?: 60L)
                .put("timeout_ms", (draft.timeoutMs.toLongOrNull()?.coerceAtLeast(1L) ?: 60L) * 1000L)
                .put("async", draft.async),
        )
    }
    return runCatching { fs.writeText(path, arr.toString(2)) }.isSuccess
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerUsePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val status by produceState(AndroidUseStatus(false, false), refresh) {
        value = readAndroidUseStatus(context)
    }
    val store = remember { SettingsStore(context) }
    val settings by store.settings.collectAsState(initial = ProviderSettings())
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { backAppBar("Android Use", onBack) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            SwitchRow(
                title = "启用 Android Use",
                description = "关闭后，新会话不再提供屏幕操作工具。",
                checked = settings.androidUseEnabled,
                onCheckedChange = { on ->
                    scope.launch { store.update(settings.copy(androidUseEnabled = on)) }
                },
            )
            StatusLine("无障碍服务", if (status.accessibility) "已开启" else "未开启")
            StatusLine("屏幕录制", if (status.projection) "这次运行已授权" else "尚未授权")
            Text(tr("无障碍用来点击、滑动和读取当前界面。屏幕录制只在这次运行里有效，进程退出后需要重新允许。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text(tr("打开无障碍设置")) }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(context, com.andmx.ui2.MainActivity2::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra(com.andmx.ui2.MainActivity2.EXTRA_REQUEST_PROJECTION, true)
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (status.projection) "重新允许屏幕录制" else "允许屏幕录制") }
        }
    }
}

private data class AndroidUseStatus(val accessibility: Boolean, val projection: Boolean)

private fun readAndroidUseStatus(context: android.content.Context): AndroidUseStatus {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    val accessibility = enabled.contains("com.andmx.computeruse.AndmxAccessibilityService")
    val projection = com.andmx.computeruse.MediaProjectionManagerHolder.isAuthorized
    return AndroidUseStatus(accessibility, projection)
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val s by store.settings.collectAsState(initial = ProviderSettings())
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    fun save(next: ProviderSettings) {
        scope.launch { store.update(next) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { backAppBar("浏览器控制", onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SettingsGroup("控制") {
                SwitchRow(
                    title = "开启网页抓取",
                    description = "关闭后，新会话不再提供 WebFetch 和网页搜索。",
                    checked = s.browserControlEnabled,
                    onCheckedChange = { save(s.copy(browserControlEnabled = it)) },
                )
            }
            SettingsGroup("安全") {
                SwitchRow(
                    title = "忽略证书校验",
                    description = "开启后，网页抓取和搜索不再校验 HTTPS 证书。只影响这两项，不影响系统浏览器。",
                    checked = s.allowInsecureCertificates,
                    onCheckedChange = { save(s.copy(allowInsecureCertificates = it)) },
                )
            }
            SettingsGroup("浏览器数据") {
                Text(
                    "当前记住了 ${WebFetchCache.count()} 条网页正文，15 分钟后过期。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(tr("清除抓取缓存"), style = MaterialTheme.typography.bodyLarge)
                        Text(tr("清掉 15 分钟内记住的网页正文。下次抓取会重新打开页面。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = {
                        val removed = WebFetchCache.clear()
                        note = if (removed == 0) "缓存本来就是空的" else "已清除 $removed 条网页抓取缓存"
                    }) { Text(tr("清除")) }
                }
                if (note.isNotBlank()) {
                    Text(note, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val settings by store.settings.collectAsState(initial = ProviderSettings())
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<String?>(null) }
    val overrides = remember(settings.shortcutBindings) { SessionShortcuts.parse(settings.shortcutBindings) }
    val shown = SessionShortcuts.commands.filter { item ->
        val binding = overrides[item.id] ?: item.defaultBinding
        query.isBlank() ||
            item.label.contains(query, ignoreCase = true) ||
            binding.contains(query, ignoreCase = true)
    }
    fun save(next: Map<String, String>) {
        scope.launch { store.update(settings.copy(shortcutBindings = SessionShortcuts.serialize(next))) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { backAppBar("键盘快捷键", onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .onPreviewKeyEvent { event ->
                    val id = recording ?: return@onPreviewKeyEvent false
                    val combo = SessionShortcuts.format(event) ?: return@onPreviewKeyEvent false
                    if (combo == "Esc") {
                        recording = null
                        return@onPreviewKeyEvent true
                    }
                    save(overrides + (id to combo))
                    recording = null
                    true
                },
        ) {
            Text(
                if (recording == null) "点某一行，再按外接键盘上的新按键。Esc 取消。"
                else "正在录制，按下新的快捷键。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(tr("搜索快捷键")) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
            )
            if (shown.isEmpty()) {
                Text(tr("没有匹配的命令"), modifier = Modifier.padding(top = 16.dp))
            }
            shown.forEach { item ->
                val binding = overrides[item.id] ?: item.defaultBinding
                val custom = overrides.containsKey(item.id)
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(item.label), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (recording == item.id) "请按键…" else "$binding · ${item.scope}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { recording = item.id }) { Text(if (recording == item.id) "录制中" else "改键") }
                    if (custom) {
                        TextButton(onClick = { save(overrides - item.id) }) { Text("恢复") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceIgnorePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val fs = remember { GuestFs(ProotRuntime(context)) }
    val project = remember { ProjectManager(context) }
    val scope = rememberCoroutineScope()
    val path = "${project.guestMountPath}/.zcodeignore"
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val dirty = text != saved
    androidx.compose.runtime.LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { fs.readText(path, limit = 256 * 1024) }.getOrDefault("")
        }
        text = loaded
        saved = loaded
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { backAppBar("工作区搜索", onBack) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Text(tr("`.zcodeignore` 里的规则会从工作区搜索里排除。一行一条，语法和 gitignore 相同。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { fs.writeText(path, text) }.isSuccess
                            }
                            if (ok) {
                                saved = text
                                note = "已写入 $path"
                            } else {
                                note = "写入失败"
                            }
                        }
                    },
                    enabled = dirty,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (dirty) "保存" else "已保存") }
                OutlinedButton(
                    onClick = {
                        if (!text.contains("# 默认排除")) {
                            text += "\n# 默认排除\n.gradle/\nbuild/\n*.apk\n"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("补上默认排除")) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val gitignore = withContext(Dispatchers.IO) {
                                val gitPath = "${project.guestMountPath}/.gitignore"
                                if (!fs.exists(gitPath)) "" else runCatching {
                                    fs.readText(gitPath, limit = 64 * 1024)
                                }.getOrDefault("")
                            }
                            if (gitignore.isBlank()) {
                                note = "当前工作区没有 .gitignore"
                            } else if (text.contains("# 从 .gitignore 同步")) {
                                note = "已经同步过 .gitignore"
                            } else {
                                text += "\n# 从 .gitignore 同步\n$gitignore"
                                note = "已放进编辑框，保存后才会生效"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("同步 .gitignore")) }
            }
            if (note.isNotBlank()) {
                Text(note, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
