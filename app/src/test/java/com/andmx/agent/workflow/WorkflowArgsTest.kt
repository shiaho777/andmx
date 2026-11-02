package com.andmx.agent.workflow

import com.andmx.agent.workflow.WorkflowScriptFrontmatter.ArgSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数校验对着上游 `saved-workflows/args.ts` 的语义做断言。
 *
 * 重点是**一次性收全部违规**：模型拿到「少了 pr，还多传了一个 prNumber」能一次改对，
 * 拿到「少了 pr」则会改一次、再撞一次。
 */
class WorkflowArgsTest {

    private fun args(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(pairs.toMap())

    private val decl = mapOf(
        "topic" to ArgSpec(type = "string", required = true),
        "count" to ArgSpec(type = "number", required = false, default = JsonPrimitive(3)),
        "dry" to ArgSpec(type = "boolean"),
        "extra" to ArgSpec(type = "json"),
    )

    @Test
    fun `缺必填参数被点名`() {
        val result = WorkflowArgs.validate(decl, args())

        val failed = result as ArgsValidation.Failed
        assertEquals(listOf("missing required argument 'topic'"), failed.errors)
    }

    @Test
    fun `未声明的参数在无声明时说清它没有参数`() {
        val result = WorkflowArgs.validate(null, args("count" to JsonPrimitive(1)))

        val failed = result as ArgsValidation.Failed
        assertEquals(
            listOf("unknown argument 'count': this workflow declares no arguments"),
            failed.errors,
        )
    }

    @Test
    fun `未声明的参数在有条目时列出已声明的名字`() {
        val result = WorkflowArgs.validate(decl, args("bogus" to JsonPrimitive("x")))

        val errors = (result as ArgsValidation.Failed).errors
        val unknown = errors.single { it.startsWith("unknown argument 'bogus'") }
        assertTrue(unknown.contains("(declared: "))
        assertTrue(unknown.contains("topic"))
        // 顺带：因为 topic 必填而那一次没给，它也在这批里——一次收齐。
        assertTrue(errors.contains("missing required argument 'topic'"))
    }

    @Test
    fun `一次收齐全部违规`() {
        val result = WorkflowArgs.validate(
            decl,
            args(
                "bogus" to JsonPrimitive("x"),
                "count" to JsonPrimitive("three"),
                "dry" to JsonPrimitive(1),
            ),
        )

        val failed = result as ArgsValidation.Failed
        // 少 topic、多 bogus、count 类型错、dry 类型错 —— 四条一起回来。
        assertEquals(4, failed.errors.size)
        assertTrue(failed.errors.any { it.contains("missing required argument 'topic'") })
        assertTrue(failed.errors.any { it.contains("unknown argument 'bogus'") })
        assertTrue(failed.errors.any { it.contains("argument 'count': expected a finite number") })
        assertTrue(failed.errors.any { it.contains("argument 'dry': expected a boolean") })
    }

    @Test
    fun `默认值被补齐`() {
        val result = WorkflowArgs.validate(decl, args("topic" to JsonPrimitive("象棋")))

        val ok = result as ArgsValidation.Ok
        assertEquals(JsonPrimitive("象棋"), ok.args["topic"])
        assertEquals(JsonPrimitive(3), ok.args["count"])
        // 没给、没默认、非必填 → 键不出现，不造空壳。
        assertTrue(ok.args.containsKey("dry").not())
    }

    @Test
    fun `默认值走与传入值同一条类型检查`() {
        val bad = mapOf(
            "count" to ArgSpec(type = "number", default = JsonPrimitive("3")),
        )

        val failed = WorkflowArgs.validate(bad, args()) as ArgsValidation.Failed
        assertEquals(
            listOf("argument 'count': expected a finite number, got a string (default value)"),
            failed.errors,
        )
    }

    @Test
    fun `json 类型什么都收 连 null 也收`() {
        val onlyJson = mapOf("extra" to ArgSpec(type = "json"))

        assertEquals(
            ArgsValidation.Ok(mapOf("extra" to JsonNull)),
            WorkflowArgs.validate(onlyJson, args("extra" to JsonNull)),
        )
        assertEquals(
            ArgsValidation.Ok(mapOf("extra" to JsonArray(emptyList()))),
            WorkflowArgs.validate(onlyJson, args("extra" to JsonArray(emptyList()))),
        )
    }

    @Test
    fun `显式 null 走类型检查而不是当作没给`() {
        val result = WorkflowArgs.validate(decl, args("topic" to JsonNull))

        val failed = result as ArgsValidation.Failed
        // null 是模型表过态的值：它该被 string 拒掉并报出 "got null"，而不是被当成缺省。
        assertEquals(
            listOf("argument 'topic': expected a string, got null (value)"),
            failed.errors,
        )
    }

    @Test
    fun `对象实参的判词沿用上游的 a object`() {
        val onlyString = mapOf("topic" to ArgSpec(type = "string", required = true))

        val failed = WorkflowArgs.validate(
            onlyString,
            args("topic" to buildJsonObject { put("a", 1) }),
        ) as ArgsValidation.Failed

        // 上游模板是 `a ${typeof value}`，typeof {} 是 "object"，于是拼出 a object。
        // 这是逐字复述，不是笔误。
        assertEquals(
            listOf("argument 'topic': expected a string, got a object (value)"),
            failed.errors,
        )
    }

    @Test
    fun `数组与 null 的判词`() {
        val onlyString = mapOf("topic" to ArgSpec(type = "string", required = true))

        val array = WorkflowArgs.validate(
            onlyString,
            args("topic" to JsonArray(listOf(JsonPrimitive(1)))),
        ) as ArgsValidation.Failed
        assertTrue(array.errors.single().contains("got an array"))

        val number = WorkflowArgs.validate(
            onlyString,
            args("topic" to JsonPrimitive(1)),
        ) as ArgsValidation.Failed
        assertTrue(number.errors.single().contains("got a number"))
    }
}
