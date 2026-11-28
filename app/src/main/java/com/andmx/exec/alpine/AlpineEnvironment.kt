package com.andmx.exec.alpine

import android.content.Context
import com.andmx.exec.ExecutionEnvironment
import com.andmx.exec.ProcessResult
import com.andmx.exec.ProcessSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream

internal class AlpineEnvironment(
    context: Context,
    private val timeoutMs: Long = 120_000,
    private val workspace: (() -> File?)? = null,
    private val requireActiveSession: Boolean = false,
) : ExecutionEnvironment {
    private val vm = AlpineVm.get(context)

    override val id = "local-alpine-vm"
    override val displayName = "Alpine Linux"

    override suspend fun isAvailable(): Boolean = vm.isAvailable

    override suspend fun execute(spec: ProcessSpec): ProcessResult = execute(spec, {}, {})

    suspend fun execute(
        spec: ProcessSpec,
        onOutput: (String) -> Unit,
        progress: (String) -> Unit = {},
    ): ProcessResult = coroutineScope {
        val started = System.nanoTime()
        var process: AlpineProcess? = null
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        fun result(code: Int, error: String? = null) =
            ProcessResult(code, stdout.toString(), stderr.toString(), (System.nanoTime() - started) / 1_000_000, error)
        try {
            val command = runInterruptible(Dispatchers.IO) {
                vm.start(
                    spec,
                    if (workspace == null) vm.workspace() else workspace.invoke(),
                    progress = progress,
                    requireActiveSession = requireActiveSession,
                ).also { process = it }
            }
            withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val out = async { capture(command.inputStream, stdout, onOutput) }
                    val err = async { capture(command.errorStream, stderr) {} }
                    runInterruptible(Dispatchers.IO) {
                        command.outputStream.use { stream -> spec.stdin?.let { stream.write(it.toByteArray()) } }
                    }
                    out.await()
                    err.await()
                    result(command.exitValue())
                }
            } ?: result(124, "Alpine command timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            result(-1, failure.message ?: "Alpine command failed")
        } finally {
            process?.close()
        }
    }

    private suspend fun capture(input: InputStream, text: StringBuilder, onOutput: (String) -> Unit) {
        runInterruptible(Dispatchers.IO) {
            input.reader().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val chunk = String(buffer, 0, count)
                    val remaining = (16 * 1024 * 1024 - text.length).coerceAtLeast(0)
                    text.append(chunk.take(remaining))
                    onOutput(chunk)
                }
            }
        }
    }
}
