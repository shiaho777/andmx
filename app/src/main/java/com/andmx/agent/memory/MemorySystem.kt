package com.andmx.agent.memory

import android.util.Log
import com.andmx.exec.files.GuestFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Memory system — mirrors Codex's two-phase memory architecture.
 *
 * Phase 1 (Extraction): After each conversation turn, heuristically extract
 * salient facts from the transcript into raw memories. The extraction uses
 * pattern matching to identify:
 * - User preferences (explicit "I prefer", "always use", "don't" statements)
 * - Failure shields (error → fix patterns in tool outputs)
 * - Repo maps (file paths, entry points, config locations mentioned)
 * - Tool quirks (workarounds, alternative commands)
 *
 * Phase 2 (Consolidation): The agent can be asked to consolidate raw memories
 * into a structured MEMORY.md. This is typically done via a /memories command
 * or automatically when raw memory count exceeds a threshold.
 *
 * Safety rules (from Codex):
 * - Evidence-based only: no invented facts
 * - Redact secrets: replace tokens/keys/passwords with [REDACTED_SECRET]
 * - No-op is allowed and preferred when nothing worth saving
 */
class MemorySystem(
    private val fs: GuestFs,
    private val config: MemoryConfig = MemoryConfig.DEFAULT,
) {
    companion object {
        private const val TAG = "MemorySystem"
        private const val MEMORY_DIR = "/root/.andmx/memory"
        private const val RAW_MEMORIES = "$MEMORY_DIR/raw_memories.md"
        private const val MEMORY_MD = "$MEMORY_DIR/MEMORY.md"
        private const val MEMORY_SUMMARY = "$MEMORY_DIR/memory_summary.md"
        private const val SKILLS_DIR = "$MEMORY_DIR/skills"
        private const val MAX_RAW_MEMORIES = 200
    }

    private val consolidateThreshold get() = config.maxRawMemoriesForConsolidation

    data class RawMemory(
        val content: String,
        val category: MemoryCategory,
        val sessionId: String,
        val timestamp: String,
    ) {
        /** Serialize to markdown section for raw_memories.md */
        fun toMarkdown(): String = buildString {
            append("\n## [${category.name}] $timestamp (session: $sessionId)\n")
            append(content.trim())
            append("\n")
        }
    }

    enum class MemoryCategory(val label: String, val description: String) {
        USER_PREFERENCE("用户偏好", "稳定的用户操作偏好、反复出现的喜好和纠正"),
        DECISION_TRIGGER("决策触发", "能避免无效探索的决策触发条件"),
        FAILURE_SHIELD("故障防护", "症状 → 原因 → 修复 + 验证 + 停止规则"),
        REPO_MAP("仓库地图", "关键目录、入口点、配置文件位置"),
        TOOL_QUIRK("工具怪癖", "工具使用的怪癖和可靠快捷方式"),
        REPRODUCTION_PLAN("复现方案", "经过验证的复现步骤"),
    }

    /** A single parsed raw memory entry. */
    data class ParsedMemory(
        val category: MemoryCategory,
        val timestamp: String,
        val sessionId: String,
        val content: String,
    )

    data class MemorySnapshot(
        val hasMemory: Boolean,
        val memoryContent: String,
        val summary: String,
        val rawCount: Int,
        val rawMemories: List<ParsedMemory>,
        val categoryCounts: Map<MemoryCategory, Int>,
        val needsConsolidation: Boolean,
    )

    private val _state = MutableStateFlow(
        MemorySnapshot(false, "", "", 0, emptyList(), emptyMap(), false)
    )
    val state: StateFlow<MemorySnapshot> = _state

    // ── Phase 1: Extraction ─────────────────────────────────

    /**
     * Extract salient memories from a completed conversation turn.
     * Uses heuristic pattern matching — not LLM-based, so it's fast and free.
     *
     * @param userMessage The user's original message
     * @param assistantMessage The assistant's final response
     * @param toolOutputs List of (toolName, output) pairs from this turn
     * @param sessionId Current session identifier
     */
    suspend fun extractFromTurn(
        userMessage: String,
        assistantMessage: String,
        toolOutputs: List<Pair<String, String>>,
        sessionId: String,
    ) {
        val timestamp = java.time.Instant.now().toString()
        val extracted = mutableListOf<RawMemory>()

        // 1. Detect user preferences
        extractPreferences(userMessage, sessionId, timestamp, extracted)

        // 2. Detect failure shields (error → fix patterns)
        extractFailureShields(toolOutputs, sessionId, timestamp, extracted)

        // 3. Detect repo map entries (file paths, configs)
        extractRepoMap(assistantMessage, toolOutputs, sessionId, timestamp, extracted)

        // 4. Detect tool quirks (alternative commands, workarounds)
        extractToolQuirks(toolOutputs, sessionId, timestamp, extracted)

        // Deduplicate against existing raw memories
        if (extracted.isNotEmpty()) {
            val existing = loadRawMemories()
            val existingContents = existing.map { it.content }.toSet()
            for (mem in extracted) {
                if (mem.content !in existingContents) {
                    appendRaw(mem)
                }
            }
        }
    }

    private fun extractPreferences(
        userMessage: String,
        sessionId: String,
        timestamp: String,
        out: MutableList<RawMemory>,
    ) {
        val patterns = listOf(
            Regex("(?i)(?:我|总是|永远|不要|别|prefer|always|never|don\\'t use)\\s+(.{10,80})", RegexOption.IGNORE_CASE),
            Regex("(?i)(?:用|使用)\\s+(\\S+)\\s*(?:不要|别|instead|rather)", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(userMessage)) {
                val text = match.groupValues[1].trim()
                if (text.length in 5..80) {
                    out.add(RawMemory(
                        content = "用户偏好: $text",
                        category = MemoryCategory.USER_PREFERENCE,
                        sessionId = sessionId,
                        timestamp = timestamp,
                    ))
                }
            }
        }
    }

    private fun extractFailureShields(
        toolOutputs: List<Pair<String, String>>,
        sessionId: String,
        timestamp: String,
        out: MutableList<RawMemory>,
    ) {
        for ((toolName, output) in toolOutputs) {
            // Look for error patterns followed by successful retries
            val errorMatch = Regex("(?i)(error|failed|not found|cannot|unable to)[:\\s]+(.{10,120})", RegexOption.IGNORE_CASE)
                .find(output)
            if (errorMatch != null && toolName == "run_shell") {
                val errorText = errorMatch.groupValues[2].trim()
                out.add(RawMemory(
                    content = "故障: $errorText (来自 $toolName)",
                    category = MemoryCategory.FAILURE_SHIELD,
                    sessionId = sessionId,
                    timestamp = timestamp,
                ))
            }
        }
    }

    private fun extractRepoMap(
        assistantMessage: String,
        toolOutputs: List<Pair<String, String>>,
        sessionId: String,
        timestamp: String,
        out: MutableList<RawMemory>,
    ) {
        // Extract file paths mentioned in tool outputs
        val pathPattern = Regex("(?:^|\\s)(/root/\\S+\\.(?:kt|py|js|ts|go|rs|java|c|cpp|md|toml|yaml|yml|json|sh))")
        val paths = mutableSetOf<String>()
        for ((_, output) in toolOutputs) {
            pathPattern.findAll(output).forEach { paths.add(it.groupValues[1]) }
        }
        if (paths.isNotEmpty()) {
            // Only record if there are "important" looking paths (entry points, configs)
            val important = paths.filter { p ->
                p.contains("main") || p.contains("index") || p.contains("config") ||
                    p.contains("Cargo") || p.contains("package.json") || p.contains("build.gradle")
            }
            if (important.isNotEmpty()) {
                out.add(RawMemory(
                    content = "仓库入口: ${important.joinToString(", ")}",
                    category = MemoryCategory.REPO_MAP,
                    sessionId = sessionId,
                    timestamp = timestamp,
                ))
            }
        }
    }

    private fun extractToolQuirks(
        toolOutputs: List<Pair<String, String>>,
        sessionId: String,
        timestamp: String,
        out: MutableList<RawMemory>,
    ) {
        for ((toolName, output) in toolOutputs) {
            // Look for "command not found" → alternative used
            if (output.contains("command not found") || output.contains("not found")) {
                val altMatch = Regex("(?i)(?:use|try|install)\\s+(\\S+)", RegexOption.IGNORE_CASE).find(output)
                if (altMatch != null) {
                    out.add(RawMemory(
                        content = "工具怪癖: ${toolName} 中 ${altMatch.groupValues[1]} 可作为替代",
                        category = MemoryCategory.TOOL_QUIRK,
                        sessionId = sessionId,
                        timestamp = timestamp,
                    ))
                }
            }
        }
    }

    // ── Phase 2: Consolidation ──────────────────────────────

    /**
     * Consolidate raw memories into MEMORY.md (Phase 2).
     * Groups by category, deduplicates, and generates a summary.
     */
    suspend fun consolidate() {
        val raws = loadRawMemories()
        if (raws.isEmpty()) return

        val byCategory = raws.groupBy { it.category }

        val memoryMd = buildString {
            appendLine("# AndMX Memory")
            appendLine()
            appendLine("> 自动生成 — 请勿手动编辑")
            appendLine()
            for (cat in MemoryCategory.entries) {
                val items = byCategory[cat] ?: continue
                if (items.isEmpty()) continue
                appendLine("## ${cat.label}")
                appendLine()
                appendLine("> ${cat.description}")
                appendLine()
                // Deduplicate by content, keep latest
                val seen = mutableSetOf<String>()
                for (item in items.sortedByDescending { it.timestamp }) {
                    val key = item.content.take(60)
                    if (key in seen) continue
                    seen.add(key)
                    appendLine("- ${item.content}")
                    appendLine("  _${item.timestamp}_")
                }
                appendLine()
            }
        }

        ensureDir()
        fs.writeText(MEMORY_MD, memoryMd)

        val summary = generateSummary(memoryMd, raws)
        fs.writeText(MEMORY_SUMMARY, summary)

        load()
    }

    // ── State Management ────────────────────────────────────

    /** Load memory snapshot for context injection and UI. */
    suspend fun load(): MemorySnapshot {
        val memory = if (fs.exists(MEMORY_MD)) runCatching { fs.readText(MEMORY_MD) }.getOrDefault("") else ""
        val summary = if (fs.exists(MEMORY_SUMMARY)) runCatching { fs.readText(MEMORY_SUMMARY) }.getOrDefault("") else ""
        val raws = loadRawMemories()
        val categoryCounts = raws.groupBy { it.category }.mapValues { it.value.size }

        val snapshot = MemorySnapshot(
            hasMemory = memory.isNotBlank() || raws.isNotEmpty(),
            memoryContent = memory,
            summary = summary,
            rawCount = raws.size,
            rawMemories = raws,
            categoryCounts = categoryCounts,
            needsConsolidation = raws.size >= consolidateThreshold && memory.isBlank(),
        )
        _state.value = snapshot
        return snapshot
    }

    /** Parse raw_memories.md into structured entries. */
    suspend fun loadRawMemories(): List<ParsedMemory> {
        if (!fs.exists(RAW_MEMORIES)) return emptyList()
        val content = runCatching { fs.readText(RAW_MEMORIES) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<ParsedMemory>()

        // Parse "## [CATEGORY] timestamp (session: id)" headers
        val headerPattern = Regex("^## \\[(\\w+)\\] (.+?) \\(session: (.+?)\\)$", RegexOption.MULTILINE)
        val matches = headerPattern.findAll(content).toList()
        for ((i, match) in matches.withIndex()) {
            val catStr = match.groupValues[1]
            val timestamp = match.groupValues[2]
            val sessionId = match.groupValues[3]
            val category = runCatching { MemoryCategory.valueOf(catStr) }.getOrNull() ?: continue
            val start = match.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else content.length
            val body = content.substring(start, end).trim()
            if (body.isNotBlank()) {
                result.add(ParsedMemory(category, timestamp, sessionId, body))
            }
        }
        return result
    }

    /** Append a raw memory (Phase 1). */
    suspend fun appendRaw(memory: RawMemory) {
        ensureDir()
        val redacted = redactSecrets(memory.content)
        val entry = RawMemory(redacted, memory.category, memory.sessionId, memory.timestamp).toMarkdown()
        val existing = if (fs.exists(RAW_MEMORIES)) runCatching { fs.readText(RAW_MEMORIES) }.getOrDefault("# AndMX Raw Memories\n\n") else "# AndMX Raw Memories\n\n"
        fs.writeText(RAW_MEMORIES, existing + entry)

        // Auto-consolidate if too many raw memories
        val rawCount = loadRawMemories().size
        if (rawCount >= consolidateThreshold) {
            Log.i(TAG, "Auto-consolidating $rawCount raw memories")
            consolidate()
        } else {
            load()
        }
    }

    /** Delete a single raw memory by its content prefix. */
    suspend fun deleteRaw(contentPrefix: String) {
        val raws = loadRawMemories()
        val filtered = raws.filter { !it.content.startsWith(contentPrefix) }
        if (filtered.size == raws.size) return
        val rebuilt = buildString {
            appendLine("# AndMX Raw Memories")
            appendLine()
            for (mem in filtered) {
                appendLine("## [${mem.category.name}] ${mem.timestamp} (session: ${mem.sessionId})")
                appendLine(mem.content)
                appendLine()
            }
        }
        fs.writeText(RAW_MEMORIES, rebuilt)
        load()
    }

    /**
     * Generate the system prompt fragment for memory injection.
     * Only injects if memory exists — otherwise returns empty.
     */
    suspend fun promptFragment(): String {
        val snapshot = load()
        if (!snapshot.hasMemory) return ""

        return buildString {
            appendLine("# 记忆")
            appendLine("以下是来自之前会话的持久记忆，可能帮助你更好地完成当前任务:")
            appendLine()
            if (snapshot.summary.isNotBlank()) {
                appendLine("## 摘要")
                appendLine(snapshot.summary.take(2000))
                appendLine()
            }
            if (snapshot.memoryContent.isNotBlank()) {
                appendLine("## 详细记忆")
                appendLine(snapshot.memoryContent.take(8000))
            } else if (snapshot.rawMemories.isNotEmpty()) {
                // No consolidated memory yet — use raw memories directly
                appendLine("## 原始记忆")
                for (mem in snapshot.rawMemories.take(15)) {
                    appendLine("- [${mem.category.label}] ${mem.content.take(120)}")
                }
                appendLine()
            }
            appendLine()
            appendLine("## 记忆安全规则")
            appendLine("- 记忆是证据驱动的，不要编造事实")
            appendLine("- 不要在记忆中存储密钥/令牌/密码")
            appendLine("- 如果记忆与当前任务无关，忽略它")
            appendLine("- 如果记忆过时或与最新事实矛盾，以最新事实为准")
        }
    }

    /** Clear all memories. */
    suspend fun clear() {
        if (fs.exists(MEMORY_MD)) fs.deleteFile(MEMORY_MD)
        if (fs.exists(RAW_MEMORIES)) fs.deleteFile(RAW_MEMORIES)
        if (fs.exists(MEMORY_SUMMARY)) fs.deleteFile(MEMORY_SUMMARY)
        _state.value = MemorySnapshot(false, "", "", 0, emptyList(), emptyMap(), false)
    }

    // ── Internal ────────────────────────────────────────────

    private fun generateSummary(memoryMd: String, raws: List<ParsedMemory>): String {
        val byCategory = raws.groupBy { it.category }
        return buildString {
            appendLine("v1")
            appendLine("# Memory Summary")
            appendLine()
            appendLine("Generated: ${java.time.Instant.now()}")
            appendLine("Total raw memories: ${raws.size}")
            appendLine()
            appendLine("## Statistics")
            for (cat in MemoryCategory.entries) {
                val count = byCategory[cat]?.size ?: 0
                if (count > 0) appendLine("- ${cat.label}: $count 条")
            }
            appendLine()
            appendLine("## Top entries")
            val allContent = raws.sortedByDescending { it.timestamp }.take(10)
            for (mem in allContent) {
                appendLine("- [${mem.category.label}] ${mem.content.take(100)}")
            }
        }
    }

    private fun ensureDir() {
        if (!fs.exists(RAW_MEMORIES)) {
            fs.writeText(RAW_MEMORIES, "# AndMX Raw Memories\n\n")
        }
    }

    /** Redact secrets from text. */
    fun redactSecrets(text: String): String {
        var result = text
        // OpenAI API keys
        result = Regex("(sk-[a-zA-Z0-9]{20,})").replace(result, "[REDACTED_SECRET]")
        // Bearer tokens
        result = Regex("(Bearer\\s+[a-zA-Z0-9._-]{20,})", RegexOption.IGNORE_CASE).replace(result, "Bearer [REDACTED_SECRET]")
        // Password assignments
        result = Regex("(password|passwd|pwd|secret|token|api_key|apikey)\\s*[=:]\\s*\\S+", RegexOption.IGNORE_CASE)
            .replace(result) { "${it.groupValues[1]}=[REDACTED_SECRET]" }
        // AWS access keys
        result = Regex("(AKIA[0-9A-Z]{16})").replace(result, "[REDACTED_SECRET]")
        // AWS secret keys (40 char base64 after key id)
        result = Regex("([A-Za-z0-9/+=]{40})").replace(result) { m ->
            if (m.value.startsWith("AKIA")) m.value else "[REDACTED_SECRET]"
        }
        // Private keys
        result = Regex("-----BEGIN [A-Z ]+PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+PRIVATE KEY-----")
            .replace(result, "[REDACTED_SECRET]")
        return result
    }
}
