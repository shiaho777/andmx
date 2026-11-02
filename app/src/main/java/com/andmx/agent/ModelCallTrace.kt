package com.andmx.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 模型调用轨迹（ZCode modelTrajectory.* 对齐）：逐次 LLM 调用的可回放记录。
 *
 * ZCode 把每次调用落盘 model-io 并在查看器展示来源、token、结束原因与
 * 输入/输出原文；AndMX 在会话进程内保存最近 [MAX_CALLS] 条（环形淘汰），
 * 并异步镜像到 `files/model-traces.json`，进程重启后仍能查看。
 */
object ModelCallTrace {

    enum class Source(val label: String) {
        MAIN("主会话"),
        SESSION_TITLE("标题生成"),
        COMPACT("上下文压缩"),
        SUBAGENT("子智能体"),
        GOAL_VERIFY("目标验证"),
        MEMORY("记忆抽取"),
        WEB_FETCH("网页摘要"),
        WORKFLOW("工作流"),
        UNKNOWN("未知来源"),
    }

    enum class Finish(val label: String) {
        STOP("正常结束"),
        TOOL_CALLS("工具调用"),
        LENGTH("达到长度限制"),
        CONTENT_FILTER("内容过滤"),
    }

    @Serializable
    data class Call(
        val seq: Long,
        val atMs: Long,
        val source: Source,
        val model: String,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int,
        val finish: Finish,
        val inputPreview: String,
        val outputPreview: String,
        val durationMs: Long,
    ) {
        val totalTokens: Int get() = inputTokens + outputTokens
    }

    const val MAX_CALLS = 50
    const val PREVIEW_CHARS = 2_000

    internal val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _calls = kotlinx.coroutines.flow.MutableStateFlow<List<Call>>(emptyList())
    val calls: kotlinx.coroutines.flow.StateFlow<List<Call>> = _calls

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    @Volatile private var traceFile: File? = null
    @Volatile private var stateVersion = 0L
    @Volatile private var persistedVersion = -1L
    @Volatile private var stateEpoch = 0L
    private var seqCounter = 0L

    fun install(context: Context) {
        if (traceFile != null) return
        synchronized(this) {
            if (traceFile != null) return
            val file = File(context.applicationContext.filesDir, TRACE_FILE)
            traceFile = file
            val loaded = runCatching {
                if (file.isFile) codec.decodeFromString<List<Call>>(file.readText()) else emptyList()
            }.getOrDefault(emptyList())
            if (loaded.isNotEmpty()) {
                _calls.value = loaded.takeLast(MAX_CALLS)
                seqCounter = loaded.maxOfOrNull { it.seq } ?: 0L
            }
            persistedVersion = stateVersion
        }
    }

    fun record(
        source: Source,
        model: String,
        inputTokens: Int,
        cachedInputTokens: Int,
        outputTokens: Int,
        finish: Finish,
        inputPreview: String,
        outputPreview: String,
        durationMs: Long,
        atMs: Long = System.currentTimeMillis(),
    ): Call = synchronized(this) {
        val call = Call(
            seq = ++seqCounter,
            atMs = atMs,
            source = source,
            model = model,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            finish = finish,
            inputPreview = inputPreview.take(PREVIEW_CHARS),
            outputPreview = outputPreview.take(PREVIEW_CHARS),
            durationMs = durationMs,
        )
        val next = (_calls.value + call).takeLast(MAX_CALLS)
        _calls.value = next
        val version = ++stateVersion
        val epoch = stateEpoch
        ioScope.launch { persist(next, version, epoch) }
        call
    }

    fun clear() {
        synchronized(this) {
            _calls.value = emptyList()
            val version = ++stateVersion
            val epoch = stateEpoch
            ioScope.launch { persist(emptyList(), version, epoch) }
        }
    }

    /** 汇总（查看器顶部条）：调用数 + 总 token。 */
    fun summary(): Pair<Int, Int> {
        val list = _calls.value
        return list.size to list.sumOf { it.totalTokens }
    }

    private suspend fun persist(calls: List<Call>, version: Long, epoch: Long) {
        val file = traceFile ?: return
        writeMutex.withLock {
            if (epoch != stateEpoch) return
            if (version < persistedVersion) return
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(codec.encodeToString(calls))
            }.onSuccess { persistedVersion = version }
        }
    }

    @androidx.annotation.VisibleForTesting
    suspend fun flush() {
        val snapshot = _calls.value
        persist(snapshot, stateVersion, stateEpoch)
    }

    @androidx.annotation.VisibleForTesting
    fun resetInMemory() {
        synchronized(this) {
            traceFile = null
            _calls.value = emptyList()
            seqCounter = 0L
            stateVersion = 0L
            persistedVersion = -1L
            stateEpoch += 1
        }
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTests(context: Context) {
        resetInMemory()
        File(context.applicationContext.filesDir, TRACE_FILE).delete()
    }

    private const val TRACE_FILE = "model-traces.json"
}
