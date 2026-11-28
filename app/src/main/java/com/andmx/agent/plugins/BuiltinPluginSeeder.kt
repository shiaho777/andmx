package com.andmx.agent.plugins

import android.content.Context
import android.util.Log
import com.andmx.exec.files.GuestFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BuiltinPluginSeeder {
    private const val TAG = "BuiltinPluginSeeder"
    private const val ASSET_ROOT = "andmx-plugins"
    private const val PREFS = "andmx_builtin_plugins"
    private const val KEY_VERSION = "seeded_version"
    private const val VERSION = 12

    data class SeedFailure(val name: String, val reason: String)

    data class SeedReport(
        val installed: List<String>,
        val failures: List<SeedFailure> = emptyList(),
        val assetError: String? = null,
    )

    suspend fun ensureSeeded(context: Context, fs: GuestFs = GuestFs(context)): List<String> =
        seedWithReport(context, fs).installed

    suspend fun seedWithReport(
        context: Context,
        fs: GuestFs = GuestFs(context),
    ): SeedReport = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installed = mutableListOf<String>()
        val failures = mutableListOf<SeedFailure>()
        val am = context.assets
        val listing = runCatching { am.list(ASSET_ROOT)?.toList().orEmpty() }
        val names = listing.getOrElse {
            Log.w(TAG, "Builtin plugin assets unavailable: ${it.message}")
            return@withContext SeedReport(emptyList(), assetError = it.message ?: "assets unavailable")
        }
        if (names.isEmpty()) return@withContext SeedReport(emptyList())
        runCatching { fs.writeText("${PluginSystem.PLUGINS_DIR}/.gitkeep", "") }
        runCatching {
            val legacyIos = "${PluginSystem.PLUGINS_DIR}/andmx-ios-dev"
            if (fs.exists(legacyIos)) {
                fs.resolve(legacyIos).deleteRecursively()
                Log.i(TAG, "Removed legacy builtin plugin andmx-ios-dev")
            }
        }
        for (name in names) {
            val dest = "${PluginSystem.PLUGINS_DIR}/$name"
            val marker = "$dest/.andmx-seed-version"
            val current = runCatching { if (fs.exists(marker)) fs.readText(marker).trim() else null }.getOrNull()
            val healthy = runCatching { manifestHealthy(dest, fs) }.getOrDefault(false)
            val action = BuiltinSeedPolicy.decide(current, VERSION, fs.exists(dest), healthy)
            if (action == BuiltinSeedPolicy.SeedAction.SKIP) {
                installed += name
                continue
            }
            val staging = "${PluginSystem.PLUGINS_DIR}/.seed-staging-$name"
            val stagingFile = fs.resolve(staging)
            val outcome = runCatching {
                BuiltinSeedPolicy.withRetry {
                    stagingFile.deleteRecursively()
                    copyAssetDir(am, "$ASSET_ROOT/$name", staging, fs)
                }
                val stagedManifest = runCatching { readManifestText(staging, fs) }.getOrNull()
                if (!BuiltinSeedPolicy.manifestHealthy(stagedManifest)) {
                    error("staged manifest missing or corrupt")
                }
                fs.writeText("$staging/.andmx-seed-version", VERSION.toString())
                fs.writeText("$staging/.andmx-install-source", "builtin")
                if (!BuiltinSeedPolicy.swapIn(stagingFile, fs.resolve(dest))) {
                    error("atomic replace failed")
                }
            }
            stagingFile.deleteRecursively()
            outcome.onSuccess {
                installed += name
                Log.i(TAG, "Seeded builtin plugin $name -> $dest")
            }.onFailure {
                failures += SeedFailure(name, it.message ?: "seed failed")
                Log.w(TAG, "Failed to seed $name (previous version kept): ${it.message}")
            }
        }
        prefs.edit().putInt(KEY_VERSION, VERSION).apply()
        SeedReport(installed, failures)
    }

    private fun manifestCandidates(dir: String): List<String> = listOf(
        "$dir/${PluginSystem.ANDMX_PLUGIN_DIR}/${PluginSystem.CODEX_MANIFEST}",
        "$dir/${PluginSystem.ZCODE_PLUGIN_DIR}/${PluginSystem.CODEX_MANIFEST}",
        "$dir/${PluginSystem.CODEX_PLUGIN_DIR}/${PluginSystem.CODEX_MANIFEST}",
        "$dir/${PluginSystem.CODEX_MANIFEST}",
        "$dir/${PluginSystem.CLAUDE_MANIFEST}",
    )

    private fun readManifestText(dir: String, fs: GuestFs): String? =
        manifestCandidates(dir).firstOrNull { fs.exists(it) }?.let { fs.readText(it) }

    private fun manifestHealthy(dir: String, fs: GuestFs): Boolean =
        BuiltinSeedPolicy.manifestHealthy(readManifestText(dir, fs))

    private fun copyAssetDir(
        am: android.content.res.AssetManager,
        assetPath: String,
        guestDest: String,
        fs: GuestFs,
    ) {
        val children = am.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            am.open(assetPath).use { input ->
                val bytes = input.readBytes()
                val host = fs.resolve(guestDest)
                host.parentFile?.mkdirs()
                host.writeBytes(bytes)
            }
            return
        }
        fs.resolve(guestDest).mkdirs()
        for (child in children) {
            copyAssetDir(am, "$assetPath/$child", "$guestDest/$child", fs)
        }
    }
}
