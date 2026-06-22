package com.andmx.exec.proot

import android.content.Context
import com.andmx.exec.ExecutionEnvironment
import com.andmx.exec.LocalProcessEnvironment
import com.andmx.exec.ProcessSpec
import com.andmx.exec.ProcessResult

/**
 * Execution environment that runs commands inside a proot guest. For now it
 * targets the host filesystem (rootfs = /) to validate the proot machinery;
 * once a guest distro is bootstrapped, [ProotRuntime.rootfsDir] takes over.
 */
class LocalProotEnvironment(
    private val context: Context,
    private val runtime: ProotRuntime = ProotRuntime(context),
    private val host: ExecutionEnvironment = LocalProcessEnvironment(),
) : ExecutionEnvironment {

    override val id: String = "local-proot"
    override val displayName: String = "本地 proot"

    override suspend fun isAvailable(): Boolean =
        runtime.isBundled() && runtime.install().ok

    override suspend fun execute(spec: ProcessSpec): ProcessResult {
        val install = runtime.install()
        if (!install.ok) {
            return ProcessResult(-1, "", "", 0, error = install.message)
        }
        val useRootfs = runtime.rootfsDir.takeIf { it.exists() && it.isDirectory }
        val argv = runtime.prootArgv(spec.argv, rootfs = useRootfs)
        return host.execute(
            spec.copy(
                argv = argv,
                env = runtime.env() + spec.env,
                redirectErrorStream = true,
            ),
        )
    }

    /** Convenience: run a shell command string through the guest shell. */
    suspend fun shell(command: String): ProcessResult {
        val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
        return execute(ProcessSpec(argv = listOf(sh, "-c", command)))
    }
}
