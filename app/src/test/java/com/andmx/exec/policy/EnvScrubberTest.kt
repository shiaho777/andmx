package com.andmx.exec.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A command the model chose inherits the harness environment, so anything
 * credential-shaped must not survive into the child — otherwise it reaches
 * `env`, command output, or a spilled artifact.
 */
class EnvScrubberTest {

    @Test
    fun matchesCredentialShapedNamesUnanchored() {
        assertTrue(EnvScrubber.isSensitive("OPENAI_API_KEY"))
        assertTrue(EnvScrubber.isSensitive("MY_API_KEY_B64"))
        assertTrue(EnvScrubber.isSensitive("github_token"))
        assertTrue(EnvScrubber.isSensitive("DB_PASSWORD"))
        assertTrue(EnvScrubber.isSensitive("AWS_SECRET_ACCESS_KEY"))
        assertTrue(EnvScrubber.isSensitive("GOOGLE_APPLICATION_CREDENTIALS"))
    }

    @Test
    fun leavesOrdinaryVariablesAlone() {
        assertFalse(EnvScrubber.isSensitive("PATH"))
        assertFalse(EnvScrubber.isSensitive("HOME"))
        assertFalse(EnvScrubber.isSensitive("TERM"))
        assertFalse(EnvScrubber.isSensitive("LD_LIBRARY_PATH"))
        assertFalse(EnvScrubber.isSensitive("PROOT_TMP_DIR"))
        assertFalse(EnvScrubber.isSensitive("TMPDIR"))
    }

    @Test
    fun scrubRemovesOnlyTheSensitiveEntries() {
        val env = mapOf(
            "PATH" to "/usr/bin",
            "HOME" to "/root",
            "OPENAI_API_KEY" to "sk-live",
            "ANTHROPIC_TOKEN" to "secret",
            "TERM" to "xterm",
        )
        assertEquals(mapOf("PATH" to "/usr/bin", "HOME" to "/root", "TERM" to "xterm"), EnvScrubber.scrub(env))
    }

    @Test
    fun sensitiveKeysReportsWhatWouldLeak() {
        val names = listOf("PATH", "GITHUB_TOKEN", "HOME", "API_KEY")
        assertEquals(listOf("GITHUB_TOKEN", "API_KEY"), EnvScrubber.sensitiveKeys(names))
    }
}
