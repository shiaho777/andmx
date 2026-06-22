package com.andmx.agent.plugins

import android.content.Context
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plugin system — mirrors Codex's plugin architecture.
 *
 * Plugins are packages that extend AndMX's capabilities. Each plugin has:
 * - A `plugin.json` manifest describing its tools, hooks, and skills
 * - Optional hook scripts that run at lifecycle events
 * - Optional skill files (SKILL.md) that inject domain knowledge
 *
 * Plugin discovery:
 * - Built-in plugins in /root/.andmx/plugins/
 * - Marketplace plugins (future: git clone)
 *
 * Plugin manifest format (plugin.json):
 * ```json
 * {
 *   "name": "my-plugin",
 *   "version": "1.0.0",
 *   "description": "Does cool stuff",
 *   "hooks": [
 *     {"event": "pre_tool_use", "command": "python3 hook.py", "timeout_ms": 5000}
 *   ],
 *   "skills": ["SKILL.md"],
 *   "tools": ["tools/my_tool.py"]
 * }
 * ```
 *
 * Also supports Claude-compatible `.claude-plugin/plugin.json` manifests.
 */
class PluginSystem(
    private val context: Context,
    private val fs: GuestFs,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        const val PLUGINS_DIR = "/root/.andmx/plugins"
        const val CODEX_MANIFEST = "plugin.json"
        const val CLAUDE_MANIFEST = ".claude-plugin/plugin.json"
        const val CODEX_PLUGIN_DIR = ".codex-plugin"
    }

    @Serializable
    data class PluginManifest(
        val name: String,
        val version: String = "0.0.0",
        val description: String = "",
        val author: String = "",
        val hooks: List<HookEntry> = emptyList(),
        val skills: List<String> = emptyList(),
        val tools: List<String> = emptyList(),
        val enabled: Boolean = true,
    )

    @Serializable
    data class HookEntry(
        val event: String,          // pre_tool_use, post_tool_use, session_start, etc.
        val command: String,
        val timeoutMs: Long = 10_000,
        val name: String = "",
    )

    data class Plugin(
        val manifest: PluginManifest,
        val dir: String,
        val enabled: Boolean,
        val source: PluginSource,
    )

    enum class PluginSource { LOCAL, MARKETPLACE, BUILTIN }

    data class PluginDiscovery(
        val plugins: List<Plugin>,
        val totalHooks: Int,
        val totalSkills: Int,
        val totalTools: Int,
    )

    private val _state = MutableStateFlow<PluginDiscovery>(PluginDiscovery(emptyList(), 0, 0, 0))
    val state: StateFlow<PluginDiscovery> = _state

    /** Discover all plugins in the plugins directory. */
    suspend fun discover(): PluginDiscovery {
        val plugins = mutableListOf<Plugin>()

        if (!fs.exists(PLUGINS_DIR)) {
            _state.value = PluginDiscovery(emptyList(), 0, 0, 0)
            return _state.value
        }

        val entries = runCatching { fs.list(PLUGINS_DIR) }.getOrDefault(emptyList())
        for (entry in entries) {
            val pluginDir = "$PLUGINS_DIR/$entry"
            discoverPlugin(pluginDir)?.let { plugins.add(it) }
        }

        val discovery = PluginDiscovery(
            plugins = plugins,
            totalHooks = plugins.sumOf { it.manifest.hooks.size },
            totalSkills = plugins.sumOf { it.manifest.skills.size },
            totalTools = plugins.sumOf { it.manifest.tools.size },
        )
        _state.value = discovery
        return discovery
    }

    private suspend fun discoverPlugin(dir: String): Plugin? {
        // Try Codex manifest first, then Claude manifest
        val manifestPath = when {
            fs.exists("$dir/$CODEX_MANIFEST") -> "$dir/$CODEX_MANIFEST"
            fs.exists("$dir/$CLAUDE_MANIFEST") -> "$dir/$CLAUDE_MANIFEST"
            fs.exists("$dir/$CODEX_PLUGIN_DIR/$CODEX_MANIFEST") -> "$dir/$CODEX_PLUGIN_DIR/$CODEX_MANIFEST"
            else -> return null
        }

        return runCatching {
            val content = fs.readText(manifestPath)
            val manifest = json.decodeFromString(PluginManifest.serializer(), content)
            Plugin(
                manifest = manifest,
                dir = dir,
                enabled = manifest.enabled,
                source = PluginSource.LOCAL,
            )
        }.getOrNull()
    }

    /** Load all skill files from enabled plugins as a prompt fragment. */
    suspend fun skillsPromptFragment(): String {
        val discovery = discover()
        if (discovery.plugins.isEmpty()) return ""

        val sb = StringBuilder()
        for (plugin in discovery.plugins.filter { it.enabled }) {
            for (skillFile in plugin.manifest.skills) {
                val skillPath = "${plugin.dir}/$skillFile"
                if (fs.exists(skillPath)) {
                    val content = runCatching { fs.readText(skillPath) }.getOrNull() ?: continue
                    sb.appendLine("## Skill: ${plugin.manifest.name}/$skillFile")
                    sb.appendLine(content.take(8_000))
                    sb.appendLine()
                }
            }
        }

        return if (sb.isNotEmpty()) {
            buildString {
                appendLine("# 插件技能")
                appendLine("以下技能来自已启用的插件:")
                appendLine()
                append(sb.toString())
            }
        } else ""
    }

    /**
     * Materialize each plugin's declared `tools` (script names) into real
     * [PluginTool]s the agent can call. A tool named "lint" in plugin "qa"
     * becomes `plugin_qa_lint`. Returns empty if no plugins declare tools or
     * the script files don't exist on disk.
     */
    suspend fun loadPluginTools(context: android.content.Context): List<com.andmx.agent.Tool> {
        val discovery = discover()
        val tools = mutableListOf<com.andmx.agent.Tool>()
        for (plugin in discovery.plugins.filter { it.enabled }) {
            for (script in plugin.manifest.tools) {
                // Only register the tool if its script actually exists; a
                // declared-but-missing script would just produce confusing errors.
                if (fs.exists("${plugin.dir}/$script")) {
                    val safeName = plugin.manifest.name.filter { it.isLetterOrDigit() || it == '_' }
                    val safeScript = script.filter { it.isLetterOrDigit() || it in "._-" }
                    val toolName = "plugin_${safeName}_${safeScript}"
                    val desc = "插件 ${plugin.manifest.name} 提供的工具 ($script)。" +
                        if (plugin.manifest.description.isNotBlank()) " ${plugin.manifest.description}" else ""
                    tools += PluginTool(context, toolName, plugin.dir, script, desc)
                }
            }
        }
        return tools
    }

    /** Get all hooks from enabled plugins, organized by event. */
    suspend fun allHooks(): Map<String, List<HookEntry>> {
        val discovery = discover()
        val hooks = mutableMapOf<String, MutableList<HookEntry>>()
        for (plugin in discovery.plugins.filter { it.enabled }) {
            for (hook in plugin.manifest.hooks) {
                val list = hooks.getOrPut(hook.event) { mutableListOf() }
                list.add(hook.copy(name = if (hook.name.isBlank()) "${plugin.manifest.name}:${hook.event}" else hook.name))
            }
        }
        return hooks
    }

    /** Enable or disable a plugin by writing to its manifest. */
    suspend fun setEnabled(pluginName: String, enabled: Boolean): Boolean {
        val discovery = discover()
        val plugin = discovery.plugins.find { it.manifest.name == pluginName } ?: return false
        val manifestPath = when {
            fs.exists("${plugin.dir}/$CODEX_MANIFEST") -> "${plugin.dir}/$CODEX_MANIFEST"
            fs.exists("${plugin.dir}/$CLAUDE_MANIFEST") -> "${plugin.dir}/$CLAUDE_MANIFEST"
            else -> return false
        }
        val content = fs.readText(manifestPath)
        val manifest = json.decodeFromString(PluginManifest.serializer(), content)
        val updated = manifest.copy(enabled = enabled)
        fs.writeText(manifestPath, json.encodeToString(PluginManifest.serializer(), updated))
        discover() // refresh state
        return true
    }
}
