package com.andmx.agent.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 保存的工作流文件头编解码，对齐 ZCode `saved-workflows/frontmatter.ts`。
 *
 * 文件形状：第一段非空白内容必须是 [SENTINEL]（块注释开头 + `zcode-workflow`），
 * 其后的 YAML 行描述 `description` / `whenToUse` / `args`，再由一行 `*` + `/` 收尾，
 * 收尾行之后的一切是脚本原文。
 *
 * 用块注释而不是 `---` 围栏，是因为文件扩展名是 `.dwf.ts`：整份文件仍是合法 TypeScript，
 * 编辑器的高亮、括号匹配、格式化都照常工作。
 *
 * 与上游的差异只在序列化的**排版**（上游走 `yaml` 库，这里走子集编码器），语义与字段顺序一致：
 * `description` → `whenToUse` → `args`，两次保存得到逐字节相同的文件。
 */
object WorkflowScriptFrontmatter {

    const val SENTINEL = "/* zcode-workflow"
    private const val TERMINATOR = "*/"

    /** `SavedWorkflowArgTypesSchema` 的取值集合。 */
    val ARG_TYPES: Set<String> = linkedSetOf("string", "number", "boolean", "json")

    private val META_KEYS = setOf("description", "whenToUse", "args")
    private val ARG_KEYS = setOf("type", "description", "required", "default")

    data class ArgSpec(
        val type: String,
        /** `null` 表示声明里没写 `required`，与写 `required: false` 不是同一件事。 */
        val required: Boolean? = null,
        val description: String? = null,
        val default: JsonElement? = null,
    )

    data class Meta(
        val description: String,
        val whenToUse: String? = null,
        val args: Map<String, ArgSpec> = emptyMap(),
    )

    enum class Reason {
        MISSING_FRONTMATTER,
        UNTERMINATED_FRONTMATTER,
        INVALID_YAML,
        INVALID_METADATA,
    }

    sealed interface Result {
        data class Ok(
            val meta: Meta,
            val script: String,
            /** 正文之前有多少行：诊断按文件行报出时用 `fileLine = bodyLine + bodyLineOffset`。 */
            val bodyLineOffset: Int,
        ) : Result

        data class Fail(val reason: Reason, val detail: String) : Result
    }

    fun parse(source: String): Result {
        val lines = source.split('\n')

        var start = 0
        while (start < lines.size && lines[start].isBlank()) start += 1
        if (start >= lines.size || lines[start].trim() != SENTINEL) {
            return Result.Fail(
                Reason.MISSING_FRONTMATTER,
                "file does not start with the `$SENTINEL` metadata block",
            )
        }

        var end = start + 1
        while (end < lines.size && lines[end].trim() != TERMINATOR) end += 1
        if (end >= lines.size) {
            return Result.Fail(
                Reason.UNTERMINATED_FRONTMATTER,
                "metadata block is never closed with `$TERMINATOR`",
            )
        }

        val bodyText = lines.subList(start + 1, end).joinToString("\n")
        val script = lines.subList(end + 1, lines.size).joinToString("\n")

        val root = try {
            YamlSubset.parse(bodyText)
        } catch (e: YamlSubset.YamlSubsetException) {
            return Result.Fail(Reason.INVALID_YAML, "line ${start + 1 + e.line}: ${e.message}")
        }

        return when (val meta = toMeta(root)) {
            is MetaResult.Ok -> Result.Ok(meta.meta, script, end + 1)
            is MetaResult.Bad -> Result.Fail(Reason.INVALID_METADATA, meta.detail)
        }
    }

    fun serialize(meta: Meta, script: String): String = buildString {
        append(SENTINEL).append('\n')
        appendScalar(this, "description", meta.description, 0)
        meta.whenToUse?.let { appendScalar(this, "whenToUse", it, 0) }
        if (meta.args.isNotEmpty()) {
            append("args:\n")
            meta.args.forEach { (name, spec) ->
                append("  ").append(encodeKey(name)).append(":\n")
                append("    type: ").append(spec.type).append('\n')
                spec.required?.let { append("    required: ").append(it).append('\n') }
                spec.description?.let { appendScalar(this, "description", it, 4) }
                spec.default?.let { append("    default: ").append(encodeJson(it)).append('\n') }
            }
        }
        append(TERMINATOR).append('\n')
        append(script)
    }

    private sealed interface MetaResult {
        data class Ok(val meta: Meta) : MetaResult
        data class Bad(val detail: String) : MetaResult
    }

