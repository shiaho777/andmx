package com.andmx.agent.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** 上游 `WorkflowArgsValidation`。 */
sealed interface ArgsValidation {
    data class Ok(val args: Map<String, JsonElement>) : ArgsValidation
    data class Failed(val errors: List<String>) : ArgsValidation
}

/**
 * Saved workflow 的参数校验。对齐上游 `saved-workflows/args.ts`。
 *
 * 校验发生在**确认窗之前**：参数传错了没有任何值得用户裁决的东西，弹一个注定失败的窗只是用
 * 一次无效决策打断模型自己的改错回路。这与 `CreateWorkflow` 对编不过的脚本的处理是同一条原则。
 */
object WorkflowArgs {

    /**
     * 把调用方传来的参数按声明校验并补齐默认值。
     *
     * 收集**全部**违规再一次性返回，而不是撞到第一条就退出：模型拿到「少了 pr，还多传了一个
     * prNumber」能一次改对，拿到「少了 pr」则会改一次、再撞一次。
     *
     * 未声明 args 的 workflow 收到任何参数都是错——它读不到它们，静默丢弃会让调用方以为参数生效了。
     */
    fun validate(
        declaration: Map<String, WorkflowScriptFrontmatter.ArgSpec>?,
        provided: JsonObject?,
    ): ArgsValidation {
        val declared = declaration.orEmpty()
        val given = provided.orEmpty()
        val errors = mutableListOf<String>()
        val args = LinkedHashMap<String, JsonElement>()

        val declaredNames = declared.keys.toList()
        for (key in given.keys) {
            if (declared.containsKey(key)) continue
            errors += if (declaredNames.isEmpty()) {
                "unknown argument '$key': this workflow declares no arguments"
            } else {
                "unknown argument '$key' (declared: ${declaredNames.joinToString(", ")})"
            }
        }

        for ((key, spec) in declared) {
            val supplied = given[key]
            // 只把「键不在」当作没给。显式 `null` 是模型**表过态**的值，必须走类型检查：
            // `json` 收它，`string` 拒它并报出 "got null"。上游 `describeValue` 里那条 null 分支
            // 正是为这条路存在的——把 null 当没给，那条分支永远不会被走到。
            if (supplied == null) {
                val fallback = spec.default
                if (fallback != null) {
                    // 默认值与传入值走**同一条**类型检查：一个声明成 number 却默认写成 "3" 的参数，
                    // 错在保存的那一刻，不该等到脚本读到它才炸。
                    val failure = typeMismatch(key, spec, fallback, "default value")
                    if (failure == null) args[key] = fallback else errors += failure
                    continue
                }
                if (spec.required == true) errors += "missing required argument '$key'"
                continue
            }

            val failure = typeMismatch(key, spec, supplied, "value")
            if (failure == null) args[key] = supplied else errors += failure
        }

        return if (errors.isEmpty()) ArgsValidation.Ok(args) else ArgsValidation.Failed(errors)
    }

    /**
     * 类型不符时的说明，符合时 `null`。
     *
     * `json` 什么都收——它的意思正是「这里不做检查」，所以连 null 都是合法的 json 值。三个原语按
     * JSON 标量种类判定；`number` 额外拒 NaN 与 Infinity，因为它们没法经 JSON 过界到沙箱，放行
     * 只会把一个可读的错误挪到脚本里变成一个 `null`。
     */
    private fun typeMismatch(
        key: String,
        spec: WorkflowScriptFrontmatter.ArgSpec,
        value: JsonElement,
        what: String,
    ): String? {
        fun describe(expected: String) = "argument '$key': expected $expected, got ${describeValue(value)} ($what)"

        return when (spec.type) {
            "string" -> if (value is JsonPrimitive && value.isString) null else describe("a string")
            "number" -> {
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                if (number != null && number.isFinite()) null else describe("a finite number")
            }
            "boolean" -> {
                val bool = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                if (bool != null) null else describe("a boolean")
            }
            "json" -> null
            else -> describe(spec.type)
        }
    }

    /**
     * 上游 `describeValue` 的逐字复述。
     *
     * 注意 `a object` 这个写法来自上游模板 `` `a ${typeof value}` ``——`typeof {}` 是 `"object"`，
     * 于是拼出 `a object`。**不要顺手改成 `an object`**：这是对照项目，文案逐字一致才有意义，
     * 改一处就得回答「还有多少处是照我们的口味改的」。
     */
    private fun describeValue(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonArray -> "an array"
        is JsonObject -> "a object"
        is JsonPrimitive -> when {
            value.isString -> "a string"
            value.booleanOrNull != null -> "a boolean"
            else -> "a number"
        }
    }
}
