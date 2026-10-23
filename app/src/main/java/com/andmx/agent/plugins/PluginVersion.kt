package com.andmx.agent.plugins

object PluginVersion {
    enum class Status { NONE, UPDATE_AVAILABLE, VERSION_CHANGED }

    fun compare(installed: String?, latest: String?): Status {
        if (installed.isNullOrBlank() || latest.isNullOrBlank()) return Status.NONE
        val installedValid = coerceSemver(installed)
        val latestValid = coerceSemver(latest)
        if (installedValid != null && latestValid != null) {
            return if (compareSemver(latestValid, installedValid) > 0) Status.UPDATE_AVAILABLE else Status.NONE
        }
        return if (installed == latest) Status.NONE else Status.VERSION_CHANGED
    }

    fun compareUpdate(
        installedVersion: String?,
        installedSha: String?,
        latestVersion: String?,
        latestSha: String?,
    ): Status {
        if (!latestVersion.isNullOrBlank()) return compare(installedVersion, latestVersion)
        if (!latestSha.isNullOrBlank()) {
            if (installedSha.isNullOrBlank()) return Status.VERSION_CHANGED
            return if (installedSha.equals(latestSha, ignoreCase = true)) Status.NONE else Status.UPDATE_AVAILABLE
        }
        return Status.NONE
    }

    private fun coerceSemver(raw: String): IntArray? {
        val match = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(raw.trim()) ?: return null
        return intArrayOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toIntOrNull() ?: 0,
            match.groupValues[3].toIntOrNull() ?: 0,
        )
    }

    private fun compareSemver(a: IntArray, b: IntArray): Int {
        for (i in 0..2) {
            val diff = a[i] - b[i]
            if (diff != 0) return diff
        }
        return 0
    }
}