    private fun toMeta(root: Any?): MetaResult {
        if (root == null) return MetaResult.Bad("metadata block is empty")
        val map = root as? Map<*, *> ?: return MetaResult.Bad("metadata block must be a mapping")
        val keys = map.keys.map { it.toString() }
        val unknown = keys.filter { it !in META_KEYS }
        if (unknown.isNotEmpty()) {
            return MetaResult.Bad("unknown metadata ${unknown.joinToString(", ")}")
        }

        val description = map["description"]
        if (description !is String || description.isEmpty()) {
            return MetaResult.Bad("description: required non-empty string")
        }
        val whenToUse = map["whenToUse"]
        if (whenToUse != null && (whenToUse !is String || whenToUse.isEmpty())) {
            return MetaResult.Bad("whenToUse: must be a non-empty string")
        }

        val args = LinkedHashMap<String, ArgSpec>()
        val rawArgs = map["args"]
        if (rawArgs != null) {
            val argMap = rawArgs as? Map<*, *> ?: return MetaResult.Bad("args: must be a mapping")
            argMap.forEach { (rawName, rawSpec) ->
                val name = rawName.toString()
                val spec = rawSpec as? Map<*, *>
                    ?: return MetaResult.Bad("args.$name: must be a mapping")
                val specKeys = spec.keys.map { it.toString() }
                val unknownSpec = specKeys.filter { it !in ARG_KEYS }
                if (unknownSpec.isNotEmpty()) {
                    return MetaResult.Bad("args.$name: unknown field ${unknownSpec.joinToString(", ")}")
                }
                val type = spec["type"] as? String
                    ?: return MetaResult.Bad("args.$name.type: required string")
                if (type !in ARG_TYPES) {
                    return MetaResult.Bad("args.$name.type: `${type}` is not one of ${ARG_TYPES.joinToString(", ")}")
                }
                val required = when (val value = spec["required"]) {
                    null -> null
                    is Boolean -> value
                    else -> return MetaResult.Bad("args.$name.required: must be a boolean")
                }
                val argDescription = when (val value = spec["description"]) {
                    null -> null
                    is String -> value
                    else -> return MetaResult.Bad("args.$name.description: must be a string")
                }
                val default = spec["default"]?.let { toJson(it) }
                args[name] = ArgSpec(type, required, argDescription, default)
            }
        }

        return MetaResult.Ok(
            Meta(
                description = description,
                whenToUse = whenToUse as String?,
                args = args,
            ),
        )
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { toJson(it) })
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        else -> JsonPrimitive(value.toString())
    }

    private fun encodeJson(element: JsonElement): String = when (element) {
        is JsonPrimitive -> if (element.isString) encodeScalar(element.content, quoteSensitive = true) else element.toString()
        else -> element.toString()
    }

    private fun appendScalar(builder: StringBuilder, key: String, value: String, indent: Int) {
        val pad = " ".repeat(indent)
        if (value.contains('\n')) {
            // 末尾有换行用 `|`（clip），没有就用 `|-`（strip）——两种都能原样读回。
            val strip = !value.endsWith('\n')
            builder.append(pad).append(key).append(": ").append(if (strip) "|-\n" else "|\n")
            val body = if (strip) value else value.dropLast(1)
            body.split('\n').forEach { line ->
                if (line.isEmpty()) builder.append('\n') else builder.append(pad).append("  ").append(line).append('\n')
            }
            return
        }
        builder.append(pad).append(key).append(": ").append(encodeScalar(value, quoteSensitive = false)).append('\n')
    }

    /** 需要引号的值就用双引号，否则裸标量——保证 `parse(serialize(m)) == m`。 */
    private fun encodeScalar(value: String, quoteSensitive: Boolean): String {
        val needsQuote = value.isEmpty() ||
            value != value.trim() ||
            value.first() in "-?:,[]{}#&*!|>'\"%@`" ||
            value.contains(": ") ||
            value.contains(" #") ||
            value.contains('\n') ||
            value.equals("true", true) ||
            value.equals("false", true) ||
            value.equals("null", true) ||
            value == "~" ||
            value.matches(Regex("^[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$")) ||
            (quoteSensitive && value.any { it in ",{}[]" })
        return if (!needsQuote) value else quote(value)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun encodeKey(key: String): String =
        if (key.matches(Regex("^[A-Za-z0-9_.-]+$"))) key else quote(key)
}
