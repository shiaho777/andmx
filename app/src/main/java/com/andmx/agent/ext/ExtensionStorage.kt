package com.andmx.agent.ext

import android.content.Context
import android.content.SharedPreferences

class ExtensionStorage(private val prefs: SharedPreferences) {
    fun get(pluginId: String, key: String): String? = prefs.getString("$pluginId:$key", null)

    fun set(pluginId: String, key: String, value: String) {
        prefs.edit().putString("$pluginId:$key", value).apply()
    }

    fun remove(pluginId: String, key: String) {
        prefs.edit().remove("$pluginId:$key").apply()
    }

    fun clearPlugin(pluginId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$pluginId:") }.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        const val PREFS = "andmx_extension_storage"

        fun create(context: Context): ExtensionStorage = ExtensionStorage(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )
    }
}
