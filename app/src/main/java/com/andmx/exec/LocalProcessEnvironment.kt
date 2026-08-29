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
            val childEnv = pb.environment()
            // ProcessBuilder seeds the child with a copy of this process's
            // environment. Drop anything credential-shaped before the model's
            // command can read `env`, echo it, or spill it to a file.
            com.andmx.exec.policy.EnvScrubber.sensitiveKeys(childEnv.keys).forEach { childEnv.remove(it) }
            if (spec.env.isNotEmpty()) {
                childEnv.putAll(com.andmx.exec.policy.EnvScrubber.scrub(spec.env))
            }
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
