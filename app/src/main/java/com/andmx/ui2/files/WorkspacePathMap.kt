package com.andmx.ui2.files

import java.io.File

/** Maps host workspace paths to the guest paths recorded by ChangeTracker. */
object WorkspacePathMap {
    fun toGuest(hostPath: String, hostRoot: String, guestRoot: String): String? {
        if (hostRoot.isBlank() || guestRoot.isBlank()) return null
        val root = File(hostRoot).absolutePath.trimEnd('/')
        val abs = File(hostPath).absolutePath
        if (abs == root) return guestRoot.trimEnd('/')
        if (!abs.startsWith("$root/")) return null
        return guestRoot.trimEnd('/') + "/" + abs.removePrefix("$root/")
    }

    fun sameGuest(a: String, b: String): Boolean =
        a.trimEnd('/') == b.trimEnd('/')

    fun isChanged(
        hostPath: String,
        hostRoot: String,
        guestRoot: String,
        changedPaths: Set<String>,
        directory: Boolean,
    ): Boolean {
        val guest = toGuest(hostPath, hostRoot, guestRoot) ?: return false
        return if (directory) {
            changedPaths.any { it == guest || it.startsWith("$guest/") }
        } else {
            changedPaths.any { sameGuest(it, guest) }
        }
    }
}
