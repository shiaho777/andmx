package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEvictionTest {

    private val ttl = 600_000L
    private val config = SessionEvictionConfig(highWater = 16, lowWater = 8, idleTtlMs = ttl)
    private val now = 10_000_000L

    private fun candidate(id: Long, ageMs: Long, facts: Set<String> = emptySet()) =
        SessionEvictionCandidate(id = id, lastAccessAt = now - ageMs, protectedFacts = facts)

    @Test
    fun ttlExpiredEvictedRegardlessOfCount() {
        val candidates = listOf(candidate(1, ttl), candidate(2, 0))
        assertEquals(
            listOf(1L),
            SessionEvictionPolicy.selectEvictions(candidates, now, config),
        )
    }

    @Test
    fun ttlBoundaryNotYetExpired() {
        val candidates = listOf(candidate(1, ttl - 1))
        assertTrue(SessionEvictionPolicy.selectEvictions(candidates, now, config).isEmpty())
    }

    @Test
    fun protectedSessionsNeverEvicted() {
        val facts = listOf("running", "queued", "interaction", "background", "subagent", "subscriber", "focused")
        val candidates = facts.mapIndexed { i, fact -> candidate(i + 1L, ttl * 2, setOf(fact)) }
        assertTrue(SessionEvictionPolicy.selectEvictions(candidates, now, config).isEmpty())
    }

    @Test
    fun highWaterHysteresisEvictsLruDownToLowWater() {
        val candidates = (1L..17L).map { candidate(it, ageMs = it * 1000L) }
        val evicted = SessionEvictionPolicy.selectEvictions(candidates, now, config)
        assertEquals((9L..17L).toList().reversed(), evicted)
        assertEquals(8, candidates.size - evicted.size)
    }

    @Test
    fun atHighWaterNothingEvictedWhenFresh() {
        val candidates = (1L..16L).map { candidate(it, ageMs = it * 1000L) }
        assertTrue(SessionEvictionPolicy.selectEvictions(candidates, now, config).isEmpty())
    }

    @Test
    fun protectedSurplusAllowedAboveHighWater() {
        val candidates = (1L..18L).map { candidate(it, ttl * 2, setOf("running")) }
        assertTrue(SessionEvictionPolicy.selectEvictions(candidates, now, config).isEmpty())
    }

    @Test
    fun highWaterStopsWhenNoIdleCandidatesRemain() {
        val candidates = (1L..5L).map { candidate(it, ageMs = it * 1000L) } +
            (6L..18L).map { candidate(it, ageMs = it * 1000L, facts = setOf("subscriber")) }
        val evicted = SessionEvictionPolicy.selectEvictions(candidates, now, config)
        assertEquals((1L..5L).toList().reversed(), evicted)
        assertEquals(13, candidates.size - evicted.size)
    }

    @Test
    fun ttlResetByRecentAccessPreventsEviction() {
        val candidates = listOf(candidate(1, ttl + 5_000L), candidate(2, ttl + 5_000L))
        assertEquals(listOf(1L, 2L), SessionEvictionPolicy.selectEvictions(candidates, now, config))
        val touched = listOf(candidate(1, 1_000L), candidate(2, ttl + 5_000L))
        assertEquals(listOf(2L), SessionEvictionPolicy.selectEvictions(touched, now, config))
    }

    @Test
    fun protectionReappearingBlocksTtlEviction() {
        val idle = listOf(candidate(1, ttl + 1_000L))
        assertEquals(listOf(1L), SessionEvictionPolicy.selectEvictions(idle, now, config))
        val protected = listOf(candidate(1, ttl + 1_000L, setOf("background")))
        assertTrue(SessionEvictionPolicy.selectEvictions(protected, now, config).isEmpty())
    }

    @Test
    fun lruOrderFollowsLastAccessNotId() {
        val candidates = listOf(
            candidate(30, ageMs = 3_000L),
            candidate(10, ageMs = 9_000L),
            candidate(20, ageMs = 6_000L),
        ) + (100L..114L).map { candidate(it, ageMs = 1_000L) }
        val evicted = SessionEvictionPolicy.selectEvictions(candidates, now, config)
        assertEquals(listOf(10L, 20L, 30L, 100L, 101L, 102L, 103L, 104L, 105L, 106L), evicted)
    }

    @Test
    fun ttlAndHighWaterSameRoundNoDuplicate() {
        val candidates = listOf(candidate(1, ttl + 1_000L), candidate(2, ttl + 2_000L)) +
            (3L..19L).map { candidate(it, ageMs = it * 1000L) }
        val evicted = SessionEvictionPolicy.selectEvictions(candidates, now, config)
        assertEquals(evicted.size, evicted.toSet().size)
        assertEquals(listOf(2L, 1L, 19L, 18L, 17L, 16L, 15L, 14L, 13L, 12L, 11L), evicted)
        assertEquals(8, candidates.size - evicted.size)
    }

    @Test
    fun emptyListYieldsNoEvictions() {
        assertTrue(SessionEvictionPolicy.selectEvictions(emptyList(), now, config).isEmpty())
    }

    @Test
    fun defaultsMatchSpec() {
        val defaults = SessionEvictionConfig()
        assertEquals(16, defaults.highWater)
        assertEquals(8, defaults.lowWater)
        assertEquals(600_000L, defaults.idleTtlMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidWaterCombinationRejected() {
        SessionEvictionConfig(highWater = 4, lowWater = 8)
    }
}
