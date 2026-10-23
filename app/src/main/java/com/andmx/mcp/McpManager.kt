package com.andmx.mcp

import android.content.Context
import com.andmx.agent.McpTool
import com.andmx.agent.Tool
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Parsed MCP server entry from the settings text.
 * Plain lines stay `name|command`. Anything beyond that is one JSON object per line,
 * so headers, env, args, timeout and protocol version survive a save.
 */
data class McpServerConfig(
    val name: String,
    val command: String,
    val transport: McpTransport = McpTransport.STDIO,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 0,
    val protocolVersion: String = "",
    val type: String = "",
    val oauthJson: String = "",
) {
    val resolvedType: String
        get() = type.ifBlank {
            when (transport) {
                McpTransport.HTTP -> "http"
                McpTransport.WEBSOCKET -> "ws"
                McpTransport.STDIO -> "stdio"
            }
        }

    fun shellCommand(): String = (listOf(command.trim()) + args).filter { it.isNotBlank() }.joinToString(" ")

    fun wireProtocol(): String = when (protocolVersion) {
        "", "auto" -> McpProtocol.LATEST_VERSION
        "legacy" -> "2024-11-05"
        else -> protocolVersion
    }

    fun toLine(): String {
        val plain = args.isEmpty() && env.isEmpty() && headers.isEmpty() && timeoutMs <= 0L &&
            protocolVersion.isBlank() && oauthJson.isBlank() && type != "sse"
        if (plain) return "$name|$command"
        return buildJsonObject {
            put("name", name)
            put("type", resolvedType)
            if (resolvedType == "stdio") {
                put("command", command)
                if (args.isNotEmpty()) put("args", JsonArray(args.map { JsonPrimitive(it) }))
                if (env.isNotEmpty()) put("env", stringMap(env))
            } else {
                put("url", command)
                if (headers.isNotEmpty()) put("headers", stringMap(headers))
            }
            if (timeoutMs > 0L) put("timeoutMs", timeoutMs)
            if (protocolVersion.isNotBlank()) put("protocolVersion", protocolVersion)
            oauthElement(oauthJson)?.let { put("oauth", it) }
        }.toString()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): List<McpServerConfig> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                runCatching {
                    if (line.startsWith("{")) fromDraft(json.parseToJsonElement(line).jsonObject) else fromLegacy(line)
                }.getOrNull()
            }
            .filter { it.name.isNotEmpty() && it.command.isNotEmpty() }
            .toList()

        fun parseDraft(text: String): McpServerConfig {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{")) {
                return parse(trimmed).singleOrNull() ?: error("JSON 内容不是有效的 MCP server 配置")
            }
            return fromDraft(json.parseToJsonElement(trimmed).jsonObject)
        }

        private fun fromLegacy(line: String): McpServerConfig? {
            if (!line.contains('|')) return null
            val name = line.substringBefore('|').trim()
            val rest = line.substringAfter('|').trim()
            val transport = when {
                rest.startsWith("http://") || rest.startsWith("https://") -> McpTransport.HTTP
                rest.startsWith("ws://") || rest.startsWith("wss://") -> McpTransport.WEBSOCKET
                else -> McpTransport.STDIO
            }
            if (name.isEmpty() || rest.isEmpty()) return null
            return McpServerConfig(name, rest, transport)
        }

        private fun fromDraft(parsed: JsonObject): McpServerConfig {
            var name = parsed.str("name")
            var body = parsed
            val wrapped = parsed["mcpServers"] as? JsonObject
            if (wrapped != null) {
                val entries = wrapped.entries.filter { it.value is JsonObject }
                if (entries.size != 1) error("JSON 模式暂时只支持一次编辑一个 MCP server")
                name = entries.first().key
                body = entries.first().value.jsonObject
            } else if (parsed["type"] == null && parsed["command"] == null && parsed["url"] == null && parsed["name"] == null) {
                val entries = parsed.entries.filter { it.value is JsonObject }
                if (entries.size == 1) {
                    name = entries.first().key
                    body = entries.first().value.jsonObject
                } else if (entries.size > 1) {
                    error("JSON 模式暂时只支持一次编辑一个 MCP server")
                }
            }
            if (name.isBlank()) error("JSON 模式需要提供 server 名称")
            return fromBody(name, body)
        }

        private fun fromBody(name: String, body: JsonObject): McpServerConfig {
            val explicit = body.str("type")
            val command = body.str("command")
            val url = body.str("url")
            val type = when {
                explicit == "sse" -> "sse"
                explicit == "streamableHttp" || explicit == "http" -> "http"
                explicit == "ws" || explicit == "websocket" -> "ws"
                explicit == "stdio" || command.isNotBlank() -> "stdio"
                url.isNotBlank() -> "http"
                else -> "stdio"
            }
            val transport = when (type) {
                "ws" -> McpTransport.WEBSOCKET
                "http", "sse" -> McpTransport.HTTP
                else -> McpTransport.STDIO
            }
            val target = if (transport == McpTransport.STDIO) command else url.ifBlank { command }
            val args = when (val raw = body["args"]) {
                is JsonArray -> raw.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                is JsonPrimitive -> raw.contentOrNull?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
                else -> emptyList()
            }
            val protocol = body.str("protocolVersion").let { value ->
                if (value.isBlank() || value == "auto" || value == "legacy" || value == "2026-07-28" || value in McpProtocol.SUPPORTED_VERSIONS) {
                    if (value == "auto") "" else value
                } else {
                    ""
                }
            }
            return McpServerConfig(
                name = name,
                command = target,
                transport = transport,
                args = if (transport == McpTransport.STDIO) args else emptyList(),
                env = if (transport == McpTransport.STDIO) stringMapOf(body["env"] as? JsonObject) else emptyMap(),
                headers = if (transport == McpTransport.STDIO) emptyMap() else stringMapOf(body["headers"] as? JsonObject),
                timeoutMs = body["timeoutMs"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L } ?: 0L,
                protocolVersion = protocol,
                type = type,
                oauthJson = (body["oauth"] as? JsonObject)?.toString().orEmpty(),
            )
        }

        private fun JsonObject.str(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }
}

