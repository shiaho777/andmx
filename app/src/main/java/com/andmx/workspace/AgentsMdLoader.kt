package com.andmx.workspace

import com.andmx.exec.files.GuestFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AGENTS.md project instruction loader — mirrors Codex's AGENTS.md system.
 *
 * Codex reads AGENTS.md files from the project root and any directories
 * from CWD up to the root. The contents are included with the developer
 * message and don't need to be re-read by the model.
 *
 * More deeply-nested AGENTS.md files take precedence in case of conflicts.
 * Direct system/developer/user instructions take precedence over AGENTS.md.
 *
 * This implementation:
 * 1. Scans /root (and optionally subdirectories) for AGENTS.md files
 * 2. Merges them with the deepest taking precedence
 * 3. Exposes the merged content as a prompt fragment for the system prompt
 */
class AgentsMdLoader(private val fs: GuestFs) {

    companion object {
        const val AGENTS_MD = "AGENTS.md"
        const val MAX_FILE_SIZE = 32_000  // 32KB max per file
        const val MAX_TOTAL_SIZE = 64_000 // 64KB total
    }

    data class AgentFile(
        val path: String,
        val content: String,
        val depth: Int,              // 0 = root, higher = deeper
    )

    private val _state = MutableStateFlow<List<AgentFile>>(emptyList())
    val state: StateFlow<List<AgentFile>> = _state

    /** Scan for AGENTS.md files in /root and common project subdirectories. */
    suspend fun load(): List<AgentFile> {
        val files = mutableListOf<AgentFile>()

        // Root AGENTS.md
        loadAgentFile("/root/$AGENTS_MD", 0)?.let { files.add(it) }

        // Scan immediate subdirectories (depth 1)
        if (fs.exists("/root")) {
            val entries = runCatching { fs.list("/root") }.getOrDefault(emptyList())
            for (entry in entries) {
                val dirPath = if (entry.startsWith("/")) entry else "/root/$entry"
                if (dirPath == "/root") continue
                loadAgentFile("$dirPath/$AGENTS_MD", 1)?.let { files.add(it) }
            }
        }

        _state.value = files
        return files
    }

    /** Generate the system prompt fragment from loaded AGENTS.md files. */
    suspend fun promptFragment(): String {
        val files = load()
        if (files.isEmpty()) return ""

        val totalContent = StringBuilder()
        for (file in files) {
            if (totalContent.length + file.content.length > MAX_TOTAL_SIZE) break
            totalContent.appendLine("## AGENTS.md (${file.path})")
            totalContent.appendLine(file.content.take(MAX_FILE_SIZE))
            totalContent.appendLine()
        }

        return buildString {
            appendLine("# 项目指令 (AGENTS.md)")
            appendLine("以下项目指令文件已包含在上下文中，无需重新读取。")
            appendLine("指令关于代码风格、结构、命名等仅适用于其作用域内的代码。")
            appendLine("更深层嵌套的 AGENTS.md 文件优先级更高。")
            appendLine("直接的系统/开发者/用户指令优先级高于 AGENTS.md。")
            appendLine()
            append(totalContent.toString())
        }
    }

    private suspend fun loadAgentFile(path: String, depth: Int): AgentFile? {
        return runCatching {
            if (!fs.exists(path)) return null
            val content = fs.readText(path).take(MAX_FILE_SIZE)
            if (content.isBlank()) return null
            AgentFile(path, content, depth)
        }.getOrNull()
    }
}
