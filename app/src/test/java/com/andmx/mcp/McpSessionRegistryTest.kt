package com.andmx.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSessionRegistryTest {

    private class FakeConnection(var closed: Boolean = false)

    private fun registry(closed: MutableList<FakeConnection> = mutableListOf()): McpSessionRegistry<FakeConnection> =
        McpSessionRegistry { connection ->
            connection.closed = true
            closed += connection
        }

    @Test
    fun sameKeyReusesConnectionAcrossCalls() {
        val reg = registry()
        var created = 0
        val first = reg.getOrCreate("srv", "s1") { created++; FakeConnection() }
        val second = reg.getOrCreate("srv", "s1") { created++; FakeConnection() }
        assertSame(first, second)
        assertEquals(1, created)
        assertEquals(1, reg.size)
    }

    @Test
    fun differentSessionsGetIsolatedConnections() {
        val reg = registry()
        val a = reg.getOrCreate("srv", "s1") { FakeConnection() }
        val b = reg.getOrCreate("srv", "s2") { FakeConnection() }
        val shared = reg.getOrCreate("srv", null) { FakeConnection() }
        assertNotSame(a, b)
        assertNotSame(a, shared)
        assertEquals(3, reg.size)
        assertTrue(reg.contains("srv", "s1"))
        assertTrue(reg.contains("srv", null))
        assertFalse(reg.contains("srv", "s3"))
    }

    @Test
    fun differentServersInSameSessionAreSeparate() {
        val reg = registry()
        val a = reg.getOrCreate("srv-a", "s1") { FakeConnection() }
        val b = reg.getOrCreate("srv-b", "s1") { FakeConnection() }
        assertNotSame(a, b)
        assertEquals(listOf("s1"), reg.sessionsFor("srv-a"))
    }

    @Test
    fun closeSessionOnlyReleasesThatSessionsConnections() {
        val closed = mutableListOf<FakeConnection>()
        val reg = registry(closed)
        val s1a = reg.getOrCreate("srv-a", "s1") { FakeConnection() }
        val s1b = reg.getOrCreate("srv-b", "s1") { FakeConnection() }
        val s2 = reg.getOrCreate("srv-a", "s2") { FakeConnection() }
        val shared = reg.getOrCreate("srv-a", null) { FakeConnection() }

        assertEquals(2, reg.closeSession("s1"))

        assertTrue(s1a.closed)
        assertTrue(s1b.closed)
        assertFalse(s2.closed)
        assertFalse(shared.closed)
        assertNull(reg.get("srv-a", "s1"))
        assertSame(s2, reg.get("srv-a", "s2"))
        assertSame(shared, reg.get("srv-a", null))
        assertEquals(listOf(s1a, s1b), closed)
    }

    @Test
    fun closeSessionWithoutMatchesIsNoop() {
        val reg = registry()
        val shared = reg.getOrCreate("srv", null) { FakeConnection() }
        assertEquals(0, reg.closeSession("missing"))
        assertFalse(shared.closed)
        assertEquals(1, reg.size)
    }

    @Test
    fun removeClosesOnlyTheTargetKey() {
        val reg = registry()
        val a = reg.getOrCreate("srv", "s1") { FakeConnection() }
        val b = reg.getOrCreate("srv", "s2") { FakeConnection() }
        assertTrue(reg.remove("srv", "s1"))
        assertFalse(reg.remove("srv", "s1"))
        assertTrue(a.closed)
        assertFalse(b.closed)
    }

    @Test
    fun closeAllReleasesEverythingIncludingShared() {
        val reg = registry()
        val a = reg.getOrCreate("srv", "s1") { FakeConnection() }
        val shared = reg.getOrCreate("srv", null) { FakeConnection() }
        reg.closeAll()
        assertTrue(a.closed)
        assertTrue(shared.closed)
        assertEquals(0, reg.size)
    }

    @Test
    fun compositeServerKeysAreFoundByName() {
        val reg = registry()
        reg.getOrCreate("srv\u0000https://a", "s1") { FakeConnection() }
        reg.getOrCreate("srv\u0000https://b", "s2") { FakeConnection() }
        reg.getOrCreate("other\u0000https://c", "s1") { FakeConnection() }
        assertEquals(2, reg.keysForName("srv").size)
        assertEquals(3, reg.keys().size)
        assertEquals(2, reg.closeSession("s1"))
        assertEquals(1, reg.keysForName("srv").size)
    }
}