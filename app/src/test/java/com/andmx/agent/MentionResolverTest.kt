package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionResolverTest {

    @Test
    fun noMentionReturnsNull() {
        assertNull(MentionResolver.parse("hello world"))
        assertNull(MentionResolver.parse("@root/app done")) // space ends mention
    }

    @Test
    fun parsesRelativeMention() {
        val q = MentionResolver.parse("see @ap")!!
        assertEquals("/root", q.listDir)
        assertEquals("", q.dirPart)
        assertEquals("ap", q.prefix)
    }

    @Test
    fun parsesNestedAbsoluteMention() {
        val q = MentionResolver.parse("open @/etc/ho")!!
        assertEquals("/etc", q.listDir)
        assertEquals("/etc/", q.dirPart)
        assertEquals("ho", q.prefix)
    }

    @Test
    fun completeInsertsChoice() {
        val q = MentionResolver.parse("look @/etc/ho")!!
        assertEquals("look @/etc/hosts ", MentionResolver.complete("look @/etc/ho", q, "hosts"))
        // directory choice keeps menu open (no trailing space)
        assertEquals("look @/etc/network/", MentionResolver.complete("look @/etc/ho", q, "network/"))
    }
}
