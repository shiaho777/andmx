package com.andmx.ui2.files

data class CommitNode(
    val hash: String,
    val parents: List<String>,
    val subject: String,
    val author: String,
    val timestamp: Long,
    val lane: Int = 0,
) {
    val shortHash: String get() = hash.take(7)
    val isMerge: Boolean get() = parents.size > 1
}

object GitGraphParser {

    fun parse(output: String, limit: Int = 50): List<CommitNode> {
        val rows = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseLine(it) }
            .take(limit.coerceAtLeast(1))
            .toList()
        if (rows.isEmpty()) return emptyList()
        return assignLanes(rows)
    }

    private fun parseLine(line: String): CommitNode? {
        val parts = line.split('|', limit = 5)
        if (parts.size < 5) return null
        val hash = parts[0].trim()
        if (hash.length < 7 || !hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val parents = parts[1].trim().split(' ').filter { it.isNotBlank() }
        if (parents.any { p -> p.length < 7 || !p.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }) return null
        val timestamp = parts[4].trim().toLongOrNull() ?: return null
        return CommitNode(
            hash = hash,
            parents = parents,
            subject = parts[2].trim(),
            author = parts[3].trim(),
            timestamp = timestamp,
        )
    }

    private fun assignLanes(commits: List<CommitNode>): List<CommitNode> {
        val active = mutableListOf<String?>()
        val result = ArrayList<CommitNode>(commits.size)
        for (commit in commits) {
            var lane = active.indexOf(commit.hash)
            if (lane < 0) {
                lane = active.indexOf(null)
                if (lane < 0) {
                    lane = active.size
                    active.add(commit.hash)
                } else {
                    active[lane] = commit.hash
                }
            }
            result += commit.copy(lane = lane)
            active[lane] = commit.parents.firstOrNull()
            for (parent in commit.parents.drop(1)) {
                if (active.contains(parent)) continue
                val free = active.indexOf(null)
                if (free < 0) active.add(parent) else active[free] = parent
            }
            while (active.isNotEmpty() && active.last() == null) active.removeAt(active.lastIndex)
        }
        return result
    }
}