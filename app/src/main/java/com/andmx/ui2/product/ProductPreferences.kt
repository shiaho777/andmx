package com.andmx.ui2.product

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

internal class ProductPreferences(private val app: Context) {
    private val prefs = app.getSharedPreferences("andmx_product", Context.MODE_PRIVATE)

    fun shouldShowIntro(): Boolean {
        if (prefs.getBoolean("intro_complete", false)) return false
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (info.lastUpdateTime <= info.firstInstallTime) return true
        val version = PackageInfoCompat.getLongVersionCode(info)
        prefs.edit().putBoolean("intro_complete", true)
            .putLong("last_seen_version", version).apply()
        return false
    }

    fun completeIntro() {
        if (prefs.getBoolean("intro_complete", false)) return
        prefs.edit().putBoolean("intro_complete", true)
            .putLong("last_seen_version", currentVersion()).apply()
    }

    var introStep: String
        get() = prefs.getString("intro_step", null) ?: "start"
        set(value) { prefs.edit().putString("intro_step", value).apply() }

    var automaticUpdates: Boolean
        get() = prefs.getBoolean("automatic_updates", true)
        set(value) { prefs.edit().putBoolean("automatic_updates", value).apply() }

    var skippedVersion: Long
        get() = prefs.getLong("skipped_version", 0)
        set(value) { prefs.edit().putLong("skipped_version", value).apply() }

    var lastCheckTime: Long
        get() = prefs.getLong("last_check_time", 0)
        set(value) { prefs.edit().putLong("last_check_time", value).apply() }

    fun changelogPending(): Boolean {
        val previous = prefs.getLong("last_seen_version", 0)
        return previous > 0 && currentVersion() > previous
    }

    fun markChangelogSeen(): Boolean =
        prefs.edit().putLong("last_seen_version", currentVersion()).commit()

    private fun currentVersion() =
        PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
}
