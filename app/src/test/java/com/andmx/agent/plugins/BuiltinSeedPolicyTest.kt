package com.andmx.agent.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BuiltinSeedPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun skipWhenVersionMatchesAndHealthy() {
        assertEquals(
            BuiltinSeedPolicy.SeedAction.SKIP,
            BuiltinSeedPolicy.decide("12", 12, destExists = true, manifestHealthy = true),
        )
        assertEquals(
            BuiltinSeedPolicy.SeedAction.SKIP,
            BuiltinSeedPolicy.decide(" 12 ", 12, destExists = true, manifestHealthy = true),
        )
    }

    @Test
    fun reseedOnVersionMismatchOrMissingDest() {
        assertEquals(
            BuiltinSeedPolicy.SeedAction.SEED,
            BuiltinSeedPolicy.decide("11", 12, destExists = true, manifestHealthy = true),
        )
        assertEquals(
            BuiltinSeedPolicy.SeedAction.SEED,
            BuiltinSeedPolicy.decide(null, 12, destExists = false, manifestHealthy = false),
        )
        assertEquals(
            BuiltinSeedPolicy.SeedAction.SEED,
            BuiltinSeedPolicy.decide("12", 12, destExists = false, manifestHealthy = true),
        )
    }

    @Test
    fun reseedWhenManifestCorruptEvenIfVersionMatches() {
        assertEquals(
            BuiltinSeedPolicy.SeedAction.SEED,
            BuiltinSeedPolicy.decide("12", 12, destExists = true, manifestHealthy = false),
        )
    }

    @Test
    fun manifestHealthDetectsMissingAndBrokenJson() {
        assertFalse(BuiltinSeedPolicy.manifestHealthy(null))
        assertFalse(BuiltinSeedPolicy.manifestHealthy(""))
        assertFalse(BuiltinSeedPolicy.manifestHealthy("   "))
        assertFalse(BuiltinSeedPolicy.manifestHealthy("{broken json"))
        assertFalse(BuiltinSeedPolicy.manifestHealthy("[1,2,3]"))
        assertFalse(BuiltinSeedPolicy.manifestHealthy("{}"))
        assertFalse(BuiltinSeedPolicy.manifestHealthy("""{"name":""}"""))
        assertFalse(BuiltinSeedPolicy.manifestHealthy("""{"version":"1.0"}"""))
        assertTrue(BuiltinSeedPolicy.manifestHealthy("""{"name":"design","version":"0.2.0"}"""))
    }

    @Test
    fun retrySucceedsOnThirdAttempt() {
        var calls = 0
        val result = BuiltinSeedPolicy.withRetry { attempt ->
            calls = attempt
            if (attempt < 3) throw IOException("transient $attempt")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun retryStopsAfterBudgetAndRethrowsLast() {
        var calls = 0
        try {
            BuiltinSeedPolicy.withRetry { attempt ->
                calls = attempt
                throw IOException("fail $attempt")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("fail 3", e.message)
        }
        assertEquals(3, calls)
    }

    @Test
    fun retryRejectsZeroBudget() {
        try {
            BuiltinSeedPolicy.withRetry(attempts = 0) { "x" }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("attempts"))
        }
    }

    private fun writeDir(dir: File, fileName: String, content: String) {
        dir.mkdirs()
        File(dir, fileName).writeText(content)
    }

    @Test
    fun swapReplacesOldAndRemovesBackup() {
        val parent = tmp.newFolder("plugins")
        val dest = File(parent, "design")
        val staging = File(parent, ".seed-staging-design")
        writeDir(dest, "plugin.json", "old")
        writeDir(staging, "plugin.json", "new")
        assertTrue(BuiltinSeedPolicy.swapIn(staging, dest))
        assertEquals("new", File(dest, "plugin.json").readText())
        assertFalse(staging.exists())
        assertFalse(File(parent, ".design.seed-backup").exists())
    }

    @Test
    fun swapOntoFreshDestination() {
        val parent = tmp.newFolder("plugins")
        val dest = File(parent, "design")
        val staging = File(parent, ".seed-staging-design")
        writeDir(staging, "plugin.json", "new")
        assertTrue(BuiltinSeedPolicy.swapIn(staging, dest))
        assertEquals("new", File(dest, "plugin.json").readText())
    }

    @Test
    fun swapRestoresOldVersionWhenReplaceFails() {
        val parent = tmp.newFolder("plugins")
        val dest = File(parent, "design")
        val staging = object : File(parent, ".seed-staging-design") {
            override fun renameTo(dest: File): Boolean = false
        }
        writeDir(dest, "plugin.json", "old")
        writeDir(staging, "plugin.json", "new")
        assertFalse(BuiltinSeedPolicy.swapIn(staging, dest))
        assertEquals("old", File(dest, "plugin.json").readText())
        assertTrue(staging.exists())
        assertFalse(File(parent, ".design.seed-backup").exists())
    }

    @Test
    fun swapFailsWithoutStagingAndKeepsOld() {
        val parent = tmp.newFolder("plugins")
        val dest = File(parent, "design")
        writeDir(dest, "plugin.json", "old")
        assertFalse(BuiltinSeedPolicy.swapIn(File(parent, "missing-staging"), dest))
        assertEquals("old", File(dest, "plugin.json").readText())
    }
}
