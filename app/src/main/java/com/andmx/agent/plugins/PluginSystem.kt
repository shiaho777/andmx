package com.andmx.agent.plugins

import android.content.Context
import android.util.Log
import com.andmx.exec.PersistentShell
import com.andmx.exec.files.GuestFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PluginSystem(
    private val context: Context,
    private val fs: GuestFs,
    private val shell: PersistentShell? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    companion object {
        private const val TAG = "PluginSystem"
        const val PLUGINS_DIR = "/root/.andmx/plugins"
        const val CODEX_MANIFEST = "plugin.json"
        const val CLAUDE_MANIFEST = ".claude-plugin/plugin.json"
        const val CODEX_PLUGIN_DIR = ".codex-plugin"
        const val ANDMX_PLUGIN_DIR = ".andmx-plugin"
        const val ZCODE_PLUGIN_DIR = ".zcode-plugin"
        private val DISABLED_FLAG = ".disabled"
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
        val commands: List<String> = emptyList(),
        val mcpServers: List<String> = emptyList(),
        val userConfigKeys: List<String> = emptyList(),
        val enabled: Boolean = true,
    )

    @Serializable
    data class HookEntry(
        val event: String,
        val command: String,
        val timeoutMs: Long = 10_000,
        val name: String = "",
    )

    data class Plugin(
        val manifest: PluginManifest,
        val dir: String,
        val enabled: Boolean,
        val source: PluginSource,
        val installSource: String = "",
    )

    enum class PluginSource { LOCAL, MARKETPLACE, BUILTIN }

    data class PluginDiscovery(
        val plugins: List<Plugin>,
        val totalHooks: Int,
        val totalSkills: Int,
        val totalTools: Int,
        val totalCommands: Int = 0,
        val totalMcpServers: Int = 0,
    )

    data class PluginMcpEntry(
        val id: String,
        val name: String,
        val pluginName: String,
        val pluginId: String,
        val pluginEnabled: Boolean,
        val active: Boolean,
        val toolCount: Int? = null,
        val marketplace: String = "",
        val status: String? = null,
        val error: String? = null,
        val description: String = "",
    )

    data class PluginSkillEntry(
        val id: String,
        val name: String,
        val description: String,
        val path: String,
        val pluginName: String,
        val pluginId: String,
        val pluginEnabled: Boolean,
        val marketplace: String = "",
    )

    data class InstallResult(
        val ok: Boolean,
        val name: String,
        val dir: String,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(PluginDiscovery(emptyList(), 0, 0, 0))
    val state: StateFlow<PluginDiscovery> = _state

    private val _reloadToken = MutableStateFlow(readStamp())
    val reloadToken: StateFlow<Long> = _reloadToken

    private fun stampPath() = "$PLUGINS_DIR/.reload-stamp"

    private fun readStamp(): Long = runCatching {
        if (!fs.exists(stampPath())) 0L else fs.readText(stampPath()).trim().toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    fun currentReloadToken(): Long {
        val stamp = readStamp()
        if (stamp != _reloadToken.value) _reloadToken.value = stamp
        return _reloadToken.value
    }

    private fun bumpReload() {
        val now = System.currentTimeMillis()
        runCatching { fs.writeText(stampPath(), now.toString()) }
        _reloadToken.value = now
    }

    private suspend fun ensurePluginsDir() {
        runCatching { fs.writeText("$PLUGINS_DIR/.gitkeep", "") }
    }

    private var ephemeralShell: PersistentShell? = null

    private suspend fun shellExec(command: String, timeoutMs: Long = 120_000L): String {
        val s = when {
            shell != null -> shell
            ephemeralShell != null -> ephemeralShell!!
            else -> PersistentShell(context).also { ephemeralShell = it }
        }
        s.start()
        val res = s.exec(command, timeoutMs)
        return (res.stdout + "\n" + res.stderr).trim()
    }

    suspend fun discover(): PluginDiscovery = withContext(Dispatchers.IO) {
        ensurePluginsDir()
        val stamp = readStamp()
        if (stamp != _reloadToken.value) _reloadToken.value = stamp
        if (!fs.exists(PLUGINS_DIR)) {
            val empty = PluginDiscovery(emptyList(), 0, 0, 0)
            _state.value = empty
            return@withContext empty
        }
        val entries = runCatching { fs.list(PLUGINS_DIR) }.getOrDefault(emptyList())
        val plugins = mutableListOf<Plugin>()
        for (entry in entries) {
            val name = entry.trimEnd('/')
            if (name.isBlank() || name.startsWith(".")) continue
            val pluginDir = "$PLUGINS_DIR/$name"
            discoverPlugin(pluginDir)?.let { plugins.add(it) }
        }
        val discovery = PluginDiscovery(
            plugins = plugins.sortedBy { it.manifest.name.lowercase() },
            totalHooks = plugins.sumOf { it.manifest.hooks.size },
            totalSkills = plugins.sumOf { it.manifest.skills.size },
            totalTools = plugins.sumOf { it.manifest.tools.size },
            totalCommands = plugins.sumOf { it.manifest.commands.size },
            totalMcpServers = plugins.sumOf { it.manifest.mcpServers.size },
        )
        _state.value = discovery
        discovery
    }

    private suspend fun discoverPlugin(dir: String): Plugin? {
        val manifestPath = when {
            fs.exists("$dir/$ANDMX_PLUGIN_DIR/$CODEX_MANIFEST") -> "$dir/$ANDMX_PLUGIN_DIR/$CODEX_MANIFEST"
            fs.exists("$dir/$ZCODE_PLUGIN_DIR/$CODEX_MANIFEST") -> "$dir/$ZCODE_PLUGIN_DIR/$CODEX_MANIFEST"
            fs.exists("$dir/$CODEX_PLUGIN_DIR/$CODEX_MANIFEST") -> "$dir/$CODEX_PLUGIN_DIR/$CODEX_MANIFEST"
            fs.exists("$dir/$CODEX_MANIFEST") -> "$dir/$CODEX_MANIFEST"
            fs.exists("$dir/$CLAUDE_MANIFEST") -> "$dir/$CLAUDE_MANIFEST"
            else -> null
        }
        val disabledByFlag = fs.exists("$dir/$DISABLED_FLAG")
        val installSource = runCatching {
            if (fs.exists("$dir/.andmx-install-source")) fs.readText("$dir/.andmx-install-source").trim() else ""
        }.getOrDefault("")
        val source = when {
            installSource.startsWith("http") || installSource.startsWith("git@") ||
                installSource.startsWith("marketplace:") -> PluginSource.MARKETPLACE
            installSource == "builtin" || installSource.startsWith("marketplace:builtin:") -> PluginSource.BUILTIN
            else -> PluginSource.LOCAL
        }
        if (manifestPath != null) {
            return runCatching {
                val content = fs.readText(manifestPath)
                val manifest = parseManifest(content, dir.substringAfterLast('/'))
                val enriched = enrichManifest(dir, manifest)
                Plugin(
                    manifest = enriched,
                    dir = dir,
                    enabled = enriched.enabled && !disabledByFlag,
                    source = source,
                    installSource = installSource,
                )
            }.onFailure { Log.w(TAG, "discover failed $dir: ${it.message}") }.getOrNull()
        }
        val inferred = inferManifest(dir) ?: return null
        return Plugin(
            manifest = inferred,
            dir = dir,
            enabled = inferred.enabled && !disabledByFlag,
            source = source,
            installSource = installSource,
        )
    }

    private fun parseManifest(content: String, fallbackName: String): PluginManifest {
        runCatching {
            return json.decodeFromString(PluginManifest.serializer(), content)
        }
        val root = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
            ?: return PluginManifest(name = fallbackName)
        val name = root.str("name")
            ?: root.str("id")
            ?: fallbackName
        val version = root.str("version") ?: "0.0.0"
        val description = root.str("description") ?: root.str("summary") ?: ""
        val author = root.str("author")
            ?: (root["interface"] as? JsonObject)?.str("displayName")
            ?: ""
        val skills = mutableListOf<String>()
        root["skills"]?.let { el ->
            when (el) {
                is JsonArray -> el.forEach { item ->
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull?.let { skills += it }
                        is JsonObject -> item.str("path")?.let { skills += it }
                            ?: item.str("file")?.let { skills += it }
                        else -> Unit
                    }
                }
                is JsonPrimitive -> el.contentOrNull?.let { skills += it }
                else -> Unit
            }
        }
        val tools = mutableListOf<String>()
        root["tools"]?.let { el ->
            when (el) {
                is JsonArray -> el.forEach { item ->
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull?.let { tools += it }
                        is JsonObject -> item.str("script")?.let { tools += it }
                            ?: item.str("path")?.let { tools += it }
                            ?: item.str("name")?.let { tools += it }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        val commands = mutableListOf<String>()
        root["commands"]?.let { el ->
            when (el) {
                is JsonArray -> el.forEach { item ->
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull?.let { commands += it }
                        is JsonObject -> item.str("path")?.let { commands += it }
                            ?: item.str("file")?.let { commands += it }
                        else -> Unit
                    }
                }
                is JsonPrimitive -> el.contentOrNull?.let {
                    // directory pointer like "commands"
                    if (it == "commands" || it.endsWith("/commands")) {
                        // resolved later by enrich
                    } else commands += it
                }
                else -> Unit
            }
        }
        val hooks = mutableListOf<HookEntry>()
        val hooksEl = root["hooks"]
        when (hooksEl) {
            is JsonArray -> hooksEl.forEach { item ->
                val o = item as? JsonObject ?: return@forEach
                val event = o.str("event") ?: o.str("hook") ?: return@forEach
                val command = o.str("command") ?: o.str("script") ?: return@forEach
                hooks += HookEntry(
                    event = event,
                    command = command,
                    timeoutMs = o.str("timeout_ms")?.toLongOrNull()
                        ?: o.str("timeoutMs")?.toLongOrNull()
                        ?: 10_000L,
                    name = o.str("name").orEmpty(),
                )
            }
            is JsonObject -> hooksEl.forEach { (event, value) ->
                when (value) {
                    is JsonArray -> value.forEach { item ->
                        val o = item as? JsonObject
                        val command = o?.str("command") ?: o?.str("script")
                            ?: (item as? JsonPrimitive)?.contentOrNull
                            ?: return@forEach
                        hooks += HookEntry(event = event, command = command, name = o?.str("name").orEmpty())
                    }
                    is JsonPrimitive -> value.contentOrNull?.let {
                        hooks += HookEntry(event = event, command = it)
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
        val enabled = root["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val mcpServers = parseMcpServerNames(root["mcpServers"])
        return PluginManifest(
            name = name,
            version = version,
            description = description,
            author = author,
            hooks = hooks,
            skills = skills,
            tools = tools,
            commands = commands,
            mcpServers = mcpServers,
            enabled = enabled,
        )
    }

    private fun parseMcpServerNames(el: kotlinx.serialization.json.JsonElement?): List<String> {
        if (el == null) return emptyList()
        val out = mutableListOf<String>()
        when (el) {
            is JsonArray -> el.forEach { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                    is JsonObject -> {
                        item.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                            ?: item.str("id")?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                    }
                    else -> Unit
                }
            }
            is JsonObject -> out += el.keys.map { it.trim() }.filter { it.isNotEmpty() }
            is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            else -> Unit
        }
        return out.distinct()
    }

    private suspend fun enrichManifest(dir: String, manifest: PluginManifest): PluginManifest {
        val skills = manifest.skills.toMutableList()
        val tools = manifest.tools.toMutableList()
        if (skills.isEmpty()) {
            if (fs.exists("$dir/SKILL.md")) skills += "SKILL.md"
            if (fs.exists("$dir/skills")) {
                runCatching { fs.list("$dir/skills") }.getOrDefault(emptyList()).forEach { entry ->
                    val name = entry.trimEnd('/')
                    when {
                        name.endsWith(".md", true) -> skills += "skills/$name"
                        entry.endsWith("/") && fs.exists("$dir/skills/$name/SKILL.md") ->
                            skills += "skills/$name/SKILL.md"
                    }
                }
            }
        }
        if (tools.isEmpty() && fs.exists("$dir/scripts")) {
            runCatching { fs.list("$dir/scripts") }.getOrDefault(emptyList()).forEach { entry ->
                val name = entry.trimEnd('/')
                if (name.endsWith(".sh") || name.endsWith(".py") || name.endsWith(".js")) {
                    tools += "scripts/$name"
                }
            }
        }
        if (tools.isEmpty() && fs.exists("$dir/tools")) {
            runCatching { fs.list("$dir/tools") }.getOrDefault(emptyList()).forEach { entry ->
                val name = entry.trimEnd('/')
                if (!entry.endsWith("/")) tools += "tools/$name"
            }
        }
        val commands = manifest.commands.toMutableList()
        if (commands.isEmpty() && fs.exists("$dir/commands")) {
            runCatching { fs.list("$dir/commands") }.getOrDefault(emptyList()).forEach { entry ->
                val name = entry.trimEnd('/')
                if (name.endsWith(".md", true)) commands += "commands/$name"
            }
        }
        val mcpServers = manifest.mcpServers.toMutableList()
        if (fs.exists("$dir/.mcp.json")) {
            val body = runCatching { fs.readText("$dir/.mcp.json") }.getOrNull().orEmpty()
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (root != null) {
                mcpServers += parseMcpServerNames(root["mcpServers"])
            }
        }
        if (mcpServers.isEmpty()) {
            when (manifest.name.lowercase()) {
                "andmx-android-dev", "android-emulator" -> mcpServers += "android-emulator"
                "andmx-storage-cleanup", "device-storage" -> mcpServers += "device-storage"
                "andmx-html-video", "html-video" -> mcpServers += "html-video"
                "andmx-dev-forge", "dev-forge" -> mcpServers += "dev-forge"
            }
        }
        return manifest.copy(
            skills = skills.distinct(),
            tools = tools.distinct(),
            commands = commands.distinct(),
            mcpServers = mcpServers.distinct(),
        )
    }

    private suspend fun inferManifest(dir: String): PluginManifest? {
        val name = dir.substringAfterLast('/')
        if (name.isBlank()) return null
        val hasSkill = fs.exists("$dir/SKILL.md") || fs.exists("$dir/skills")
        val hasScripts = fs.exists("$dir/scripts") || fs.exists("$dir/tools")
        val hasHooks = fs.exists("$dir/hooks")
        val hasCommands = fs.exists("$dir/commands")
        if (!hasSkill && !hasScripts && !hasHooks && !hasCommands) return null
        return enrichManifest(
            dir,
            PluginManifest(
                name = name,
                version = "0.0.0",
                description = "Inferred local plugin",
                skills = emptyList(),
                tools = emptyList(),
                hooks = emptyList(),
            ),
        )
    }

    suspend fun installFromGit(url: String, name: String? = null): InstallResult = withContext(Dispatchers.IO) {
        ensurePluginsDir()
        val pluginName = normalizeName(
            name ?: url.substringAfterLast('/').removeSuffix(".git").ifBlank { "unnamed-plugin" },
        )
        if (pluginName.isBlank()) {
            return@withContext InstallResult(false, "unnamed", "", listOf("Invalid plugin name"))
        }
        val destDir = "$PLUGINS_DIR/$pluginName"
        if (fs.exists(destDir)) {
            return@withContext InstallResult(false, pluginName, destDir, listOf("插件已存在: $pluginName"))
        }
        val out = runCatching {
            shellExec("mkdir -p '$PLUGINS_DIR' && git clone --depth 1 '$url' '$destDir' 2>&1")
        }.getOrElse {
            return@withContext InstallResult(false, pluginName, destDir, listOf("Git clone failed: ${it.message}"))
        }
        if (out.contains("fatal:") || out.contains("error:") || !fs.exists(destDir)) {
            runCatching { shellExec("rm -rf '$destDir'") }
            return@withContext InstallResult(false, pluginName, destDir, listOf("Git clone failed: ${out.take(500)}"))
        }
        val discovered = discoverPlugin(destDir)
        if (discovered == null) {
            val seed = PluginManifest(
                name = pluginName,
                version = "0.1.0",
                description = "Installed from $url",
                enabled = true,
            )
            writeManifest(destDir, seed)
        }
        runCatching { fs.writeText("$destDir/.andmx-install-source", url) }
        val final = discoverPlugin(destDir)
        if (final == null) {
            runCatching { shellExec("rm -rf '$destDir'") }
            return@withContext InstallResult(false, pluginName, destDir, listOf("无法识别插件结构（需要 plugin.json / SKILL.md / scripts）"))
        }
        discover()
        bumpReload()
        Log.i(TAG, "Installed plugin $pluginName from $url")
        InstallResult(true, final.manifest.name, destDir)
    }

    suspend fun installFromLocal(sourcePath: String, name: String? = null): InstallResult = withContext(Dispatchers.IO) {
        ensurePluginsDir()
        val pluginName = normalizeName(
            name ?: sourcePath.trimEnd('/').substringAfterLast('/').ifBlank { "unnamed-plugin" },
        )
        val destDir = "$PLUGINS_DIR/$pluginName"
        if (fs.exists(destDir)) {
            return@withContext InstallResult(false, pluginName, destDir, listOf("插件已存在: $pluginName"))
        }
        val src = sourcePath.trim()
        if (!fs.exists(src)) {
            return@withContext InstallResult(false, pluginName, destDir, listOf("源路径不存在: $src"))
        }
        val out = runCatching {
            shellExec("mkdir -p '$PLUGINS_DIR' && cp -a '$src' '$destDir' 2>&1")
        }.getOrElse {
            return@withContext InstallResult(false, pluginName, destDir, listOf("Copy failed: ${it.message}"))
        }
        if (!fs.exists(destDir)) {
            return@withContext InstallResult(false, pluginName, destDir, listOf("Copy failed: ${out.take(500)}"))
        }
        if (discoverPlugin(destDir) == null) {
            writeManifest(
                destDir,
                PluginManifest(name = pluginName, version = "0.1.0", description = "Copied from $src"),
            )
        }
        runCatching { fs.writeText("$destDir/.andmx-install-source", "local:$src") }
        discover()
        bumpReload()
        InstallResult(true, pluginName, destDir)
    }

    
    suspend fun installFromMarketplace(entry: PluginMarketplace.CatalogPlugin): InstallResult = withContext(Dispatchers.IO) {
        ensurePluginsDir()
        val pluginName = normalizeName(entry.name)
        if (pluginName.isBlank()) {
            return@withContext InstallResult(false, "unnamed", "", listOf("Invalid plugin name"))
        }
        val destDir = "$PLUGINS_DIR/$pluginName"
        if (fs.exists(destDir)) {
            return@withContext InstallResult(false, pluginName, destDir, listOf("插件已存在: $pluginName"))
        }
        val source = entry.source
            ?: return@withContext InstallResult(false, pluginName, destDir, listOf("缺少安装源"))

        when (source.type) {
            "builtin", "builtin-asset" -> {
                runCatching { BuiltinPluginSeeder.ensureSeeded(context, fs) }
                val seeded = "$PLUGINS_DIR/${source.id.ifBlank { pluginName }}"
                if (!fs.exists(seeded) && source.id.isNotBlank() && source.id != pluginName) {
                    // try exact name
                }
                val finalDir = when {
                    fs.exists(destDir) -> destDir
                    fs.exists(seeded) -> seeded
                    else -> destDir
                }
                if (!fs.exists(finalDir)) {
                    return@withContext InstallResult(
                        false,
                        pluginName,
                        destDir,
                        listOf("内置插件未找到，请刷新后重试"),
                    )
                }
                runCatching { fs.writeText("$finalDir/.andmx-install-source", "marketplace:builtin:${source.id.ifBlank { pluginName }}") }
                discover()
                bumpReload()
                return@withContext InstallResult(true, pluginName, finalDir)
            }
            "path" -> {
                val market = PluginMarketplace(context, fs, shell)
                val repo = market.ensureClaudeMarketplaceRepo()
                    ?: return@withContext InstallResult(false, pluginName, destDir, listOf("无法拉取官方市场仓库"))
                val rel = source.path.removePrefix("./").trimStart('/')
                val srcPath = "$repo/$rel"
                if (!fs.exists(srcPath)) {
                    return@withContext InstallResult(false, pluginName, destDir, listOf("市场路径不存在: $rel"))
                }
                val out = runCatching {
                    shellExec("mkdir -p '$PLUGINS_DIR' && cp -a '$srcPath' '$destDir' 2>&1")
                }.getOrElse {
                    return@withContext InstallResult(false, pluginName, destDir, listOf("复制失败: ${it.message}"))
                }
                if (!fs.exists(destDir)) {
                    return@withContext InstallResult(false, pluginName, destDir, listOf("复制失败: ${out.take(400)}"))
                }
            }
            "url", "github" -> {
                val url = source.url.ifBlank {
                    if (source.repo.isNotBlank()) "https://github.com/${source.repo}.git" else ""
                }
                if (url.isBlank()) {
                    return@withContext InstallResult(false, pluginName, destDir, listOf("缺少 git URL"))
                }
                val ref = source.ref.ifBlank { source.commit }
                val cloneCmd = if (ref.isNotBlank()) {
                    "mkdir -p '$PLUGINS_DIR' && git clone --depth 1 --branch '$ref' '$url' '$destDir' 2>&1 || (rm -rf '$destDir' && git clone '$url' '$destDir' 2>&1 && cd '$destDir' && git checkout '$ref' 2>&1)"
                } else {
                    "mkdir -p '$PLUGINS_DIR' && git clone --depth 1 '$url' '$destDir' 2>&1"
                }
                val out = runCatching { shellExec(cloneCmd, timeoutMs = 300_000L) }.getOrElse {
                    return@withContext InstallResult(false, pluginName, destDir, listOf("Git clone failed: ${it.message}"))
                }
                if (!fs.exists(destDir) || out.contains("fatal:")) {
                    runCatching { shellExec("rm -rf '$destDir'") }
                    return@withContext InstallResult(false, pluginName, destDir, listOf("Git clone failed: ${out.take(500)}"))
                }
            }
            "git-subdir" -> {
                val url = source.url
                val sub = source.path.trimStart('/')
                if (url.isBlank() || sub.isBlank()) {
                    return@withContext InstallResult(false, pluginName, destDir, listOf("git-subdir 缺少 url/path"))
                }
                val tmp = "$PLUGINS_DIR/.tmp-market-$pluginName"
                runCatching { shellExec("rm -rf '$tmp'") }
                val ref = source.ref.ifBlank { "HEAD" }
                val out = runCatching {
                    shellExec(
                        """
                        set -e
                        mkdir -p '$PLUGINS_DIR'
                        git clone --depth 1 --filter=blob:none --sparse '$url' '$tmp' 2>&1 || git clone --depth 1 '$url' '$tmp' 2>&1
                        cd '$tmp'
                        if git sparse-checkout set '$sub' 2>/dev/null; then true; fi
                        if [ -n '$ref' ] && [ '$ref' != HEAD ]; then git fetch --depth 1 origin '$ref' 2>/dev/null || true; git checkout '$ref' 2>/dev/null || true; fi
                        if [ -d '$tmp/$sub' ]; then
                          cp -a '$tmp/$sub' '$destDir'
                        else
                          echo 'subdir missing: $sub'
                          exit 2
                        fi
                        rm -rf '$tmp'
                        """.trimIndent(),
                        timeoutMs = 360_000L,
                    )
                }.getOrElse {
                    runCatching { shellExec("rm -rf '$tmp' '$destDir'") }
                    return@withContext InstallResult(false, pluginName, destDir, listOf("git-subdir failed: ${it.message}"))
                }
                if (!fs.exists(destDir)) {
                    runCatching { shellExec("rm -rf '$tmp' '$destDir'") }
                    return@withContext InstallResult(false, pluginName, destDir, listOf("git-subdir failed: ${out.take(500)}"))
                }
            }
            else -> {
                return@withContext InstallResult(false, pluginName, destDir, listOf("不支持的源类型: ${source.type}"))
            }
        }

        if (discoverPlugin(destDir) == null) {
            writeManifest(
                destDir,
                PluginManifest(
                    name = pluginName,
                    version = entry.version.ifBlank { "0.1.0" },
                    description = entry.description,
                    author = entry.author,
                    enabled = true,
                ),
            )
        }
        runCatching {
            fs.writeText(
                "$destDir/.andmx-install-source",
                "marketplace:${entry.marketplace}:${entry.name}",
            )
        }
        val final = discoverPlugin(destDir)
        if (final == null) {
            runCatching { shellExec("rm -rf '$destDir'") }
            return@withContext InstallResult(false, pluginName, destDir, listOf("无法识别插件结构"))
        }
        discover()
        bumpReload()
        InstallResult(true, final.manifest.name, destDir)
    }

suspend fun uninstall(pluginName: String): Boolean = withContext(Dispatchers.IO) {
        val discovery = discover()
        val plugin = discovery.plugins.find {
            it.manifest.name.equals(pluginName, true) || it.dir.endsWith("/$pluginName")
        } ?: return@withContext false
        val out = runCatching { shellExec("rm -rf '${plugin.dir}' 2>&1") }.getOrDefault("")
        val gone = !fs.exists(plugin.dir)
        if (!gone) Log.w(TAG, "uninstall incomplete: $out")
        discover()
        bumpReload()
        gone
    }

    suspend fun setEnabled(pluginName: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val discovery = discover()
        val plugin = discovery.plugins.find { it.manifest.name == pluginName } ?: return@withContext false
        val flag = "${plugin.dir}/$DISABLED_FLAG"
        if (enabled) {
            runCatching { fs.deleteFile(flag) }
        } else {
            runCatching { fs.writeText(flag, "1") }
        }
        val manifestPath = when {
            fs.exists("${plugin.dir}/$ANDMX_PLUGIN_DIR/$CODEX_MANIFEST") ->
                "${plugin.dir}/$ANDMX_PLUGIN_DIR/$CODEX_MANIFEST"
            fs.exists("${plugin.dir}/$ZCODE_PLUGIN_DIR/$CODEX_MANIFEST") ->
                "${plugin.dir}/$ZCODE_PLUGIN_DIR/$CODEX_MANIFEST"
            fs.exists("${plugin.dir}/$CODEX_PLUGIN_DIR/$CODEX_MANIFEST") ->
                "${plugin.dir}/$CODEX_PLUGIN_DIR/$CODEX_MANIFEST"
            fs.exists("${plugin.dir}/$CODEX_MANIFEST") -> "${plugin.dir}/$CODEX_MANIFEST"
            fs.exists("${plugin.dir}/$CLAUDE_MANIFEST") -> "${plugin.dir}/$CLAUDE_MANIFEST"
            else -> null
        }
        if (manifestPath != null) {
            runCatching {
                val content = fs.readText(manifestPath)
                val manifest = parseManifest(content, plugin.manifest.name).copy(enabled = enabled)
                fs.writeText(manifestPath, json.encodeToString(PluginManifest.serializer(), manifest))
            }
        }
        discover()
        bumpReload()
        true
    }

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
                    sb.appendLine(content.take(4000))
                    sb.appendLine()
                }
            }
        }
        return sb.toString().trim()
    }

    suspend fun listPluginSkills(includeDisabled: Boolean = true): List<PluginSkillEntry> = withContext(Dispatchers.IO) {
        val discovery = discover()
        val out = mutableListOf<PluginSkillEntry>()
        for (plugin in discovery.plugins) {
            if (!includeDisabled && !plugin.enabled) continue
            for (skillFile in plugin.manifest.skills) {
                val skillPath = "${plugin.dir}/$skillFile"
                if (!fs.exists(skillPath)) continue
                val content = runCatching { fs.readText(skillPath) }.getOrNull().orEmpty()
                val fm = parseSkillFrontmatter(content)
                val leaf = skillFile
                    .removeSuffix("/SKILL.md")
                    .removeSuffix("SKILL.md")
                    .substringAfterLast('/')
                    .ifBlank { plugin.manifest.name }
                val name = fm["name"]?.trim().orEmpty().ifBlank { leaf }
                val description = fm["description"]?.trim().orEmpty()
                    .ifBlank { plugin.manifest.description }
                out += PluginSkillEntry(
                    id = "${plugin.manifest.name}:$name",
                    name = name,
                    description = description,
                    path = skillPath,
                    pluginName = plugin.manifest.name,
                    pluginId = plugin.manifest.name,
                    pluginEnabled = plugin.enabled,
                    marketplace = marketplaceLabel(plugin),
                )
            }
        }
        out.sortedWith(compareBy({ it.name.lowercase() }, { it.pluginName.lowercase() }))
    }

    private fun parseSkillFrontmatter(content: String): Map<String, String> {
        if (!content.startsWith("---")) return emptyMap()
        val end = content.indexOf("---", 3)
        if (end <= 0) return emptyMap()
        val block = content.substring(3, end)
        val map = linkedMapOf<String, String>()
        for (line in block.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isNotBlank()) map[key] = value
        }
        return map
    }

    suspend fun listInvocableSkills(): List<Pair<String, String>> {
        return listPluginSkills(includeDisabled = false).map { it.name to it.path }
    }

    
    data class PluginCommand(
        val name: String,
        val description: String,
        val pluginName: String,
        val path: String,
        val body: String,
        val skills: List<String> = emptyList(),
    )

    suspend fun listPluginCommands(): List<PluginCommand> = withContext(Dispatchers.IO) {
        val discovery = discover()
        val out = mutableListOf<PluginCommand>()
        for (plugin in discovery.plugins.filter { it.enabled }) {
            for (rel in plugin.manifest.commands) {
                val path = "${plugin.dir}/$rel"
                if (!fs.exists(path)) continue
                val raw = runCatching { fs.readText(path) }.getOrNull().orEmpty()
                val name = rel.substringAfterLast('/').removeSuffix(".md")
                val desc = Regex("""(?m)^description:\s*(.+)$""").find(raw)?.groupValues?.getOrNull(1)?.trim()
                    ?: plugin.manifest.description.ifBlank { name }
                val skills = Regex("""(?m)^skills:\s*(.+)$""").find(raw)?.groupValues?.getOrNull(1)
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                val body = raw.substringAfter("---", raw).substringAfter("---", raw).trim()
                out += PluginCommand(
                    name = name,
                    description = desc,
                    pluginName = plugin.manifest.name,
                    path = path,
                    body = body,
                    skills = skills,
                )
            }
        }
        out
    }

    fun marketplaceLabel(plugin: Plugin): String {
        val src = plugin.installSource.trim()
        return when {
            src.startsWith("marketplace:builtin:") -> "andmx-plugins-official"
            src.startsWith("marketplace:") -> {
                val rest = src.removePrefix("marketplace:")
                rest.substringBefore(":").ifBlank { "andmx-plugins-official" }
            }
            plugin.source == PluginSource.BUILTIN || src == "builtin" -> "andmx-plugins-official"
            src.startsWith("http") || src.startsWith("git@") -> "git"
            else -> "local"
        }
    }

    fun displayMcpName(pluginName: String, serverName: String): String {
        val prefix = "plugin:$pluginName:"
        return if (serverName.startsWith(prefix)) serverName.removePrefix(prefix) else serverName
    }

    suspend fun listPluginMcpEntries(
        runtimeToolCounts: Map<String, Int> = emptyMap(),
    ): List<PluginMcpEntry> = withContext(Dispatchers.IO) {
        val discovery = discover()
        val androidTools = runCatching {
            com.andmx.agent.plugins.mobile.AndroidDevToolset(context).tools().size
        }.getOrDefault(23)
        val storageTools = runCatching {
            com.andmx.agent.plugins.deviceutils.StorageCleanupToolset(context).tools().size
        }.getOrDefault(10)
        val htmlVideoTools = runCatching {
            com.andmx.agent.plugins.htmlvideo.HtmlVideoToolset(context).tools().size
        }.getOrDefault(9)
        val forgeTools = runCatching {
            com.andmx.agent.plugins.devforge.DevForgeToolset(context).tools().size
        }.getOrDefault(8)
        discovery.plugins.flatMap { plugin ->
            val names = plugin.manifest.mcpServers
                .map { displayMcpName(plugin.manifest.name, it) }
                .distinct()
            names.map { serverName ->
                val runtimeKey = "plugin:${plugin.manifest.name}:$serverName"
                val nativeCount = BuiltinNativeMcp.toolCount(
                    plugin.manifest.name,
                    serverName,
                    androidTools,
                    storageTools,
                    htmlVideoTools,
                    forgeTools,
                )
                val runtimeCount = runtimeToolCounts[serverName]
                    ?: runtimeToolCounts[runtimeKey]
                    ?: runtimeToolCounts[plugin.manifest.name]
                val isNative = BuiltinNativeMcp.isNative(plugin.manifest.name, serverName)
                val resolvedActive = plugin.enabled && (
                    isNative || (runtimeCount != null && runtimeCount > 0)
                )
                val toolCount = when {
                    !plugin.enabled -> null
                    nativeCount != null -> nativeCount
                    runtimeCount != null -> runtimeCount
                    else -> null
                }
                val status = when {
                    !plugin.enabled -> null
                    resolvedActive -> "connected"
                    runtimeCount != null && runtimeCount <= 0 -> "error"
                    else -> null
                }
                PluginMcpEntry(
                    id = "${plugin.manifest.name}:$serverName",
                    name = serverName,
                    pluginName = plugin.manifest.name,
                    pluginId = plugin.manifest.name,
                    pluginEnabled = plugin.enabled,
                    active = resolvedActive,
                    toolCount = toolCount,
                    marketplace = marketplaceLabel(plugin),
                    status = status,
                    error = if (plugin.enabled && !resolvedActive) {
                        "插件声明了该 MCP 服务器，但当前未成功加载"
                    } else null,
                    description = plugin.manifest.description,
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    object BuiltinNativeMcp {
        private val androidNames = setOf("andmx-android-dev", "android-emulator")
        private val androidServers = setOf("android-emulator")
        private val storageNames = setOf("andmx-storage-cleanup", "device-storage")
        private val storageServers = setOf("device-storage")
        private val htmlVideoNames = setOf("andmx-html-video", "html-video")
        private val htmlVideoServers = setOf("html-video")
        private val forgeNames = setOf("andmx-dev-forge", "dev-forge")
        private val forgeServers = setOf("dev-forge")

        fun isNative(pluginName: String, serverName: String): Boolean {
            val p = pluginName.lowercase()
            val s = serverName.lowercase()
            return (p in androidNames && (s in androidServers || s == p)) ||
                (p in storageNames && (s in storageServers || s == p)) ||
                (p in htmlVideoNames && (s in htmlVideoServers || s == p)) ||
                (p in forgeNames && (s in forgeServers || s == p))
        }

        fun isAndroid(pluginName: String, serverName: String = ""): Boolean {
            val p = pluginName.lowercase()
            val s = serverName.lowercase()
            return p in androidNames || s in androidServers
        }

        fun isStorage(pluginName: String, serverName: String = ""): Boolean {
            val p = pluginName.lowercase()
            val s = serverName.lowercase()
            return p in storageNames || s in storageServers
        }

        fun isHtmlVideo(pluginName: String, serverName: String = ""): Boolean {
            val p = pluginName.lowercase()
            val s = serverName.lowercase()
            return p in htmlVideoNames || s in htmlVideoServers
        }

        fun isForge(pluginName: String, serverName: String = ""): Boolean {
            val p = pluginName.lowercase()
            val s = serverName.lowercase()
            return p in forgeNames || s in forgeServers
        }

        fun toolCount(
            pluginName: String,
            serverName: String,
            androidTools: Int,
            storageTools: Int = 10,
            htmlVideoTools: Int = 9,
            forgeTools: Int = 8,
        ): Int? {
            if (!isNative(pluginName, serverName)) return null
            return when {
                isAndroid(pluginName, serverName) -> androidTools
                isStorage(pluginName, serverName) -> storageTools
                isHtmlVideo(pluginName, serverName) -> htmlVideoTools
                isForge(pluginName, serverName) -> forgeTools
                else -> null
            }
        }

        fun providesAndroidTools(pluginName: String): Boolean =
            pluginName.lowercase() in androidNames

        fun providesStorageTools(pluginName: String): Boolean =
            pluginName.lowercase() in storageNames

        fun providesHtmlVideoTools(pluginName: String): Boolean =
            pluginName.lowercase() in htmlVideoNames

        fun providesForgeTools(pluginName: String): Boolean =
            pluginName.lowercase() in forgeNames
    }

    suspend fun loadPluginTools(context: Context): List<com.andmx.agent.Tool> {
        val discovery = discover()
        val tools = mutableListOf<com.andmx.agent.Tool>()
        for (plugin in discovery.plugins.filter { it.enabled }) {
            for (script in plugin.manifest.tools) {
                if (script.isBlank()) continue
                val safeName = plugin.manifest.name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                val safeScript = script.filter { it.isLetterOrDigit() || it in "._-/" }
                    .replace('/', '_')
                val toolName = "plugin_${safeName}_${safeScript}"
                val desc = "插件 ${plugin.manifest.name} 工具 ($script)。" +
                    if (plugin.manifest.description.isNotBlank()) " ${plugin.manifest.description}" else ""
                tools += PluginTool(context, toolName, plugin.dir, script, desc)
            }
        }
        return tools
    }

    suspend fun allHooks(): Map<String, List<HookEntry>> {
        val discovery = discover()
        val hooks = mutableMapOf<String, MutableList<HookEntry>>()
        for (plugin in discovery.plugins.filter { it.enabled }) {
            for (hook in plugin.manifest.hooks) {
                val list = hooks.getOrPut(hook.event) { mutableListOf() }
                list.add(
                    hook.copy(
                        name = if (hook.name.isBlank()) "${plugin.manifest.name}:${hook.event}" else hook.name,
                    ),
                )
            }
        }
        return hooks
    }

    private suspend fun writeManifest(dir: String, manifest: PluginManifest) {
        val codexDir = "$dir/$CODEX_PLUGIN_DIR"
        runCatching { fs.writeText("$codexDir/.gitkeep", "") }
        val path = if (fs.exists(codexDir) || true) {
            "$codexDir/$CODEX_MANIFEST"
        } else {
            "$dir/$CODEX_MANIFEST"
        }
        fs.writeText(path, json.encodeToString(PluginManifest.serializer(), manifest))
    }

    private fun normalizeName(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .take(64)
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
