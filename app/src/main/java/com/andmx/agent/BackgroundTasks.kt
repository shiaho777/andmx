package com.andmx.agent

import android.content.Context
import com.andmx.exec.files.GuestFs
import com.andmx.exec.policy.EnvScrubber
import com.andmx.exec.proot.ProotRuntime
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * run_in_background shell 任务登记处（ZCode background task / local_bash 对齐）：
 * 任务输出实时写入访客可读的 output 文件，终态经 [events] 触发
 * queued_system_notification 回到主代理。状态词对齐上游：
 * 正常退出 → completed（含非零 exit），被停止 → killed，启动失败 → failed。
 */
class BackgroundTasks(
    context: Context,
    private val conversationId: Long = 0L,
    private val persistTask: (suspend (TaskRecord) -> Unit)? = null,
    private val loadTasks: (suspend (Long) -> List<TaskRecord>)? = null,
) {
    private val appContext = context.applicationContext
    private val runtime = ProotRuntime(appContext)
    private val guestFs = GuestFs(runtime)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, BgTask>()

    enum class TaskState { RUNNING, COMPLETED, FAILED, KILLED, INTERRUPTED }

    data class TaskRecord(
        val id: String,
        val conversationId: Long,
        val command: String,
        val cwd: String,
        val status: String,
        val outputPath: String,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val exitCode: Int? = null,
    )

    companion object {
        fun interruptRunning(records: List<TaskRecord>, now: Long): List<TaskRecord> =
            records.map { record ->
                if (record.status == "running") record.copy(status = "interrupted", finishedAt = now) else record
            }

        fun stateFromStatus(status: String): TaskState =
            TaskState.entries.firstOrNull { it.name.equals(status, ignoreCase = true) } ?: TaskState.INTERRUPTED
    }

    class BgTask(
        val id: String,
        val command: String,
        val cwd: String,
        val outputPath: String,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var state: TaskState = TaskState.RUNNING,
        @Volatile var exitCode: Int? = null,
        @Volatile internal var process: Process? = null,
        internal var job: Job? = null,
        val conversationId: Long = 0L,
        @Volatile var finishedAt: Long? = null,
    ) {
        val statusWord: String
            get() = when (state) {
                TaskState.RUNNING -> "running"
                TaskState.COMPLETED -> "completed"
                TaskState.FAILED -> "failed"
                TaskState.KILLED -> "killed"
                TaskState.INTERRUPTED -> "interrupted"
            }

        fun toRecord(): TaskRecord = TaskRecord(
            id = id,
            conversationId = conversationId,
            command = command,
            cwd = cwd,
            status = statusWord,
            outputPath = outputPath,
            startedAt = startedAt,
            finishedAt = finishedAt,
            exitCode = exitCode,
        )
    }

    /** UI 快照：不暴露 Process/Job，且每次刷新都会产生新对象，StateFlow 才会发值。 */
    data class Snapshot(
        val id: String,
        val command: String,
        val cwd: String,
        val outputPath: String,
        val startedAt: Long,
        val state: TaskState,
        val exitCode: Int?,
    )

    sealed interface Event {
        data class Terminated(val task: BgTask) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events

    private val _taskList = MutableStateFlow<List<Snapshot>>(emptyList())
    val taskList: StateFlow<List<Snapshot>> = _taskList.asStateFlow()

    fun list(): List<BgTask> = tasks.values.sortedByDescending { it.startedAt }

    fun listSnapshots(): List<Snapshot> = list().map {
        Snapshot(
            id = it.id,
            command = it.command,
            cwd = it.cwd,
            outputPath = it.outputPath,
            startedAt = it.startedAt,
            state = it.state,
            exitCode = it.exitCode,
        )
    }

    private fun refresh() {
        _taskList.value = listSnapshots()
    }

    fun get(id: String): BgTask? = tasks[id]

    private fun persist(task: BgTask) {
        val writer = persistTask ?: return
        scope.launch { runCatching { writer(task.toRecord()) } }
    }

    suspend fun restore(): List<TaskRecord> {
        val loader = loadTasks ?: return emptyList()
        val stored = runCatching { loader(conversationId) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val records = interruptRunning(stored.filter { !tasks.containsKey(it.id) }, now)
        val writer = persistTask
        records.forEach { record ->
            tasks[record.id] = BgTask(
                id = record.id,
                command = record.command,
                cwd = record.cwd,
                outputPath = record.outputPath,
                startedAt = record.startedAt,
                state = stateFromStatus(record.status),
                exitCode = record.exitCode,
                conversationId = record.conversationId,
                finishedAt = record.finishedAt,
            )
            if (writer != null && record.status == "interrupted") {
                runCatching { writer(record) }
            }
        }
        refresh()
        return records
    }

    fun startShell(command: String, cwd: String): BgTask {
        val id = "bash-${System.currentTimeMillis()}-${(1000..9999).random()}"
        val guestOut = "/root/.andmx/tasks/$id.output"
        val hostFile = runCatching { guestFs.resolve(guestOut) }
            .getOrElse { File(appContext.filesDir, "background_tasks/$id.output") }
        hostFile.parentFile?.mkdirs()
        val task = BgTask(id = id, command = command, cwd = cwd, outputPath = guestOut, conversationId = conversationId)
        tasks[id] = task
        refresh()
        persist(task)
        task.job = scope.launch { runTask(task, hostFile) }
        return task
    }

    private suspend fun runTask(task: BgTask, hostFile: File) {
        val rootfs = runtime.rootfsDir.takeIf { it.exists() }
        val sh = if (rootfs != null) "/bin/sh" else "/system/bin/sh"
        val quotedCwd = "'${task.cwd.replace("'", "'\"'\"'")}'"
        val cdCommand = when {
            task.cwd.isBlank() -> task.command
            else -> "cd $quotedCwd 2>/dev/null || cd ~; ${task.command}"
        }
        val process = runCatching {
            ProcessBuilder(runtime.prootArgv(listOf(sh, "-lc", cdCommand), rootfs = rootfs))
                .directory(File(appContext.filesDir.path))
                .apply {
                    environment().clear()
                    environment().putAll(EnvScrubber.scrub(runtime.env()))
                    redirectErrorStream(true)
                }
                .start()
        }.getOrElse {
            hostFile.appendText("执行失败: ${it.message}\n[exit=-1]\n")
            task.state = TaskState.FAILED
            task.finishedAt = System.currentTimeMillis()
            refresh()
            persist(task)
            _events.tryEmit(Event.Terminated(task))
            return
        }
        task.process = process
        try {
            hostFile.bufferedWriter().use { writer ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val n = process.inputStream.read(buffer)
                    if (n <= 0) break
                    writer.write(String(buffer, 0, n, Charsets.UTF_8).replace("\r", ""))
                    writer.flush()
                }
                val code = process.waitFor()
                task.exitCode = code
                writer.appendLine("[exit=$code]")
            }
            if (task.state == TaskState.RUNNING) task.state = TaskState.COMPLETED
        } catch (t: Throwable) {
            if (task.state == TaskState.RUNNING) task.state = TaskState.FAILED
        } finally {
            task.process = null
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            if (task.finishedAt == null) task.finishedAt = System.currentTimeMillis()
            refresh()
            persist(task)
        }
        _events.tryEmit(Event.Terminated(task))
    }

    fun stop(id: String): Boolean {
        val task = tasks[id] ?: return false
        if (task.state != TaskState.RUNNING) return false
        task.state = TaskState.KILLED
        task.finishedAt = System.currentTimeMillis()
        runCatching { task.process?.destroyForcibly() }
        task.job?.cancel()
        refresh()
        persist(task)
        return true
    }

    fun cancelAll() {
        tasks.values.forEach { stop(it.id) }
    }

    fun tail(id: String, maxChars: Int = 8_000): String? {
        val task = tasks[id] ?: return null
        return runCatching { guestFs.resolve(task.outputPath) }
            .getOrNull()
            ?.takeIf { it.exists() }
            ?.readText()
            ?.takeLast(maxChars)
    }

    /** 上游 local_bash 任务终态通知：扁平 <task-notification> 字段序。 */
    fun notificationBody(task: BgTask): String {
        val lines = mutableListOf("<task-notification>")
        lines += "<task-id>${escBg(task.id)}</task-id>"
        lines += "<output-file>${escBg(task.outputPath)}</output-file>"
        lines += "<status>${escBg(task.statusWord)}</status>"
        lines += "<summary>${escBg(task.command.take(120))} (exit=${task.exitCode ?: -1})</summary>"
        lines += "</task-notification>"
        return lines.joinToString("\n")
    }
}

private fun escBg(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
