package com.andmx.agent

import java.util.Collections
import java.util.LinkedHashSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * ZCode tool-search exposure（local 模式）的 Kotlin 对应物。
 *
 * 目录由引擎的实时工具面提供：`shouldDefer` 的工具默认不随请求发送，只以
 * 名字出现在 system-reminder 里；ToolSearch 命中后把完整定义展开给模型，
 * DeferredToolCall 再按名字解包执行。没有 registry 的引擎保持旧的全量行为。
 */
class ToolSearchRegistry {

    companion object {
        const val TOOL_SEARCH_NAME = "ToolSearch"
        const val DEFERRED_TOOL_CALL_NAME = "DeferredToolCall"
        const val DEFAULT_RESULTS = 5
    }

    @Volatile private var toolsProvider: () -> List<Tool> = { emptyList() }
    @Volatile private var hiddenProvider: () -> Set<String> = { emptySet() }
    private val discovered = Collections.synchronizedSet(LinkedHashSet<String>())

    /** 引擎在每次 runTurn 前接上实时工具面与当轮隐藏集合。 */
    fun attach(toolsProvider: () -> List<Tool>, hiddenProvider: () -> Set<String>) {
        this.toolsProvider = toolsProvider
        this.hiddenProvider = hiddenProvider
    }

    fun visibleTools(): List<Tool> = toolsProvider().filter { it.name !in hiddenProvider() }

    fun deferredTools(): List<Tool> = visibleTools().filter { it.shouldDefer }

    fun isEnabled(): Boolean {
        val visible = visibleTools()
        return visible.any { it.shouldDefer } &&
            visible.any { it.name == TOOL_SEARCH_NAME } &&
            visible.any { it.name == DEFERRED_TOOL_CALL_NAME }
    }

    fun isDiscovered(name: String): Boolean = name in discovered

    fun discoveredNames(): Set<String> = synchronized(discovered) { discovered.toSet() }

    fun discover(names: Collection<String>) {
        discovered.addAll(names)
    }

    fun resolveDeferred(name: String): Tool? =
        visibleTools().firstOrNull { it.shouldDefer && it.name == name }

    /**
     * 请求里实际暴露的工具。目录里没有 deferred 工具、或控制工具缺失时，
     * 退回全量列表——宁可多花 token，也不能让模型无路可走。
     */
    fun exposed(tools: List<Tool>, hidden: Set<String>): List<Tool> {
        val visible = tools.filter { it.name !in hidden }
        val hasDeferred = visible.any { it.shouldDefer }
        if (!hasDeferred) {
            return visible.filter { it.name != TOOL_SEARCH_NAME && it.name != DEFERRED_TOOL_CALL_NAME }
        }
        val hasControls = visible.any { it.name == TOOL_SEARCH_NAME } &&
            visible.any { it.name == DEFERRED_TOOL_CALL_NAME }
        if (!hasControls) return visible
        return visible.filter { !it.shouldDefer }
    }

    /**
     * 每轮请求追加的旁路公告。只列名字，不展开 schema：展开是 ToolSearch
     * 的工作，公告只负责让模型知道「这些工具存在，且现在不能直接调」。
     */
    fun announcement(tools: List<Tool>, hidden: Set<String>): String? {
        val deferred = tools.filter { it.shouldDefer && it.name !in hidden }.sortedBy { it.name }
        if (deferred.isEmpty()) return null
        val undiscovered = deferred.filter { !isDiscovered(it.name) }
        val loaded = deferred.filter { isDiscovered(it.name) }
        val paragraphs = mutableListOf<String>()
        if (undiscovered.isNotEmpty()) {
            paragraphs += buildString {
                append("The following deferred tools are available via ${TOOL_SEARCH_NAME}. ")
                append("Only their names are known — their parameter schemas are NOT loaded, so calling them directly fails. ")
                append("Use ${TOOL_SEARCH_NAME} with query \"select:<name>[,<name>...]\" to load a schema, ")
                append("then call it through ${DEFERRED_TOOL_CALL_NAME} with {\"name\":\"<name>\",\"input\":{...}}:\n")
                append(undiscovered.joinToString("\n") { it.name })
            }
        }
        if (loaded.isNotEmpty()) {
            paragraphs += buildString {
                append("These deferred tools are already loaded. Call them through ${DEFERRED_TOOL_CALL_NAME} ")
                append("with {\"name\":\"<name>\",\"input\":{...}}; do not call them directly:\n")
                append(loaded.joinToString("\n") { it.name })
            }
        }
        return SystemReminder.wrap(paragraphs.joinToString("\n\n"))
    }

