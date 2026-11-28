package com.andmx.exec.alpine

import android.content.Context
import com.andmx.exec.ProcessSpec
import com.andmx.exec.files.GuestFs
import com.andmx.exec.policy.EnvScrubber
import com.andmx.workspace.LocalWorktree
import com.andmx.workspace.ProjectManager
import org.andmx.runtime.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.WRITE
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class AlpineVm private constructor(private val context: Context) {
    private val runtime = LinuxRuntime(context)
    private var session: Session? = null
    private val idleExecutor = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "AndMX Alpine idle").apply { isDaemon = true }
    }
    private var idleCheck: ScheduledFuture<*>? = null

    val isAvailable: Boolean get() = runtime.isAvailable

    fun workspace(): File? {
        LocalWorktree.current()?.let { return File(it.project) }
        val project = ProjectManager(context)
        return project.hostPath.value?.takeUnless { project.isRemote }?.let(::File)
    }

    @Synchronized
    fun start(
        spec: ProcessSpec,
        workspace: File?,
        network: Boolean? = null,
        rows: Int = 0,
        cols: Int = 0,
        progress: (String) -> Unit = {},
        explicitEnvironment: Boolean = false,
    ): AlpineProcess {
        require(isAvailable) { "The built-in Alpine environment requires ARM64." }
        val project = workspace?.canonicalFile
        require(project == null || project.isDirectory) { "The selected workspace is unavailable." }

        var active = session
        if (active != null && (!active.console.isAlive || active.project != project ||
                network != null && active.network != network)) {
            check(!active.console.isAlive || active.processes.none { it.isAlive }) {
                "Close the active Alpine processes before switching workspace or network mode."
            }
            active.close()
            session = null
            active = null
        }
        if (active == null) {
            active = boot(project, network ?: true, progress)
            session = active
        }
        active.processes.removeAll { !it.isAlive }
        scheduleIdleCheck(active)
        val dir = File(active.control, UUID.randomUUID().toString().replace("-", ""))
        check(dir.mkdir()) { "Unable to create the Alpine command directory." }
        try {
            listOf("stdin", "stdout", "stderr", "stdin.closed", "cancel", "size").forEach { check(File(dir, it).createNewFile()) }
            File(dir, "request").writeText(JSONObject().apply {
                put("Argv", JSONArray(spec.argv))
                put("Directory", spec.workingDir ?: "/root")
                put("Env", JSONObject(if (explicitEnvironment) spec.env else EnvScrubber.scrub(spec.env)))
                put("Merge", spec.redirectErrorStream)
                put("Rows", rows)
                put("Cols", cols)
            }.toString())
            val process = AlpineProcess(dir, active.console)
            active.processes += process
            File(dir, "ready").createNewFile()
            return process
        } catch (failure: Exception) {
            removeProtocolDirectory(dir)
            throw failure
        }
    }

    private fun scheduleIdleCheck(active: Session) {
        idleCheck?.cancel(false)
        idleCheck = idleExecutor.schedule({
            synchronized(this) {
                if (session === active) {
                    if (active.processes.any { it.isAlive }) scheduleIdleCheck(active)
                    else {
                        session = null
                        runCatching { active.close() }
                    }
                }
            }
        }, 45, TimeUnit.SECONDS)
    }

    private fun boot(project: File?, network: Boolean, progress: (String) -> Unit): Session {
        val home = GuestFs(context).resolve("/root")
        check(home.isDirectory || home.mkdirs()) { "Unable to create the Alpine home directory." }
        val controlBase = File(context.cacheDir, "alpine-control").apply { mkdirs() }
        val control = Files.createTempDirectory(controlBase.toPath(), "vm-").toFile()
        try {
            runtime.prepare { progress(it) }
            progress("Starting Alpine")
            val console = runtime.openConsole(network, control, home, project)
            val running = Session(project, network, control, console)
            val log = StringBuilder()
            thread(name = "AndMX Alpine console", isDaemon = true) {
                runCatching {
                    console.input().bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(log) {
                                log.appendLine(line)
                                if (log.length > 4096) log.delete(0, log.length - 4096)
                            }
                        }
                    }
                }
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                while (!Files.isRegularFile(File(control, "ready").toPath(), NOFOLLOW_LINKS)) {
                    if (!console.isAlive || System.nanoTime() >= deadline) {
                        val details = synchronized(log) { log.toString().trim() }
                        throw IOException("Alpine did not start.${details.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}")
                    }
                    Thread.sleep(35)
                }
                return running
            } catch (failure: Exception) {
                running.close()
                throw failure
            }
        } catch (failure: Exception) {
            removeProtocolDirectory(control)
            throw failure
        }
    }

    private class Session(
        val project: File?,
        val network: Boolean,
        val control: File,
        val console: LinuxRuntime.Console,
        val processes: MutableSet<AlpineProcess> = mutableSetOf(),
    ) {
        fun close() {
            runCatching {
                if (console.isAlive && File(control, "ready").isFile) {
                    File(control, "shutdown").createNewFile()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (console.isAlive && System.nanoTime() < deadline) Thread.sleep(35)
                }
            }
            try {
                console.close()
            } finally {
                removeProtocolDirectory(control)
            }
        }
    }

    companion object {
        private var instance: AlpineVm? = null

        @Synchronized
        fun get(context: Context): AlpineVm = instance ?: AlpineVm(context.applicationContext).also { instance = it }
    }
}

