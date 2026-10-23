package com.andmx.data.session

const val SEALED_TURN_TRANSIENT_GRACE_MS = 120_000L

fun isTransientSessionEvent(type: String): Boolean = type in TRANSIENT_SESSION_EVENT_TYPES

interface SessionEventRetentionPolicy {
    fun onAppend(type: String, turnId: String?, nowMs: Long): List<String>
    fun collectExpired(nowMs: Long, graceMs: Long): List<String>
}

fun createSessionEventRetentionPolicy(mode: String): SessionEventRetentionPolicy =
    if (mode == "unbounded") UnboundedRetention else TurnWindowRetention()

private object UnboundedRetention : SessionEventRetentionPolicy {
    override fun onAppend(type: String, turnId: String?, nowMs: Long): List<String> = emptyList()
    override fun collectExpired(nowMs: Long, graceMs: Long): List<String> = emptyList()
}

private class TurnWindowRetention : SessionEventRetentionPolicy {
    private val openTurns = linkedSetOf<String>()
    private val sealedAt = linkedMapOf<String, Long>()

    override fun onAppend(type: String, turnId: String?, nowMs: Long): List<String> {
        if (turnId == null) return emptyList()
        return when (type) {
            SessionEventType.TURN_STARTED -> {
                val evict = sealedAt.keys.filter { it != turnId }
                sealedAt.clear()
                openTurns += turnId
                evict
            }
            SessionEventType.TURN_COMPLETE, SessionEventType.TURN_ERROR -> {
                openTurns.remove(turnId)
                if (turnId !in sealedAt) sealedAt[turnId] = nowMs
                emptyList()
            }
            else -> emptyList()
        }
    }

    override fun collectExpired(nowMs: Long, graceMs: Long): List<String> {
        val expired = sealedAt.filter { (_, at) -> nowMs - at >= graceMs }.keys.toList()
        expired.forEach { sealedAt.remove(it) }
        return expired
    }
}