    fun controlTools(): List<Tool> = listOf(ToolSearchTool(this), DeferredToolCallTool(this))
}

class ToolSearchTool(
    private val registry: ToolSearchRegistry,
) : Tool {
    override val name = ToolSearchRegistry.TOOL_SEARCH_NAME
    override val description = TOOL_SEARCH_DESCRIPTION
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put(
                    "description",
                    "Query to find deferred tools. Use \"select:<tool_name>\" for direct selection, or keywords to search.",
                )
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", "Maximum number of results to return (default: ${ToolSearchRegistry.DEFAULT_RESULTS})")
                put("default", ToolSearchRegistry.DEFAULT_RESULTS)
            }
        }
        putJsonArray("required") {
            add("query")
            add("max_results")
        }
    }

    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val allowedInPlanMode = true
    override val timeoutMs = 10_000L
    override val maxOutputBytes = 100_000
    override val sideEffectScope = "none"
    override val riskLevel = "low"
    override val permissionName = "tool.search"

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!registry.isEnabled()) {
            return ToolResult("ToolSearch is not enabled for this request.", isError = true)
        }
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult("query is required", isError = true)
        val maxResults = (args["max_results"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: ToolSearchRegistry.DEFAULT_RESULTS
        val limit = maxResults.coerceIn(1, 50)
        val deferred = registry.deferredTools()
        val visible = registry.visibleTools()
        val names = ToolSearchQuery.search(query, deferred, visible, limit)
        if (names.isEmpty()) return ToolResult("No matching deferred tools found")

        val matched = names.mapNotNull { name -> visible.firstOrNull { it.name == name } }
        registry.discover(matched.filter { it.shouldDefer }.map { it.name })

        val json = Json { encodeDefaults = true }
        val functions = matched.joinToString("\n") { tool ->
            val definition = buildJsonObject {
                put("description", tool.description)
                put("name", tool.name)
                put("parameters", tool.parameters)
            }
            "<function>${json.encodeToString(JsonElement.serializer(), definition)}</function>"
        }
        return ToolResult(
            buildString {
                append("Matched ${matched.size} tool definition(s). Total deferred tools: ${deferred.size}.\n")
                append("Call deferred tools through ${ToolSearchRegistry.DEFERRED_TOOL_CALL_NAME} ")
                append("with {\"name\":\"<name>\",\"input\":{...}}; resident tools are called directly.\n\n")
                append("<functions>\n")
                append(functions)
                append("\n</functions>")
            },
        )
    }
}

class DeferredToolCallTool(
    private val registry: ToolSearchRegistry,
) : Tool {
    override val name = ToolSearchRegistry.DEFERRED_TOOL_CALL_NAME
    override val description =
        "Immediately call one deferred tool and return its result. Use ToolSearch to load its complete definition, " +
            "then pass its exact name and input object. Only deferred tools can be called here; call resident tools " +
            "directly using their normal tool calls."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Exact name of the deferred tool to call.")
            }
            putJsonObject("input") {
                put("type", "object")
                put("description", "JSON object passed as the deferred tool's arguments.")
                put("additionalProperties", true)
            }
        }
        putJsonArray("required") {
            add("name")
            add("input")
        }
    }

    override val risk = ToolRisk.EXECUTE
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = false
    override val needsApproval = true
    override val timeoutMs: Long? = null
    override val maxOutputBytes = 100_000
    override val sideEffectScope = "system"
    override val riskLevel = "high"
    override val permissionName = "tool.deferred_call"

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult(
            if (registry.isEnabled()) {
                "DeferredToolCall must be resolved by the agent engine before execution."
            } else {
                "DeferredToolCall is not enabled for this request."
            },
            isError = true,
        )
}

