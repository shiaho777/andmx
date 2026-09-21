package com.andmx.agent.memory

import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.ModelCallTrace
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.agent.TracedLlm
import com.andmx.agent.TurnContext
import com.andmx.exec.files.GuestFs
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * ZCode `memory-agent-loop` 对齐：回合结束后起一个受限子代理做记忆抽取。
 * 与上游的差异：上游在请求面挂全工具目录、在执行边界按策略拒绝；这里只下发
 * 安全工具（workspace 只读三件套 + memory_* 受限写），没有的工具天然调不到。
 * 启发式 extractFromTurn 仍保留为原始证据流，本代理负责直接维护 MEMORY.md。
 */
class MemoryAgentRunner(
    private val fs: GuestFs,
    private val workspaceReadTools: () -> List<Tool>,
    private val memoryRoot: String = MemorySystem.MEMORY_DIR,
    private val maxSteps: Int = 8,
) {
    enum class Outcome { SUCCESS, NO_OP, ERROR }

    private val mutex = Mutex()
    @Volatile private var pending: Snapshot? = null

    private data class Snapshot(
        val client: LlmApi,
        val provider: ProviderDefinition,
        val model: String,
        val settings: ProviderSettings,
        val transcript: List<String>,
    )

    /** 上游 scheduler 语义：已在跑时合并 pending——最新快照赢，旧的丢弃。 */
    suspend fun extract(
        client: LlmApi,
        provider: ProviderDefinition,
        model: String,
        settings: ProviderSettings,
        transcript: List<String>,
    ): Outcome {
        pending = Snapshot(client, provider, model, settings, transcript)
        if (!mutex.tryLock()) return Outcome.NO_OP
        try {
            var outcome = Outcome.NO_OP
            while (true) {
                val snap = pending ?: break
                pending = null
                outcome = runOnce(snap)
            }
            return outcome
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runOnce(snap: Snapshot): Outcome {
        val writes = AtomicInteger(0)
        val tools = workspaceReadTools() + memoryTools(writes)
        val manifest = manifest()
        val prompt = buildPrompt(manifest, snap.transcript.size)
        val engine = AgentEngine(
            tools = tools,
            client = TracedLlm(snap.client, ModelCallTrace.Source.MEMORY),
            systemPrompt = prompt,
            maxSteps = maxSteps,
        )
        val userInput = snap.transcript.joinToString("\n")
        var finalText = ""
        var failed = false
        try {
            engine.runTurn(snap.settings, TurnContext(snap.provider, snap.model), userInput)
                .collect { ev ->
                    when (ev) {
                        is AgentEvent.Assistant -> finalText = ev.text
                        is AgentEvent.Failed -> failed = true
                        else -> {}
                    }
                }
        } catch (_: Throwable) {
            return Outcome.ERROR
        }
        if (failed) return Outcome.ERROR
        if (writes.get() == 0 || finalText.contains(NOTHING_TO_SAVE)) return Outcome.NO_OP
        return Outcome.SUCCESS
    }

    /** 现有记忆文件清单（文件名 + 首个标题行），对应上游 formatMemoryManifest。 */
    private suspend fun manifest(): List<Pair<String, String>> {
        val names = runCatching { fs.list(memoryRoot) }.getOrDefault(emptyList())
            .filter { it.endsWith(".md") }
            .sorted()
        return names.map { name ->
            val first = runCatching { fs.readText("$memoryRoot/$name", 4_000) }
                .getOrDefault("")
                .lineSequence()
                .firstOrNull { it.startsWith("#") }
                ?.removePrefix("#")?.trim().orEmpty()
            name to first
        }
    }

    private fun buildPrompt(manifest: List<Pair<String, String>>, messageCount: Int): String {
        val existing = if (manifest.isNotEmpty()) {
            buildString {
                append("\n\n## Existing memory files\n\n")
                manifest.forEach { (name, desc) ->
                    append("- $name")
                    if (desc.isNotBlank()) append(" — $desc")
                    append('\n')
                }
                append("\nCheck this list before writing — update an existing file rather than creating a duplicate.")
            }
        } else ""
        return """
You are the memory extraction subagent. Analyze the conversation transcript the user sends and use it to update the persistent memory system.

Available tools: Read, Grep, Glob over the workspace for evidence, and memory_write / memory_read / memory_list / memory_delete confined to the memory directory ($memoryRoot). All other tools are unavailable by design.

You have a limited turn budget. The efficient strategy is: turn 1 — issue all memory_read calls in parallel for every file you might update; turn 2 — issue all memory_write calls in parallel. Do not interleave reads and writes across multiple turns.

You MUST only use content from the transcript to update persistent memories. Do not waste turns investigating further — no grepping source files, no reading code to confirm a pattern exists, no git commands.$existing

Write entries into MEMORY.md (the consolidated file injected into future sessions) and, when a topic is too large for a bullet, a dedicated .md file referenced from MEMORY.md.

Memory types (use these section headings in MEMORY.md):
- 用户偏好 (USER_PREFERENCE): stable user preferences, recurring likes, corrections
- 决策触发 (DECISION_TRIGGER): conditions that prevent useless exploration
- 故障防护 (FAILURE_SHIELD): symptom → cause → fix + verification + stop rule
- 仓库地图 (REPO_MAP): key directories, entry points, config locations
- 工具怪癖 (TOOL_QUIRK): quirks and reliable shortcuts of tools
- 复现方案 (REPRODUCTION_PLAN): verified reproduction steps

What NOT to save: secrets/tokens/passwords, anything derivable from the repo itself (code structure, past fixes, git history, AGENTS.md), anything only relevant to the current conversation.

If nothing is worth saving, output only '$NOTHING_TO_SAVE' Do not explain why.
If the user explicitly asks to remember something, save it immediately. If they ask to forget something, find and remove the relevant entry.
""".trim()
    }

    private fun memoryTools(writes: AtomicInteger): List<Tool> = listOf(
        MemoryWriteTool(fs, memoryRoot, writes),
        MemoryReadTool(fs, memoryRoot),
        MemoryListTool(fs, memoryRoot),
        MemoryDeleteTool(fs, memoryRoot, writes),
    )

    private abstract class MemoryDirTool(
        protected val fs: GuestFs,
        protected val root: String,
    ) : Tool {
        protected fun resolve(raw: String): String? {
            val p = raw.trim().ifBlank { return null }
            val abs = if (p.startsWith("/")) p else "$root/$p"
            // canonicalPath folds `..` segments — invariantSeparatorsPath alone
            // would let "$root/../escape" pass the prefix check.
            val norm = runCatching { java.io.File(abs).canonicalPath }.getOrNull()
                ?: return null
            return norm.takeIf { it == root || it.startsWith("$root/") }
        }
    }

    private class MemoryWriteTool(
        fs: GuestFs,
        root: String,
        private val writes: AtomicInteger,
    ) : MemoryDirTool(fs, root) {
        override val name = "memory_write"
        override val description =
            "Write a markdown file inside the memory directory. Path may be a bare filename or an absolute path under the memory directory."
        override val risk = ToolRisk.WRITE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
            }
            putJsonArray("required") { add("path"); add("content") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val path = resolve(args["path"]?.jsonPrimitive?.content ?: "")
                ?: return ToolResult("path must stay inside the memory directory", isError = true)
            val content = MemorySystem.redactSecrets(
                args["content"]?.jsonPrimitive?.content ?: "",
            )
            return runCatching {
                fs.writeText(path, content)
                writes.incrementAndGet()
                ToolResult("wrote $path")
            }.getOrElse { ToolResult("write failed: ${it.message}", isError = true) }
        }
    }

    private class MemoryReadTool(fs: GuestFs, root: String) : MemoryDirTool(fs, root) {
        override val name = "memory_read"
        override val description = "Read a file inside the memory directory."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
            }
            putJsonArray("required") { add("path") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val path = resolve(args["path"]?.jsonPrimitive?.content ?: "")
                ?: return ToolResult("path must stay inside the memory directory", isError = true)
            return runCatching { ToolResult(fs.readText(path)) }
                .getOrElse { ToolResult("read failed: ${it.message}", isError = true) }
        }
    }

    private class MemoryListTool(fs: GuestFs, root: String) : MemoryDirTool(fs, root) {
        override val name = "memory_list"
        override val description = "List files inside the memory directory."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject): ToolResult =
            runCatching { ToolResult(fs.list(root).sorted().joinToString("\n").ifBlank { "(empty)" }) }
                .getOrElse { ToolResult("list failed: ${it.message}", isError = true) }
    }

    private class MemoryDeleteTool(
        fs: GuestFs,
        root: String,
        private val writes: AtomicInteger,
    ) : MemoryDirTool(fs, root) {
        override val name = "memory_delete"
        override val description = "Delete a file inside the memory directory."
        override val risk = ToolRisk.WRITE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
            }
            putJsonArray("required") { add("path") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val path = resolve(args["path"]?.jsonPrimitive?.content ?: "")
                ?: return ToolResult("path must stay inside the memory directory", isError = true)
            return runCatching {
                writes.incrementAndGet()
                ToolResult(if (fs.deleteFile(path)) "deleted $path" else "not found: $path")
            }.getOrElse { ToolResult("delete failed: ${it.message}", isError = true) }
        }
    }

    companion object {
        const val NOTHING_TO_SAVE = "Nothing to save."

        /** 上游 evaluateMemoryExtraction 的两个 skip 闸：无用户散文 / 主代理已直接写记忆。 */
        fun shouldSkip(userText: String, toolOutputs: List<Pair<String, String>>, memoryRoot: String): Boolean {
            val prose = userText.split(Regex("\\s+")).count { it.isNotBlank() }
            if (prose < 3) return true
            return toolOutputs.any { (name, out) ->
                name.lowercase() in setOf("write", "edit", "write_file", "edit_file") &&
                    out.contains(memoryRoot)
            }
        }
    }
}
