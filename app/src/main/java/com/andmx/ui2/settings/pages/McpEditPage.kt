package com.andmx.ui2.settings.pages

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.mcp.McpOAuthFlow
import com.andmx.mcp.McpProtocol
import com.andmx.mcp.McpServerConfig
import com.andmx.mcp.McpTransport
import com.andmx.ui2.settings.SegmentedRow
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class McpEntry(
    val name: String,
    val target: String,
    val args: String = "",
    val envText: String = "",
    val headersText: String = "",
    val timeoutMs: String = "",
    val protocolVersion: String = "",
    val type: String = "",
    val oauthText: String = "",
) {
    val isRemote: Boolean
        get() = type == "http" || type == "sse" || type == "ws" ||
            (type.isBlank() && (target.startsWith("http") || target.startsWith("ws")))

    fun toLine(): String = toConfig().toLine()

    fun toConfig(): McpServerConfig {
        val remote = isRemote
        val kind = when {
            type == "sse" -> "sse"
            type == "ws" || (!type.equals("stdio", true) && target.startsWith("ws")) -> "ws"
            remote -> "http"
            else -> "stdio"
        }
        val transport = when (kind) {
            "ws" -> McpTransport.WEBSOCKET
            "http", "sse" -> McpTransport.HTTP
            else -> McpTransport.STDIO
        }
        return McpServerConfig(
            name = name.trim(),
            command = target.trim(),
            transport = transport,
            args = if (transport == McpTransport.STDIO) splitArgs(args) else emptyList(),
            env = if (transport == McpTransport.STDIO) parseStringMap(envText, "环境变量") else emptyMap(),
            headers = if (transport == McpTransport.STDIO) emptyMap() else parseStringMap(headersText, "请求头"),
            timeoutMs = timeoutMs.trim().toLongOrNull()?.takeIf { it > 0L } ?: 0L,
            protocolVersion = protocolVersion,
            type = kind,
            oauthJson = oauthText.trim(),
        )
    }

    fun toDraft(): String {
        val line = toLine()
        val body = if (line.trim().startsWith("{")) {
            Json.parseToJsonElement(line).jsonObject
        } else {
            buildJsonObject {
                if (isRemote) {
                    put("type", JsonPrimitive(if (type == "sse") "sse" else "http"))
                    put("url", JsonPrimitive(target))
                } else {
                    put("type", JsonPrimitive("stdio"))
                    put("command", JsonPrimitive(target))
                    val pieces = splitArgs(args)
                    if (pieces.isNotEmpty()) {
                        put("args", kotlinx.serialization.json.JsonArray(pieces.map { JsonPrimitive(it) }))
                    }
                }
            }
        }
        val wrapped = buildJsonObject { put(name.ifBlank { "my-mcp-server" }, body) }
        return wrapped.toString()
    }

    companion object {
        fun parse(text: String): List<McpEntry> = McpServerConfig.parse(text).map { it.toEntry() }

        fun fromDraft(text: String): McpEntry = McpServerConfig.parseDraft(text).toEntry()
    }
}

fun McpServerConfig.toEntry(): McpEntry = McpEntry(
    name = name,
    target = command,
    args = args.joinToString(" "),
    envText = formatStringMap(env),
    headersText = formatStringMap(headers),
    timeoutMs = timeoutMs.takeIf { it > 0L }?.toString().orEmpty(),
    protocolVersion = protocolVersion,
    type = resolvedType,
    oauthText = oauthJson,
)

private fun splitArgs(raw: String): List<String> =
    raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

