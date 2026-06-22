package com.andmx.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchEngineTest {

    @Test
    fun appliesSimpleReplacement() {
        val original = "alpha\nbeta\ngamma"
        val patch = """
            @@ -1,3 +1,3 @@
             alpha
            -beta
            +BETA
             gamma
        """.trimIndent()
        val r = PatchEngine.apply(original, patch)
        assertTrue(r is PatchEngine.Result.Ok)
        assertEquals("alpha\nBETA\ngamma", (r as PatchEngine.Result.Ok).content)
    }

    @Test
    fun appliesAdditionAndDeletion() {
        val original = "one\ntwo\nthree\nfour"
        val patch = """
            @@ -1,4 +1,4 @@
             one
            -two
            -three
            +TWO
             four
        """.trimIndent()
        val r = PatchEngine.apply(original, patch) as PatchEngine.Result.Ok
        assertEquals("one\nTWO\nfour", r.content)
    }

    @Test
    fun toleratesLineNumberDrift() {
        // hunk header claims line 1 but block actually sits later
        val original = "h1\nh2\nh3\ntarget\nafter"
        val patch = """
            @@ -1,1 +1,1 @@
            -target
            +TARGET
        """.trimIndent()
        val r = PatchEngine.apply(original, patch) as PatchEngine.Result.Ok
        assertEquals("h1\nh2\nh3\nTARGET\nafter", r.content)
    }

    @Test
    fun failsWhenContextMissing() {
        val r = PatchEngine.apply("abc", "@@ -1 +1 @@\n-not-there\n+x")
        assertTrue(r is PatchEngine.Result.Fail)
    }
}
