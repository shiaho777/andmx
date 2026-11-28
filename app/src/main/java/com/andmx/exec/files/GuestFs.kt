package com.andmx.exec.files

import android.content.Context
import com.andmx.workspace.GuestPaths
import java.io.File

class GuestFs(context: Context) {
    private val root = File(context.filesDir, "rootfs")

    val rootfsRoot: File get() = root

    /** Resolve a guest path (absolute or relative to /root) to a host File. */
    fun resolve(guestPath: String): File = GuestPaths.resolve(root, guestPath)

    fun readText(guestPath: String, limit: Int = 256 * 1024): String {
        val f = resolve(guestPath)
        require(f.isFile) { "不是文件或不存在: $guestPath" }
        require(f.length() <= limit) { "文件过大 (${f.length()} 字节,上限 $limit)" }
        return f.readText()
    }

    fun exists(guestPath: String): Boolean = runCatching { resolve(guestPath).exists() }.getOrDefault(false)

    /** List a directory's entries (dirs suffixed with '/'). */
    fun list(guestPath: String): List<String> {
        val dir = resolve(guestPath)
        require(dir.isDirectory) { "不是目录: $guestPath" }
        return dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { it.name + if (it.isDirectory) "/" else "" }
            .orEmpty()
    }

    fun writeText(guestPath: String, content: String) {
        val f = resolve(guestPath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun deleteFile(guestPath: String): Boolean {
        val f = resolve(guestPath)
        require(!f.exists() || f.isFile) { "不是文件: $guestPath" }
        return !f.exists() || f.delete()
    }
}