internal class AlpineProcess(
    private val directory: File,
    private val console: LinuxRuntime.Console,
) : Process(), AutoCloseable {
    private val status = File(directory, "status").toPath()
    @Volatile private var closed = false
    @Volatile private var exitCode: Int? = null

    private val inputClosed = openOutput("stdin.closed")
    private val cancel = openOutput("cancel")
    private val size = Files.newByteChannel(File(directory, "size").toPath(), WRITE, NOFOLLOW_LINKS)
    private val stdin = object : FilterOutputStream(openOutput("stdin")) {
        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)

        @Synchronized
        override fun close() {
            super.close()
            inputClosed.write(1)
            inputClosed.flush()
        }
    }
    private val stdout = outputFrom("stdout")
    private val stderr = outputFrom("stderr")

    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun getOutputStream(): OutputStream = stdin

    @Synchronized
    private fun completedCode(): Int? {
        exitCode?.let { return it }
        if (closed || !console.isAlive) return 137.also { exitCode = it }
        if (Files.isRegularFile(status, NOFOLLOW_LINKS)) {
            val code = Files.newInputStream(status, NOFOLLOW_LINKS).use { input ->
                val bytes = ByteArray(12)
                val size = input.read(bytes)
                if (size > 0) String(bytes, 0, size).toIntOrNull() else null
            } ?: 125
            exitCode = code
        }
        return exitCode
    }

    override fun isAlive(): Boolean = completedCode() == null

    override fun exitValue(): Int = completedCode() ?: throw IllegalThreadStateException("Alpine command still running")

    override fun waitFor(): Int {
        while (isAlive) Thread.sleep(35)
        return exitValue()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val end = System.nanoTime() + unit.toNanos(timeout)
        while (isAlive && System.nanoTime() < end) Thread.sleep(35)
        return !isAlive
    }

    override fun destroy() {
        if (isAlive) runCatching { cancel.write(1); cancel.flush() }
    }

    override fun destroyForcibly(): Process = apply { destroy() }

    fun resize(rows: Int, cols: Int) {
        if (isAlive) runCatching {
            synchronized(size) {
                size.position(0)
                val bytes = java.nio.ByteBuffer.wrap(String.format(java.util.Locale.ROOT, "%05d %05d", rows, cols).toByteArray())
                while (bytes.hasRemaining()) size.write(bytes)
            }
        }
    }

    private fun openOutput(name: String): OutputStream =
        Files.newOutputStream(File(directory, name).toPath(), WRITE, NOFOLLOW_LINKS)

    private fun outputFrom(name: String): InputStream {
        val file = Files.newInputStream(File(directory, name).toPath(), NOFOLLOW_LINKS)
        return object : InputStream() {
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                while (!closed) {
                    val read = file.read(bytes, offset, length)
                    if (read >= 0) return read
                    if (!isAlive) return file.read(bytes, offset, length)
                    Thread.sleep(35)
                }
                return -1
            }

            override fun available(): Int = file.available()
            override fun close() = file.close()
        }
    }

    override fun close() {
        if (closed) return
        destroy()
        runCatching { waitFor(2, TimeUnit.SECONDS) }
        closed = true
        runCatching { stdin.close() }
        runCatching { stdout.close() }
        runCatching { stderr.close() }
        runCatching { inputClosed.close() }
        runCatching { cancel.close() }
        runCatching { size.close() }
        if (exitCode != null) removeProtocolDirectory(directory)
    }
}

private fun removeProtocolDirectory(directory: File) {
    runCatching {
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
