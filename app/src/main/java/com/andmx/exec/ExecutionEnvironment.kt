package com.andmx.exec

/**
 * The pluggable execution backend abstraction — the spine of AndMX.
 *
 * Every "hand" the agent has (run a command, read/write files, drive git)
 * is ultimately expressed as a process run inside some environment. Concrete
 * environments include:
 *   - [LocalProcessEnvironment]      : raw ProcessBuilder on the Android host
 *   - LocalProotEnvironment (later)  : a proot guest distro (real shell/git)
 *   - RemoteSshEnvironment (later)   : tools running on a remote machine
 *
 * Keeping this interface narrow lets the agent loop and UI stay backend-
 * agnostic exactly as designed in plan C.
 */
interface ExecutionEnvironment {
    /** Stable identifier, e.g. "local-process", "local-proot". */
    val id: String

    /** Human-facing label shown in the UI. */
    val displayName: String

    /** Whether this environment is currently usable on the device. */
    suspend fun isAvailable(): Boolean

    /** Run a process to completion and return its captured result. */
    suspend fun execute(spec: ProcessSpec): ProcessResult
}

/** Description of a process to launch. */
data class ProcessSpec(
    val argv: List<String>,
    val workingDir: String? = null,
    val env: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    /** Merge stderr into stdout (terminal-style). */
    val redirectErrorStream: Boolean = false,
)

/** Captured outcome of a finished process. */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && exitCode == 0
}