internal object ToolSearchQuery {

    fun search(
        query: String,
        deferred: List<Tool>,
        catalog: List<Tool>,
        maxResults: Int,
    ): List<String> {
        val select = Regex("^select:(.+)$", RegexOption.IGNORE_CASE).find(query)
        if (select != null) {
            return select.groupValues[1].split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { name ->
                    deferred.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: catalog.firstOrNull { it.name.equals(name, ignoreCase = true) }
                }
                .map { it.name }
                .distinct()
        }

        val text = query.lowercase().trim()
        if (text.isEmpty()) return emptyList()
        deferred.firstOrNull { it.name.lowercase() == text }?.let { return listOf(it.name) }

        val terms = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val required = terms.filter { it.startsWith("+") && it.length > 1 }.map { it.drop(1) }
        val keywords = if (required.isEmpty()) {
            terms
        } else {
            required + terms.filterNot { it.startsWith("+") && it.length > 1 }
        }
        val patterns = keywords.associateWith { term ->
            Regex("\\b${Regex.escape(term)}\\b")
        }

        return deferred.map { tool ->
            val tokens = tokens(tool.name)
            val full = tokens.joinToString(" ")
            val description = tool.description.lowercase()
            val requiredOk = required.all { term ->
                tokens.any { it.contains(term) } || patterns.getValue(term).containsMatchIn(description)
            }
            if (!requiredOk) return@map tool.name to 0

            var score = 0
            for (term in keywords) {
                if (tokens.contains(term)) {
                    score += 10
                } else if (tokens.any { it.contains(term) }) {
                    score += 5
                }
                if (full.contains(term) && score == 0) score += 3
                if (patterns.getValue(term).containsMatchIn(description)) score += 2
            }
            tool.name to score
        }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(maxResults)
            .map { it.first }
    }

    private fun tokens(name: String): List<String> =
        name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
}

private val TOOL_SEARCH_DESCRIPTION =
    "Fetches full schema definitions for deferred tools so they can be called using DeferredToolCall.\n\n" +
        "Deferred tools appear by name in <system-reminder> messages. Until fetched, only the name is known — " +
        "there is no parameter schema, so the tool cannot be invoked. This tool takes a query, matches it against " +
        "the deferred tool list, and returns the matched tools' complete JSONSchema definitions inside a " +
        "<functions> block. Call deferred tools through DeferredToolCall with {name, input}. Call resident tools " +
        "directly using their normal tool calls, even if their definitions were returned by direct selection.\n\n" +
        "Result format: each matched tool appears as one " +
        "<function>{\"description\": \"...\", \"name\": \"...\", \"parameters\": {...}}</function> line inside " +
        "the <functions> block. For deferred tools, parameters describes the input object passed to " +
        "DeferredToolCall.\n\n" +
        "Query forms:\n" +
        "- \"select:Read,Edit,Grep\" — fetch these exact tools by name\n" +
        "- \"notebook jupyter\" — keyword search, up to max_results best matches\n" +
        "- \"+slack send\" — require \"slack\" in the name, rank by remaining terms\n\n" +
        "Example (TodoWrite is deferred and already loaded):\n" +
        "<example>\n" +
        "DeferredToolCall({\"name\":\"TodoWrite\",\"input\":{\"todos\":[{\"content\":\"Inspect the implementation\"," +
        "\"status\":\"in_progress\",\"priority\":\"high\"}]}})\n" +
        "</example>"
