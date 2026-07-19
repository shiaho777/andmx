package com.andmx.agent.plugins.mobile

import android.content.Context

object MobilePluginConfig {
    private const val PREFS = "andmx_mobile_plugin_config"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun androidSdkPath(context: Context): String =
        prefs(context).getString("android.sdk_path", "").orEmpty()

    fun androidDefaultAvd(context: Context): String =
        prefs(context).getString("android.default_avd", "medium_phone").orEmpty().ifBlank { "medium_phone" }

    fun androidApiLevel(context: Context): String =
        prefs(context).getString("android.api_level", "35").orEmpty().ifBlank { "35" }

    fun androidBuildTools(context: Context): String =
        prefs(context).getString("android.build_tools_version", "35.0.0").orEmpty().ifBlank { "35.0.0" }

    fun androidSystemImageVariant(context: Context): String =
        prefs(context).getString("android.system_image_variant", "default").orEmpty().ifBlank { "default" }

    fun androidSystemImageAbi(context: Context): String =
        prefs(context).getString("android.system_image_abi", "").orEmpty()

    fun androidJdkMajor(context: Context): String =
        prefs(context).getString("android.jdk_major", "17").orEmpty().ifBlank { "17" }

    fun setAndroid(context: Context, key: String, value: String) {
        prefs(context).edit().putString("android.$key", value).apply()
    }

    fun allAndroid(context: Context): Map<String, String> = mapOf(
        "sdk_path" to androidSdkPath(context),
        "default_avd" to androidDefaultAvd(context),
        "api_level" to androidApiLevel(context),
        "build_tools_version" to androidBuildTools(context),
        "system_image_variant" to androidSystemImageVariant(context),
        "system_image_abi" to androidSystemImageAbi(context),
        "jdk_major" to androidJdkMajor(context),
    )

    fun resolvedSdk(context: Context): String? {
        val configured = androidSdkPath(context).trim()
        if (configured.isNotBlank()) return configured
        return MobileHostShell.androidSdkRoot()
    }

    fun resolvedAbi(context: Context): String {
        val override = androidSystemImageAbi(context).trim()
        if (override.isNotBlank()) return override
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return if (arch.contains("aarch64") || arch.contains("arm64")) "arm64-v8a" else "x86_64"
    }
}
