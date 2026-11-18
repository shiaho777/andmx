package com.andmx.ui2.chat

data class SessionEvictionCandidate(
    val id: Long,
    val lastAccessAt: Long,
    val protectedFacts: Set<String>,
)

data class SessionEvictionConfig(
    val highWater: Int = 16,
    val lowWater: Int = 8,
    val idleTtlMs: Long = 600_000L,
) {
    init {
        require(lowWater in 0..highWater)
        require(idleTtlMs > 0L)
    }
}

object SessionEvictionPolicy {
    fun selectEvictions(
        candidates: List<SessionEvictionCandidate>,
        now: Long,
        config: SessionEvictionConfig = SessionEvictionConfig(),
    ): List<Long> {
        val idle = candidates.filter { it.protectedFacts.isEmpty() }
        val expired = idle.filter { now - it.lastAccessAt >= config.idleTtlMs }
            .sortedBy { it.lastAccessAt }
        val victims = mutableListOf<Long>()
        victims += expired.map { it.id }
        var resident = candidates.size - victims.size
        if (resident > config.highWater) {
            val expiredIds = expired.map { it.id }.toSet()
            val lru = idle.filter { it.id !in expiredIds }.sortedBy { it.lastAccessAt }
            for (candidate in lru) {
                if (resident <= config.lowWater) break
                victims += candidate.id
                resident--
            }
        }
        return victims
    }
}
