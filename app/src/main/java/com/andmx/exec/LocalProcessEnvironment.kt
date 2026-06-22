package com.andmx.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The most basic execution environment: launches processes directly on the
 * Android host via [ProcessBuilder]. Used to run binaries shipped in the app's
 * nativeLibraryDir, and as the substrate that proot itself will be launched
 * through later.
 */
class LocalProcessEnvironment : ExecutionEnvironment {

    override val id: String = "local-process"
    override val displayName: String = "本地进程"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun execute(spec: ProcessSpec): ProcessResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val pb = ProcessBuilder(spec.argv)
            spec.workingDir?.let { pb.directory(File(it)) }
            if (spec.env.isNotEmpty()) pb.environment().putAll(spec.env)
            pb.redirectErrorStream(spec.redirectErrorStream)

            val process = pb.start()

            spec.stdin?.let { input ->
                process.outputStream.use { it.write(input.toByteArray()); it.flush() }
            }

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = if (spec.redirectErrorStream) "" else process.errorStream.bufferedReader().readText()
            val code = process.waitFor()

            ProcessResult(
                exitCode = code,
                stdout = stdout,
                stderr = stderr,
                durationMs = (System.nanoTime() - started) / 1_000_000,
            )
        } catch (t: Throwable) {
            ProcessResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = (System.nanoTime() - started) / 1_000_000,
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }
}
