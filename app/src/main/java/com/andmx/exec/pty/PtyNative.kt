package com.andmx.exec.pty

/** JNI bindings to the native PTY bridge (libandmxpty.so). */
object PtyNative {
    init { System.loadLibrary("andmxpty") }

    /**
     * Fork a child attached to a new PTY and execve [cmd] with [argv]/[envp].
     * @return the PTY master fd, or -1 on failure. [pidOut][0] receives the pid.
     */
    external fun createSubprocess(
        cmd: String,
        cwd: String?,
        argv: Array<String>,
        envp: Array<String>,
        pidOut: IntArray,
        rows: Int,
        cols: Int,
    ): Int

    external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int)
    external fun waitFor(pid: Int): Int
    external fun closeFd(fd: Int)
}
