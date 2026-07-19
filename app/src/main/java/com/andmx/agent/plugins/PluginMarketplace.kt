package com.andmx.agent.plugins

import android.content.Context
import android.util.Log
import com.andmx.exec.PersistentShell
import com.andmx.exec.files.GuestFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class PluginMarketplace(
    private val context: Context,
    private val fs: GuestFs,
    private val shell: PersistentShell? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }

    companion object {
        private const val TAG = "PluginMarketplace"
        const val MARKET_DIR = "/root/.andmx/marketplaces"
        const val CACHE_CATALOG = "$MARKET_DIR/catalog.json"
        const val CLAUDE_REPO = "https://github.com/anthropics/claude-plugins-official.git"
        const val CLAUDE_ID = "claude-plugins-official"
        const val ANDMX_ID = "andmx-plugins-official"
        private val REMOTE_CATALOG_URLS = listOf(
            "https://cdn.jsdelivr.net/gh/anthropics/claude-plugins-official@main/marketplace.json",
            "https://cdn.jsdelivr.net/gh/anthropics/claude-plugins-official@master/marketplace.json",
            "https://raw.githubusercontent.com/anthropics/claude-plugins-official/main/marketplace.json",
            "https://raw.githubusercontent.com/anthropics/claude-plugins-official/master/marketplace.json",
        )
    }

    @Serializable
    data class Catalog(
        val version: Int = 1,
        val updatedAt: String = "",
        val marketplaces: List<MarketplaceMeta> = emptyList(),
        val plugins: List<CatalogPlugin> = emptyList(),
    )

    @Serializable
    data class MarketplaceMeta(
        val id: String,
        val name: String = "",
        val description: String = "",
        val source: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class CatalogPlugin(
        val name: String,
        val description: String = "",
        val author: String = "",
        val category: String = "uncategorized",
        val homepage: String = "",
        val marketplace: String = "",
        val version: String = "",
        val source: CatalogSource? = null,
    )

    @Serializable
    data class CatalogSource(
        val type: String = "",
        val url: String = "",
        val path: String = "",
        val ref: String = "",
        val sha: String = "",
        val repo: String = "",
        val commit: String = "",
        val id: String = "",
    )

    data class BrowseResult(
        val catalog: Catalog,
        val from: String,
        val error: String? = null,
    )

    private var ephemeralShell: PersistentShell? = null

    private suspend fun shellExec(command: String, timeoutMs: Long = 180_000L): String {
        val s = when {
            shell != null -> shell
            ephemeralShell != null -> ephemeralShell!!
            else -> PersistentShell(context).also { ephemeralShell = it }
        }
        s.start()
        val res = s.exec(command, timeoutMs)
        return (res.stdout + "\n" + res.stderr).trim()
    }

    suspend fun loadCatalog(forceRefresh: Boolean = false): BrowseResult = withContext(Dispatchers.IO) {
        val bundled = readBundledCatalog()
        if (!forceRefresh) {
            val cached = readCachedCatalog()
            if (cached != null && cached.plugins.isNotEmpty()) {
                return@withContext BrowseResult(cached, "cache")
            }
            if (bundled != null) {
                runCatching { writeCached(bundled) }
                return@withContext BrowseResult(bundled, "bundled")
            }
        }

        val remote = fetchRemoteClaudeCatalog()
        if (remote != null) {
            val merged = mergeWithOfficial(remote, bundled)
            runCatching { writeCached(merged) }
            return@withContext BrowseResult(merged, "remote")
        }

        val cloned = tryRefreshViaGitClone()
        if (cloned != null) {
            val merged = mergeWithOfficial(cloned, bundled)
            runCatching { writeCached(merged) }
            return@withContext BrowseResult(merged, "git")
        }

        val fallback = readCachedCatalog() ?: bundled
        if (fallback != null) {
            return@withContext BrowseResult(fallback, if (readCachedCatalog() != null) "cache" else "bundled")
        }
        BrowseResult(Catalog(), "empty", error = "无法加载插件市场目录")
    }

    private fun readBundledCatalog(): Catalog? {
        return runCatching {
            context.assets.open("andmx-marketplace/catalog.json").bufferedReader().use { it.readText() }
                .let { json.decodeFromString(Catalog.serializer(), it) }
        }.onFailure { Log.w(TAG, "bundled catalog: ${it.message}") }.getOrNull()
    }

    private fun readCachedCatalog(): Catalog? {
        return runCatching {
            if (!fs.exists(CACHE_CATALOG)) return null
            json.decodeFromString(Catalog.serializer(), fs.readText(CACHE_CATALOG))
        }.getOrNull()
    }

    private fun writeCached(catalog: Catalog) {
        runCatching {
            fs.writeText("$MARKET_DIR/.gitkeep", "")
            fs.writeText(CACHE_CATALOG, json.encodeToString(Catalog.serializer(), catalog))
        }
    }

    private fun fetchRemoteClaudeCatalog(): Catalog? {
        for (url in REMOTE_CATALOG_URLS) {
            val body = httpGet(url) ?: continue
            if (body.isBlank() || body.startsWith("404") || !body.contains("\"plugins\"")) continue
            val parsed = parseClaudeMarketplaceJson(body) ?: continue
            if (parsed.plugins.isNotEmpty()) {
                Log.i(TAG, "Loaded remote marketplace from $url (${parsed.plugins.size})")
                return parsed
            }
        }
        return null
    }

    private suspend fun tryRefreshViaGitClone(): Catalog? {
        val dest = "$MARKET_DIR/$CLAUDE_ID"
        val out = runCatching {
            if (fs.exists("$dest/marketplace.json")) {
                shellExec("cd '$dest' && git pull --ff-only 2>&1 || true", timeoutMs = 180_000L)
            } else {
                shellExec(
                    "mkdir -p '$MARKET_DIR' && rm -rf '$dest' && git clone --depth 1 '$CLAUDE_REPO' '$dest' 2>&1",
                    timeoutMs = 300_000L,
                )
            }
        }.getOrDefault("")
        if (!fs.exists("$dest/marketplace.json")) {
            Log.w(TAG, "git marketplace clone failed: ${out.take(400)}")
            return null
        }
        val body = runCatching { fs.readText("$dest/marketplace.json") }.getOrNull() ?: return null
        return parseClaudeMarketplaceJson(body)
    }

    private fun parseClaudeMarketplaceJson(body: String): Catalog? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val name = root.str("name") ?: CLAUDE_ID
            val desc = root.str("description").orEmpty()
            val plugins = mutableListOf<CatalogPlugin>()
            root["plugins"]?.jsonArray?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val pluginName = o.str("name") ?: return@forEach
                val authorEl = o["author"]
                val author = when (authorEl) {
                    is JsonObject -> authorEl.str("name").orEmpty()
                    is JsonPrimitive -> authorEl.contentOrNull.orEmpty()
                    else -> ""
                }
                val source = parseSource(o["source"])
                plugins += CatalogPlugin(
                    name = pluginName,
                    description = o.str("description").orEmpty(),
                    author = author,
                    category = o.str("category") ?: "uncategorized",
                    homepage = o.str("homepage").orEmpty(),
                    marketplace = CLAUDE_ID,
                    source = source,
                )
            }
            Catalog(
                version = 1,
                updatedAt = System.currentTimeMillis().toString(),
                marketplaces = listOf(
                    MarketplaceMeta(
                        id = CLAUDE_ID,
                        name = name,
                        description = desc,
                        source = mapOf("type" to "github", "repo" to "anthropics/claude-plugins-official"),
                    ),
                ),
                plugins = plugins,
            )
        }.onFailure { Log.w(TAG, "parse marketplace: ${it.message}") }.getOrNull()
    }

    private fun parseSource(el: JsonElement?): CatalogSource? {
        if (el == null) return null
        return when (el) {
            is JsonPrimitive -> CatalogSource(type = "path", path = el.contentOrNull.orEmpty())
            is JsonObject -> {
                val t = el.str("source").orEmpty()
                when (t) {
                    "url" -> CatalogSource(
                        type = "url",
                        url = el.str("url").orEmpty(),
                        sha = el.str("sha").orEmpty(),
                        ref = el.str("ref").orEmpty(),
                    )
                    "git-subdir" -> CatalogSource(
                        type = "git-subdir",
                        url = el.str("url").orEmpty(),
                        path = el.str("path").orEmpty(),
                        ref = el.str("ref").orEmpty(),
                        sha = el.str("sha").orEmpty(),
                    )
                    "github" -> {
                        val repo = el.str("repo").orEmpty()
                        CatalogSource(
                            type = "github",
                            repo = repo,
                            url = if (repo.isNotBlank()) "https://github.com/$repo.git" else "",
                            commit = el.str("commit").orEmpty(),
                            sha = el.str("sha").orEmpty(),
                        )
                    }
                    else -> CatalogSource(
                        type = t.ifBlank { "unknown" },
                        url = el.str("url").orEmpty(),
                        path = el.str("path").orEmpty(),
                        ref = el.str("ref").orEmpty(),
                        sha = el.str("sha").orEmpty(),
                        repo = el.str("repo").orEmpty(),
                        commit = el.str("commit").orEmpty(),
                        id = el.str("id").orEmpty(),
                    )
                }
            }
            else -> null
        }
    }

    private fun mergeWithOfficial(remote: Catalog, bundled: Catalog?): Catalog {
        val official = bundled?.plugins?.filter { it.marketplace == ANDMX_ID }.orEmpty()
        val officialNames = official.map { it.name }.toSet()
        val remotePlugins = remote.plugins.filter { it.name !in officialNames }
        val markets = (bundled?.marketplaces.orEmpty() + remote.marketplaces)
            .distinctBy { it.id }
        return Catalog(
            version = 1,
            updatedAt = remote.updatedAt.ifBlank { System.currentTimeMillis().toString() },
            marketplaces = markets.ifEmpty { remote.marketplaces },
            plugins = official + remotePlugins,
        )
    }

    private fun httpGet(url: String): String? {
        return runCatching {
            var current = url
            repeat(4) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "AndMX-PluginMarketplace/1.0")
                    setRequestProperty("Accept", "application/json,text/plain,*/*")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location") ?: return null
                    conn.disconnect()
                    return@repeat
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                conn.disconnect()
                return if (code in 200..299) body else null
            }
            null
        }.onFailure { Log.w(TAG, "httpGet $url: ${it.message}") }.getOrNull()
    }

    suspend fun ensureClaudeMarketplaceRepo(): String? = withContext(Dispatchers.IO) {
        val dest = "$MARKET_DIR/$CLAUDE_ID"
        if (fs.exists("$dest/marketplace.json")) return@withContext dest
        val out = runCatching {
            shellExec(
                "mkdir -p '$MARKET_DIR' && git clone --depth 1 '$CLAUDE_REPO' '$dest' 2>&1",
                timeoutMs = 300_000L,
            )
        }.getOrDefault("")
        if (fs.exists("$dest/marketplace.json")) dest else {
            Log.w(TAG, "ensure repo failed: ${out.take(400)}")
            null
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}

