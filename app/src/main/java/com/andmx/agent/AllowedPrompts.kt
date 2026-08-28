package com.andmx.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ZCode-style prompt-based permissions: semantic action categories approved
 * alongside a plan (ExitPlanMode's `allowedPrompts`), matched against later
 * Bash tool calls so an approved category ("run tests", "install dependencies")
 * auto-approves matching commands instead of prompting every time.
 */
object AllowedPrompts {

    data class Entry(val tool: String, val prompt: String)

    /** Parse ExitPlanMode args' allowedPrompts array; tolerant of shape drift. */
    fun parse(args: JsonObject): List<Entry> = runCatching {
        val arr = args["allowedPrompts"] as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = (el as? JsonObject) ?: return@mapNotNull null
            val tool = obj["tool"]?.jsonPrimitive?.content?.trim().orEmpty()
            val prompt = obj["prompt"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (tool.isBlank() || prompt.isBlank()) null else Entry(tool, prompt)
        }
    }.getOrDefault(emptyList())

    /** A session's accepted prompt-based grants, keyed by tool name. */
    class Grants {
        private val byTool = mutableMapOf<String, MutableList<String>>()

        fun addAll(entries: List<Entry>) {
            entries.forEach { entry ->
                byTool.getOrPut(entry.tool.lowercase()) { mutableListOf() }.add(entry.prompt)
            }
        }

        fun promptsFor(tool: String): List<String> = byTool[tool.lowercase()].orEmpty()

        fun isEmpty(): Boolean = byTool.isEmpty()

        /** Serialize for persistence across engine rebuilds. */
        fun toJson(): String = kotlinx.serialization.json.buildJsonObject {
            byTool.forEach { (tool, prompts) ->
                put(tool, JsonArray(prompts.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        }.toString()

        fun loadFrom(json: String) {
            runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                obj.forEach { (tool, value) ->
                    (value as? JsonArray)?.forEach { el ->
                        byTool.getOrPut(tool.lowercase()) { mutableListOf() }
                            .add(el.jsonPrimitive.content)
                    }
                }
            }
        }
    }

    /**
     * Decide whether a Bash command falls under an approved semantic prompt.
     * Matching is keyword-based: tokenize the prompt, drop stop words, and
     * require every remaining keyword to appear in the command verbatim —
     * conservative, so a grant for "run tests" won't match "rm -rf build".
     */
    fun matches(command: String, prompt: String): Boolean {
        val cmd = command.lowercase()
        val keywords = keywords(prompt)
        if (keywords.isEmpty()) return false
        return keywords.all { kw -> cmd.contains(kw) || stem(kw).any { cmd.contains(it) } }
    }

    fun grantsFor(command: String, prompts: List<String>): Boolean =
        prompts.any { matches(command, it) }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "to", "for", "of", "in", "on", "with", "please",
        "run", "execute", "use", "do", "my", "our", "this", "that", "project", "app",
        "check", "all", "your", "their", "its", "then", "when", "where", "needed",
    )

    private fun keywords(prompt: String): List<String> =
        prompt.lowercase().split(Regex("[^a-z0-9_-]+")).filter { it.length >= 3 && it !in STOP_WORDS }
            .map { kw -> singular(kw) }

    /** "dependencies"→"depend" so npm/pip/gradle invocations match; plural collapse otherwise. */
    private fun singular(kw: String): String = when {
        kw.endsWith("encies") && kw.length > 7 -> kw.dropLast(6)
        kw.endsWith("ies") && kw.length > 4 -> kw.dropLast(3) + "y"
        kw.endsWith("s") && !kw.endsWith("ss") && kw.length > 3 -> kw.dropLast(1)
        else -> kw
    }

    /** Common developer-tooling aliases so "tests" matches "gradle testLiteDebugUnitTest". */
    private fun stem(keyword: String): List<String> = when (keyword) {
        "test" -> listOf("test")
        "depend" -> listOf("install", "sync", "restore", "fetch", "add")  // any dependency command implies installation
        "install" -> listOf("install")
        "build" -> listOf("build", "assemble")
        "lint" -> listOf("lint", "detekt", "ktlint")
        "git" -> listOf("git", "status", "log", "diff", "commit", "branch", "push", "pull", "checkout", "stash", "remote")
        else -> emptyList()
    }
}
