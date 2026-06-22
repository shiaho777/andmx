package com.andmx.exec.pty

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A running child process connected through a PTY. Reading [input] yields the
 * process's terminal output; writing [output] sends keystrokes to it.
 */
class PtyProcess private constructor(
    val pid: Int,
    private val masterFd: Int,
    private val pfd: ParcelFileDescriptor,
) {
    val input: FileInputStream = FileInputStream(pfd.fileDescriptor)
    val output: FileOutputStream = FileOutputStream(pfd.fileDescriptor)

    fun resize(rows: Int, cols: Int) = PtyNative.setPtyWindowSize(masterFd, rows, cols)

    fun waitFor(): Int = PtyNative.waitFor(pid)

    fun destroy() {
        runCatching { android.os.Process.killProcess(pid) }
        runCatching { pfd.close() } // closes the adopted fd
    }

    companion object {
        fun start(
            command: String,
            argv: Array<String>,
            envp: Array<String>,
            cwd: String? = null,
            rows: Int = 24,
            cols: Int = 80,
        ): PtyProcess {
            val pidOut = IntArray(1)
            val fd = PtyNative.createSubprocess(command, cwd, argv, envp, pidOut, rows, cols)
            check(fd >= 0) { "createSubprocess failed for $command" }
            val pfd = ParcelFileDescriptor.adoptFd(fd)
            return PtyProcess(pidOut[0], fd, pfd)
        }
    }
}
