package com.andmx.agent.plugins

import android.content.Context
import android.util.Log
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BuiltinPluginSeeder {
    private const val TAG = "BuiltinPluginSeeder"
    private const val ASSET_ROOT = "andmx-plugins"
    private const val PREFS = "andmx_builtin_plugins"
    private const val KEY_VERSION = "seeded_version"
    private const val VERSION = 9

    suspend fun ensureSeeded(context: Context, fs: GuestFs = GuestFs(ProotRuntime(context))): List<String> =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val installed = mutableListOf<String>()
            val am = context.assets
            val names = runCatching { am.list(ASSET_ROOT)?.toList().orEmpty() }.getOrDefault(emptyList())
            if (names.isEmpty()) return@withContext emptyList()
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
                val current = runCatching { if (fs.exists(marker)) fs.readText(marker).trim() else "" }.getOrDefault("")
                if (current == VERSION.toString() && fs.exists(dest)) {
                    installed += name
                    continue
                }
                runCatching {
                    copyAssetDir(am, "$ASSET_ROOT/$name", dest, fs)
                    fs.writeText(marker, VERSION.toString())
                    fs.writeText("$dest/.andmx-install-source", "builtin")
                    installed += name
                    Log.i(TAG, "Seeded builtin plugin $name -> $dest")
                }.onFailure {
                    Log.w(TAG, "Failed to seed $name: ${it.message}")
                }
            }
            prefs.edit().putInt(KEY_VERSION, VERSION).apply()
            installed
        }

    private fun copyAssetDir(
        am: android.content.res.AssetManager,
        assetPath: String,
        guestDest: String,
        fs: GuestFs,
    ) {
        val children = am.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // file
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
