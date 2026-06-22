package com.andmx.mcp

import android.content.Context
import com.andmx.agent.McpTool
import com.andmx.agent.Tool
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Parsed MCP server entry from the settings text.
 * Format: "name|command" for stdio, "name|http://url" for HTTP/SSE.
 */
data class McpServerConfig(val name: String, val command: String, val transport: McpTransport = McpTransport.STDIO) {
    companion object {
        fun parse(text: String): List<McpServerConfig> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('|') }
            .map { line ->
                val name = line.substringBefore('|').trim()
                val rest = line.substringAfter('|').trim()
                val transport = when {
                    rest.startsWith("http://") || rest.startsWith("https://") -> McpTransport.HTTP
                    rest.startsWith("ws://") || rest.startsWith("wss://") -> McpTransport.WEBSOCKET
                    else -> McpTransport.STDIO
                }
                McpServerConfig(name, rest, transport)
            }
            .filter { it.name.isNotEmpty() && it.command.isNotEmpty() }
            .toList()
    }
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
        val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
        for (cfg in configs) {
            runCatching {
                when (cfg.transport) {
                    McpTransport.HTTP, McpTransport.WEBSOCKET -> {
                        val transport = McpHttpTransport(cfg.name, cfg.command)
                        val descs = transport.connect()
                        httpTransports += transport
                        // Wrap HTTP transport tools — need an adapter
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
                            command = listOf(sh, "-lc", cfg.command),
                            rootfs = runtime.rootfsDir.takeIf { it.exists() },
                        )
                        val client = McpClient(cfg.name, argv, runtime.env())
                        val descs = client.connect()
                        clients += client
                        descs.forEach { tools += McpTool(client, it) }
                        _connected += Connected(cfg.name, descs.map { it.name }, "STDIO")
                        onLog("MCP ${cfg.name} (stdio): ${descs.size} 个工具")
                    }
                }
            }.onFailure { onLog("MCP ${cfg.name} 连接失败: ${it.message}") }
        }
        return tools
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

