package com.andmx.agent.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

object BuiltinSeedPolicy {
    const val COPY_ATTEMPTS = 3

    enum class SeedAction { SKIP, SEED }

    fun decide(
        markerVersion: String?,
        targetVersion: Int,
        destExists: Boolean,
        manifestHealthy: Boolean,
    ): SeedAction =
        if (markerVersion?.trim() == targetVersion.toString() && destExists && manifestHealthy) {
            SeedAction.SKIP
        } else {
            SeedAction.SEED
        }

    fun manifestHealthy(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val obj = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return false
        val name = runCatching { obj["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return !name.isNullOrBlank()
    }

    fun <T> withRetry(attempts: Int = COPY_ATTEMPTS, block: (attempt: Int) -> T): T {
        require(attempts >= 1) { "attempts must be >= 1" }
        var last: Exception? = null
        for (attempt in 1..attempts) {
            try {
                return block(attempt)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("retry exhausted")
    }

    fun swapIn(staging: File, dest: File): Boolean {
        if (!staging.exists()) return false
        val parent = dest.parentFile ?: return false
        parent.mkdirs()
        val backup = File(parent, ".${dest.name}.seed-backup")
        backup.deleteRecursively()
        val hadOld = dest.exists()
        if (hadOld && !dest.renameTo(backup)) return false
        if (!staging.renameTo(dest)) {
            if (hadOld) backup.renameTo(dest)
            return false
        }
        if (hadOld) backup.deleteRecursively()
        return true
    }
}
