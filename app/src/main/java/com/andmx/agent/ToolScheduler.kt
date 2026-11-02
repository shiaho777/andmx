package com.andmx.agent

data class ToolDependency(
    val toolCallId: String,
    val toolName: String? = null,
    val dependsOn: List<String> = emptyList(),
    val readOnly: Boolean? = null,
    val destructive: Boolean? = null,
    val concurrentSafe: Boolean? = null,
    val sideEffectScope: String? = null,
)

data class ToolSchedule(
    val parallelGroups: List<List<String>>,
    val executionOrder: List<String>,
)

class ToolScheduler(
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    private val readOnlyTools: Set<String> = READ_ONLY_TOOLS,
) {
    fun schedule(tools: List<ToolDependency>): ToolSchedule {
        val items = tools.map { tool ->
            Item(
                toolCallId = tool.toolCallId,
                dependencies = tool.dependsOn,
                canRunParallel = canRunInParallel(tool),
            )
        }
        val sorted = topologicalSort(items)
        val groups = groupByParallel(sorted)
        validateNoCycles(items, groups)
        return ToolSchedule(
            parallelGroups = groups,
            executionOrder = groups.flatten(),
        )
    }

    private fun canRunInParallel(tool: ToolDependency): Boolean {
        val hasToolName = !tool.toolName.isNullOrEmpty()
        val hasSafetyMetadata = tool.readOnly != null ||
            tool.destructive != null ||
            tool.concurrentSafe != null ||
            tool.sideEffectScope != null
        if (!hasToolName && !hasSafetyMetadata) return true
        val readOnly = tool.readOnly
            ?: if (hasToolName) tool.toolName in readOnlyTools else false
        if (tool.destructive == true) return false
        if (tool.concurrentSafe == true) return true
        if (tool.concurrentSafe == false) return false
        if (readOnly) return true
        return tool.sideEffectScope == "none"
    }

    private fun topologicalSort(items: List<Item>): List<Item> {
        val result = mutableListOf<Item>()
        val remaining = items.associateBy { it.toolCallId }.toMutableMap()
        val inDegree = items.associate { it.toolCallId to it.dependencies.size }.toMutableMap()
        val queue = ArrayDeque(items.filter { it.dependencies.isEmpty() }.map { it.toolCallId })
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val item = remaining.remove(current) ?: continue
            result += item
            for ((toolId, depItem) in remaining) {
                if (current in depItem.dependencies) {
                    val newDegree = (inDegree[toolId] ?: 0) - 1
                    inDegree[toolId] = newDegree
                    if (newDegree == 0) queue.addLast(toolId)
                }
            }
        }
        if (remaining.isNotEmpty()) {
            throw IllegalStateException("Circular dependency detected in tool scheduling")
        }
        return result
    }

    private fun groupByParallel(items: List<Item>): List<List<String>> {
        if (items.isEmpty()) return emptyList()
        val levels = HashMap<String, Int>()
        val byLevel = HashMap<Int, MutableList<Item>>()
        for (item in items) {
            val dependencyLevel = if (item.dependencies.isEmpty()) {
                -1
            } else {
                item.dependencies.maxOf { levels[it] ?: 0 }
            }
            val level = dependencyLevel + 1
            levels[item.toolCallId] = level
            byLevel.getOrPut(level) { mutableListOf() }.add(item)
        }
        val groups = mutableListOf<List<String>>()
        for (level in byLevel.keys.sorted()) {
            val parallelGroup = mutableListOf<String>()
            fun flushParallelGroup() {
                if (parallelGroup.isEmpty()) return
                groups += parallelGroup.toList()
                parallelGroup.clear()
            }
            for (item in byLevel[level].orEmpty()) {
                if (item.canRunParallel) {
                    if (parallelGroup.size >= maxConcurrency) flushParallelGroup()
                    parallelGroup += item.toolCallId
                    continue
                }
                flushParallelGroup()
                groups += listOf(item.toolCallId)
            }
            flushParallelGroup()
        }
        return groups
    }

    private fun validateNoCycles(items: List<Item>, groups: List<List<String>>) {
        val itemsMap = items.associateBy { it.toolCallId }
        for (group in groups) {
            val groupSet = group.toSet()
            for (toolId in group) {
                val item = itemsMap[toolId] ?: continue
                for (dep in item.dependencies) {
                    if (dep in groupSet) {
                        throw IllegalStateException(
                            "Circular dependency detected: $toolId depends on $dep in same group",
                        )
                    }
                }
            }
        }
    }

    private data class Item(
        val toolCallId: String,
        val dependencies: List<String>,
        val canRunParallel: Boolean,
    )

    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 10
        val READ_ONLY_TOOLS = setOf(
            "Read",
            "Glob",
            "Grep",
            "WebSearch",
            "WebFetch",
            "TodoRead",
            "TodoWrite",
            "AskUserQuestion",
            "Skill",
        )
        const val TOOL_CANCELLED_AFTER_TURN_STOP =
            "Tool cancelled because a previous tool result requested a turn stop."
    }
}
