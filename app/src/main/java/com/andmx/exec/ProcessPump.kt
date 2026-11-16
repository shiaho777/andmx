package com.andmx.exec

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal data class PumpedProcess(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

internal fun runProcessPumped(
    argv: List<String>,
    stdin: String? = null,
    redirectErrorStream: Boolean = false,
    timeoutMs: Long = 120_000L,
    env: Map<String, String> = emptyMap(),
): PumpedProcess {
    val pb = ProcessBuilder(argv)
    pb.redirectErrorStream(redirectErrorStream)
    if (env.isNotEmpty()) pb.environment().putAll(env)
    val proc = pb.start()
    val outBuf = ByteArrayOutputStream()
    val errBuf = ByteArrayOutputStream()
    val outPump = Thread { pumpStream(proc.inputStream, outBuf) }
    val errPump = if (redirectErrorStream) null else Thread { pumpStream(proc.errorStream, errBuf) }
    outPump.isDaemon = true
    outPump.start()
    errPump?.let {
        it.isDaemon = true
        it.start()
    }
    if (stdin != null) {
        runCatching {
            proc.outputStream.use {
                it.write(stdin.toByteArray(Charsets.UTF_8))
                it.flush()
            }
        }
    } else {
        runCatching { proc.outputStream.close() }
    }
    val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) {
        proc.destroyForcibly()
        proc.waitFor()
    }
    outPump.join(2_000)
    errPump?.join(2_000)
    val stdout = synchronized(outBuf) { String(outBuf.toByteArray(), Charsets.UTF_8) }
    val stderr = synchronized(errBuf) { String(errBuf.toByteArray(), Charsets.UTF_8) }
    return PumpedProcess(
        exitCode = if (finished) proc.exitValue() else 124,
        stdout = stdout,
        stderr = stderr,
        timedOut = !finished,
    )
}

private fun pumpStream(input: InputStream, sink: ByteArrayOutputStream) {
    val buffer = ByteArray(8_192)
    try {
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            synchronized(sink) { sink.write(buffer, 0, n) }
        }
    } catch (_: Throwable) {
    }
}