private fun formatStringMap(values: Map<String, String>): String {
    if (values.isEmpty()) return ""
    return buildJsonObject { values.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()
}

private fun parseStringMap(raw: String, label: String): Map<String, String> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyMap()
    val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
        throw IllegalArgumentException("$label 不是 JSON 对象")
    }
    return buildMap {
        obj.forEach { (key, value) ->
            val content = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
                ?: throw IllegalArgumentException("$label 的 $key 必须是字符串")
            put(key, content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpEditPage(
    initial: McpEntry?,
    onBack: () -> Unit,
    onSave: (McpEntry) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var remote by remember { mutableStateOf(initial?.isRemote ?: true) }
    var sse by remember { mutableStateOf(initial?.type == "sse") }
    var target by remember { mutableStateOf(initial?.target ?: "") }
    var args by remember { mutableStateOf(initial?.args.orEmpty()) }
    var envText by remember { mutableStateOf(initial?.envText.orEmpty()) }
    var headersText by remember { mutableStateOf(initial?.headersText.orEmpty()) }
    var timeoutText by remember { mutableStateOf(initial?.timeoutMs.orEmpty()) }
    var protocolVersion by remember { mutableStateOf(initial?.protocolVersion.orEmpty()) }
    var oauthText by remember { mutableStateOf(initial?.oauthText.orEmpty()) }
    var editorMode by remember { mutableStateOf("form") }
    var jsonDraft by remember { mutableStateOf(initial?.toDraft().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var authNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        McpOAuthFlow.lastResult.collect { result ->
            if (result != null) {
                authNote = (if (result.ok) "✓ " else "✗ ") + result.message
            }
        }
    }

    fun currentEntry(): McpEntry = McpEntry(
        name = name.trim(),
        target = target.trim(),
        args = args,
        envText = envText,
        headersText = headersText,
        timeoutMs = timeoutText.trim(),
        protocolVersion = protocolVersion,
        type = when {
            !remote -> "stdio"
            sse -> "sse"
            target.trim().startsWith("ws") -> "ws"
            else -> "http"
        },
        oauthText = oauthText,
    )

    val canSave = if (editorMode == "json") {
        jsonDraft.isNotBlank()
    } else {
        name.isNotBlank() && target.isNotBlank() && !name.contains('|')
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新建 MCP 服务器" else "编辑 MCP 服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            try {
                                val entry = if (editorMode == "json") {
                                    McpEntry.fromDraft(jsonDraft)
                                } else {
                                    currentEntry().also { it.toConfig() }
                                }
                                error = null
                                onSave(entry)
                            } catch (t: Throwable) {
                                error = t.message ?: "无法保存"
                            }
                        },
                        enabled = canSave,
                    ) { Icon(Icons.Outlined.Check, "保存") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            SegmentedRow(
                options = listOf("form" to "表单", "json" to "JSON"),
                selected = editorMode,
                onSelect = { next ->
                    if (next == editorMode) {
                    } else if (next == "json") {
                        jsonDraft = runCatching { currentEntry().toDraft() }.getOrElse { jsonDraft }
                        error = null
                        editorMode = "json"
                    } else {
                        runCatching { McpEntry.fromDraft(jsonDraft) }
                            .onSuccess { parsed ->
                                name = parsed.name
                                target = parsed.target
                                args = parsed.args
                                envText = parsed.envText
                                headersText = parsed.headersText
                                timeoutText = parsed.timeoutMs
                                protocolVersion = parsed.protocolVersion
                                oauthText = parsed.oauthText
                                remote = parsed.isRemote
                                sse = parsed.type == "sse"
                                error = null
                                editorMode = "form"
                            }
                            .onFailure {
                                error = it.message ?: "JSON 无法转回表单"
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (editorMode == "json") {
                SettingsGroup("完整配置") {
                    Text(
                        tr("可以粘贴 {\"server-name\": {...}}，或 {\"mcpServers\": {\"server-name\": {...}}}。一次只编辑一个服务器。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = jsonDraft,
                        onValueChange = {
                            jsonDraft = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 12,
                    )
                }
            } else {
                SettingsGroup("基本信息") {
                    StackedSettingRow(title = "名称", description = "工具名前缀，如 filesystem") {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text("filesystem") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    StackedSettingRow(
                        title = "传输类型",
                        description = if (remote) "远程 HTTP 或旧版 SSE" else "本地命令，在 proot 里启动",
                    ) {
                        SegmentedRow(
                            options = listOf("remote" to "HTTP / SSE", "stdio" to "本地命令"),
                            selected = if (remote) "remote" else "stdio",
                            onSelect = { remote = it == "remote" },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (remote) {
                        StackedSettingRow(title = "远程方式", description = "SSE 走同一套 HTTP 客户端。") {
                            SegmentedRow(
                                options = listOf("http" to "HTTP", "sse" to "SSE"),
                                selected = if (sse) "sse" else "http",
                                onSelect = { sse = it == "sse" },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    StackedSettingRow(title = "超时", description = "毫秒。留空用 60 秒。") {
                        OutlinedTextField(
                            value = timeoutText,
                            onValueChange = { timeoutText = it.filter { ch -> ch.isDigit() }.take(7) },
                            placeholder = { Text("60000") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    if (!sse) {
                        StackedSettingRow(title = "协议版本", description = "自动使用当前客户端版本。") {
                            SegmentedRow(
                                options = listOf(
                                    "" to "自动",
                                    "legacy" to "旧版",
                                    McpProtocol.LATEST_VERSION to "当前",
                                ),
                                selected = when (protocolVersion) {
                                    "legacy" -> "legacy"
                                    McpProtocol.LATEST_VERSION -> McpProtocol.LATEST_VERSION
                                    else -> ""
                                },
                                onSelect = { protocolVersion = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                SettingsGroup(if (remote) "端点" else "命令") {
                    if (remote) {
                        StackedSettingRow(title = "URL", description = "服务器地址") {
                            OutlinedTextField(
                                value = target,
                                onValueChange = { target = it },
                                placeholder = { Text("https://mcp.example.com/mcp") },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                singleLine = true,
                            )
                        }
                        StackedSettingRow(title = "请求头", description = "JSON 对象，例如 Authorization。") {
                            OutlinedTextField(
                                value = headersText,
                                onValueChange = { headersText = it },
                                placeholder = { Text("{\"Authorization\": \"Bearer token\"}") },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 3,
                            )
                        }
                    } else {
                        StackedSettingRow(title = "命令", description = "可执行文件，例如 npx") {
                            OutlinedTextField(
                                value = target,
                                onValueChange = { target = it },
                                placeholder = { Text("npx") },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                singleLine = true,
                            )
                        }
                        StackedSettingRow(title = "参数", description = "空格分隔，接在命令后面执行。") {
                            OutlinedTextField(
                                value = args,
                                onValueChange = { args = it },
                                placeholder = { Text("-y @modelcontextprotocol/server-filesystem /root") },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                minLines = 2,
                            )
                        }
                        StackedSettingRow(title = "环境变量", description = "JSON 对象，会加到进程环境上。") {
                            OutlinedTextField(
                                value = envText,
                                onValueChange = { envText = it },
                                placeholder = { Text("{\"MY_API_KEY\": \"your-key\"}") },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 3,
                            )
                        }
                    }
                }
                SettingsGroup("OAuth") {
                    Text(
                        tr("可选。填写 authorizationUrl / tokenUrl / clientId / scopes 后可走浏览器授权；也可直接填 accessToken 或 refreshToken。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = oauthText,
                        onValueChange = { oauthText = it },
                        placeholder = {
                            Text("""{"authorizationUrl":"https://…/authorize","tokenUrl":"https://…/token","clientId":"…","scopes":["read"]}""")
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 5,
                    )
                    OutlinedButton(
                        onClick = {
                            val result = runCatching {
                                McpOAuthFlow.start(context, currentEntry().toConfig())
                            }.getOrElse {
                                McpOAuthFlow.Result(false, it.message ?: "授权启动失败")
                            }
                            authNote = (if (result.ok) "✓ " else "✗ ") + result.message
                        },
                        enabled = oauthText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(tr("浏览器授权")) }
                    authNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("✓")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                ) {
                    Text(tr("删除服务器"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
