package com.andmx.mcp

class McpSessionRegistry<C>(private val closer: (C) -> Unit) {
    private val connections = LinkedHashMap<Pair<String, String>, C>()

    val size: Int get() = synchronized(connections) { connections.size }

    fun keyOf(serverKey: String, sessionId: String?): Pair<String, String> =
        serverKey to sessionId.orEmpty()

    fun contains(serverKey: String, sessionId: String?): Boolean =
        synchronized(connections) { keyOf(serverKey, sessionId) in connections }

    fun get(serverKey: String, sessionId: String?): C? =
        synchronized(connections) { connections[keyOf(serverKey, sessionId)] }

    fun getOrCreate(serverKey: String, sessionId: String?, factory: () -> C): C =
        synchronized(connections) {
            connections.getOrPut(keyOf(serverKey, sessionId)) { factory() }
        }

    fun sessionsFor(serverKey: String): List<String> = synchronized(connections) {
        connections.keys.filter { it.first == serverKey }.map { it.second }
    }

    fun keys(): List<Pair<String, String>> = synchronized(connections) { connections.keys.toList() }

    fun keysForName(serverName: String): List<Pair<String, String>> = synchronized(connections) {
        connections.keys.filter { it.first == serverName || it.first.startsWith("$serverName\u0000") }
    }

    fun remove(serverKey: String, sessionId: String?): Boolean {
        val removed = synchronized(connections) { connections.remove(keyOf(serverKey, sessionId)) }
            ?: return false
        runCatching { closer(removed) }
        return true
    }

    fun closeSession(sessionId: String): Int {
        val victims = synchronized(connections) {
            connections.entries.filter { it.key.second == sessionId }.map { it.key to it.value }
                .also { matched -> matched.forEach { connections.remove(it.first) } }
        }
        victims.forEach { (_, connection) -> runCatching { closer(connection) } }
        return victims.size
    }

    fun closeAll() {
        val all = synchronized(connections) {
            val values = connections.values.toList()
            connections.clear()
            values
        }
        all.forEach { runCatching { closer(it) } }
    }
}