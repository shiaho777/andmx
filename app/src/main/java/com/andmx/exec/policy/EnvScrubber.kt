package com.andmx.exec.policy

/**
 * Credential scrubbing for spawned processes.
 *
 * A command the model chose inherits the harness process environment, and any
 * key or token living there would then reach `env`, command output, or a
 * spilled artifact. Drop matching entries before spawning.
 *
 * Nothing in the harness passes a credential to a child through the
 * environment: providers authenticate over HTTP from Kotlin, and the guest
 * gets its own explicit variable set from [com.andmx.exec.proot.ProotRuntime].
 */
object EnvScrubber {

    /**
     * Case-insensitive substrings marking a variable as a credential. Matching
     * is unanchored, so `GITHUB_TOKEN` and `MY_API_KEY_B64` are both caught.
     * Deliberately narrow: `PATH`, `HOME`, `TERM`, `LD_LIBRARY_PATH`, and the
     * `PROOT_*` variables the guest needs all survive.
     */
    private val SENSITIVE = listOf("KEY", "SECRET", "TOKEN", "PASSWORD", "CREDENTIAL")

    fun isSensitive(name: String): Boolean {
        val upper = name.uppercase()
        return SENSITIVE.any { upper.contains(it) }
    }

    /** Names in [names] that would carry a credential into a child process. */
    fun sensitiveKeys(names: Iterable<String>): List<String> = names.filter(::isSensitive)

    /** [env] without its credential-bearing entries. */
    fun scrub(env: Map<String, String>): Map<String, String> = env.filterKeys { !isSensitive(it) }
}