private fun stringMap(values: Map<String, String>): JsonObject = buildJsonObject {
    values.forEach { (k, v) -> put(k, v) }
}

private fun stringMapOf(obj: JsonObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    return obj.mapNotNull { (k, v) ->
        val text = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: return@mapNotNull null
        k to text
    }.toMap()
}

private fun oauthElement(raw: String): kotlinx.serialization.json.JsonElement? {
    if (raw.isBlank()) return null
    return runCatching { Json.parseToJsonElement(raw) }.getOrNull()
}

/**
 * Resource subscription state for an MCP server.
 * Mirrors Codex's resource subscription support.
 */
data class ResourceSubscription(
    val serverName: String,
    val uri: String,
    val lastContent: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Connects to the configured MCP servers and exposes their tools to the agent.
 * Supports stdio (in proot) and HTTP/SSE transports.
 * Failures are isolated per-server so one bad server doesn't break the others.
 */
class McpManager(private val context: Context) {
    private val runtime = ProotRuntime(context)
    private val clients = mutableListOf<McpClient>()
    private val httpTransports = mutableListOf<McpHttpTransport>()

    /** A connected MCP server and the tools it advertised. */
    data class Connected(val name: String, val tools: List<String>, val transport: String)

    private val _connected = mutableListOf<Connected>()
    val connected: List<Connected> get() = _connected.toList()

    /** Resource subscriptions across all servers. */
    private val _subscriptions = MutableStateFlow<List<ResourceSubscription>>(emptyList())
    val subscriptions: StateFlow<List<ResourceSubscription>> = _subscriptions

    suspend fun connectAll(configText: String, onLog: (String) -> Unit = {}): List<Tool> {
        val configs = McpServerConfig.parse(configText)
        if (configs.isEmpty()) return emptyList()
        val tools = mutableListOf<Tool>()
        for (cfg in configs) {
            tools += connectOne(cfg, onLog)
        }
        return tools
    }

    /** Connect a single configured server; returns its tools (empty on failure). */
    suspend fun connectOne(cfg: McpServerConfig, onLog: (String) -> Unit = {}): List<Tool> {
        if (_connected.any { it.name == cfg.name }) return emptyList()
        val tools = mutableListOf<Tool>()
        val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
        runCatching {
            when (cfg.transport) {
                McpTransport.HTTP, McpTransport.WEBSOCKET -> {
                    val transport = McpHttpTransport(
                        cfg.name,
                        cfg.command,
                        headers = cfg.headers,
                        protocolVersion = cfg.wireProtocol(),
                        timeoutMs = cfg.timeoutMs,
                    )
                    val descs = transport.connect()
                    httpTransports += transport
                    descs.forEach { desc ->
                        tools += object : Tool {
                            override val name = "${cfg.name}__${desc.name}"
                            override val description = "[${cfg.name}] ${desc.description}"
                            override val risk = com.andmx.agent.ToolRisk.EXECUTE
                            override val parameters = desc.inputSchema
                            override suspend fun execute(args: kotlinx.serialization.json.JsonObject): com.andmx.agent.ToolResult {
                                val result = transport.callTool(desc.name, args)
                                return com.andmx.agent.ToolResult(result.take(16_000))
                            }
                        }
                    }
                    _connected += Connected(cfg.name, descs.map { it.name }, cfg.transport.name)
                    onLog("MCP ${cfg.name} (HTTP): ${descs.size} 个工具")
                }
                McpTransport.STDIO -> {
                    val argv = runtime.prootArgv(
                        command = listOf(sh, "-lc", cfg.shellCommand()),
                        rootfs = runtime.rootfsDir.takeIf { it.exists() },
                    )
                    val client = McpClient(
                        cfg.name,
                        argv,
                        runtime.env() + cfg.env,
                        protocolVersion = cfg.wireProtocol(),
                        timeoutMs = cfg.timeoutMs,
                    )
                    val descs = client.connect()
                    clients += client
                    descs.forEach { tools += McpTool(client, it) }
                    _connected += Connected(cfg.name, descs.map { it.name }, "STDIO")
                    onLog("MCP ${cfg.name} (stdio): ${descs.size} 个工具")
                }
            }
        }.onFailure { onLog("MCP ${cfg.name} 连接失败: ${it.message}") }
        return tools
    }

    /** Disconnect a single server by name; returns true when one was connected. */
    fun disconnect(name: String): Boolean {
        val idx = _connected.indexOfFirst { it.name == name }
        if (idx < 0) return false
        _connected.removeAt(idx)
        clients.filter { it.serverName == name }.forEach { it.close() }
        clients.removeAll { it.serverName == name }
        httpTransports.filter { it.serverName == name }.forEach { it.close() }
        httpTransports.removeAll { it.serverName == name }
        return true
    }

    /** Subscribe to a resource update on a specific server. */
    suspend fun subscribeResource(serverName: String, uri: String): Boolean {
        val client = clients.firstOrNull { it.serverName == serverName } ?: return false
        if (!client.supportsCapability("resources")) return false
        runCatching {
            // Use the client's internal request to subscribe
            // (McpClient doesn't expose subscribe directly, but we can read the resource)
            val content = client.readResource(uri)
            val sub = ResourceSubscription(serverName, uri, content)
            _subscriptions.value = _subscriptions.value.filterNot { it.serverName == serverName && it.uri == uri } + sub
        }.onFailure { return false }
        return true
    }

    /** Refresh all subscribed resources and return which ones changed. */
    suspend fun refreshSubscriptions(): List<ResourceSubscription> {
        val changed = mutableListOf<ResourceSubscription>()
        for (sub in _subscriptions.value) {
            val client = clients.firstOrNull { it.serverName == sub.serverName } ?: continue
            runCatching {
                val content = client.readResource(sub.uri)
                if (content != sub.lastContent) {
                    val updated = sub.copy(lastContent = content, updatedAt = System.currentTimeMillis())
                    changed += updated
                }
            }
        }
        if (changed.isNotEmpty()) {
            _subscriptions.value = _subscriptions.value.map { existing ->
                changed.firstOrNull { it.serverName == existing.serverName && it.uri == existing.uri } ?: existing
            }
        }
        return changed
    }

    /** List all resources from all connected servers. */
    suspend fun listAllResources(): List<McpResource> {
        val all = mutableListOf<McpResource>()
        for (client in clients) {
            runCatching { all += client.listResources() }
        }
        return all
    }

    fun close() {
        clients.forEach { it.close() }
        clients.clear()
        httpTransports.forEach { it.close() }
        httpTransports.clear()
        _connected.clear()
    }
}

