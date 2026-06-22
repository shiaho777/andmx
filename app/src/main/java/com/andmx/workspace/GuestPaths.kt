package com.andmx.workspace

import java.io.File

/**
 * Shared path semantics for the Linux guest workspace.
 *
 * Absolute paths stay absolute inside the guest rootfs. Relative paths are
 * interpreted from /root, matching the terminal working directory and @file
 * mention behavior used throughout the workbench.
 */
object GuestPaths {
    const val DEFAULT_RELATIVE_BASE = "/root"

    fun normalize(path: String, relativeBase: String = DEFAULT_RELATIVE_BASE): String {
        val clean = path.trim().replace('\\', '/')
        val raw = when {
            clean.isBlank() -> relativeBase
            clean.startsWith("/") -> clean
            else -> join(normalizeAbsolute(relativeBase), clean)
        }
        return normalizeAbsolute(raw)
    }

    fun same(a: String, b: String, relativeBase: String = DEFAULT_RELATIVE_BASE): Boolean =
        a.isNotBlank() && b.isNotBlank() && normalize(a, relativeBase) == normalize(b, relativeBase)

    fun reference(path: String): String = "@${normalize(path)} "

    fun resolve(rootfs: File, guestPath: String): File {
        val root = rootfs.canonicalFile
        val rel = normalize(guestPath).removePrefix("/")
        val target = File(root, rel).canonicalFile
        require(isInside(root, target)) { "路径越界: $guestPath" }
        return target
    }

    fun fromRootFile(rootfs: File, file: File): String {
        val root = rootfs.canonicalFile
        val target = file.canonicalFile
        require(isInside(root, target)) { "路径越界: ${file.path}" }
        if (target.path == root.path) return "/"
        return "/" + target.relativeTo(root).path.replace(File.separatorChar, '/').removePrefix("/")
    }

    private fun normalizeAbsolute(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun join(base: String, child: String): String =
        base.trimEnd('/') + "/" + child.trimStart('/')

    private fun isInside(root: File, target: File): Boolean =
        target.path == root.path || target.path.startsWith(root.path + File.separator)
}
