package com.andmx.agent.workflow

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三源归一化对着上游 `create-workflow-source.ts` 的语义做断言。
 *
 * 这个模块的失败文案是产品的一部分：模型读到的每一句话都要把它推向同一个下一步——去改那个
 * 文件，或者换一个能解出来的名字。所以断言不是「有没有报错」，而是「报的是不是那句」。
 */
class CreateWorkflowSourceTest {

    private val cwd = "/root/project"

    private val savedBlock = """
        /* zcode-workflow
        description: 双人评审
        whenToUse: 需要交叉核对时
        args:
          topic:
            type: string
            required: true
          rounds:
            type: number
            default: 2
        */
        const reviewer = agent('评审甲');
        log('开始');
        return await reviewer.ask('评审 ' + args.topic);
    """.trimIndent()

    private fun storeWith(vararg files: Pair<String, String>): SavedWorkflowStore =
        SavedWorkflowStore(InMemoryWorkflowFileSystem(files.toMap()), cwd = { cwd })

    private fun source(
        store: SavedWorkflowStore? = null,
        readable: Map<String, String> = emptyMap(),
        catalog: CreateWorkflowSource.SubagentModelCatalog? = null,
    ) = CreateWorkflowSource(
        store = store,
        readText = { path -> readable[path] },
        cwd = { cwd },
        catalog = catalog,
    )

    private fun json(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

    // —— 来源 XOR ——

    @Test
    fun `三个来源都不给是错`() {
        val source = source()
        assertEquals(
            com.andmx.agent.zcode.CREATE_WORKFLOW_SOURCE_ERROR,
            source.validateSource(json()),
        )
    }

    @Test
    fun `两个来源同时给是错`() {
        val source = source()
        assertEquals(
            com.andmx.agent.zcode.CREATE_WORKFLOW_SOURCE_ERROR,
            source.validateSource(json("script" to JsonPrimitive("x"), "path" to JsonPrimitive("a.ts"))),
        )
    }

    @Test
    fun `args 只跟 path 走`() {
        val source = source()
        assertEquals(
            com.andmx.agent.zcode.CREATE_WORKFLOW_ARGS_WITHOUT_PATH_ERROR,
            source.validateSource(
                json("script" to JsonPrimitive("x"), "args" to buildJsonObject { put("a", 1) }),
            ),
        )
        assertNull(
            source.validateSource(
                json("path" to JsonPrimitive("a.ts"), "args" to buildJsonObject { put("a", 1) }),
            ),
        )
    }

    @Test
    fun `单来源通过校验`() {
        val source = source()
        assertNull(source.validateSource(json("script" to JsonPrimitive("x"))))
        assertNull(source.validateSource(json("path" to JsonPrimitive("a.ts"))))
    }

    // —— 内联 ——

    @Test
    fun `内联来源逐字节保留脚本`() = runTest {
        val script = "const a = agent('x');\nreturn await a.ask('go');\n"
        val input = (source().resolve(json("script" to JsonPrimitive(script))) as CreateWorkflowSource.Result.Ok).input

        assertEquals(script, input.script)
        assertTrue(input.isInline)
        assertEquals(0, input.scriptLineOffset)
        assertTrue(input.args.isEmpty())
        assertNull(input.path)
        assertNull(input.saved)
    }

    @Test
    fun `并发下界归一到 1`() = runTest {
        val input = (source().resolve(
            json("script" to JsonPrimitive("x"), "max_concurrency" to JsonPrimitive(0)),
        ) as CreateWorkflowSource.Result.Ok).input

        assertEquals(1, input.maxConcurrency)
    }

    @Test
    fun `并发高于默认值也照收`() = runTest {
        val input = (source().resolve(
            json("script" to JsonPrimitive("x"), "max_concurrency" to JsonPrimitive(40)),
        ) as CreateWorkflowSource.Result.Ok).input

        // 默认并发是起点不是天花板。
        assertEquals(40, input.maxConcurrency)
    }

    @Test
    fun `宿主没有模型目录时点名拒绝 subagent_model`() = runTest {
        val result = source().resolve(
            json("script" to JsonPrimitive("x"), "subagent_model" to JsonPrimitive("claude-x")),
        )

        assertEquals(
            CreateWorkflowSource.Result.Failure(CreateWorkflowSource.SUBAGENT_MODEL_UNAVAILABLE),
            result,
        )
    }

    @Test
    fun `有模型目录时归一成规范串`() = runTest {
        val catalog = CreateWorkflowSource.SubagentModelCatalog {
            CreateWorkflowSource.Canonicalization.Ok("anthropic/$it")
        }
        val input = (source(catalog = catalog).resolve(
            json("script" to JsonPrimitive("x"), "subagent_model" to JsonPrimitive("claude-x")),
        ) as CreateWorkflowSource.Result.Ok).input

        assertEquals("anthropic/claude-x", input.subagentModel)
    }

    @Test
    fun `模型解不出来也不放行`() = runTest {
        val catalog = CreateWorkflowSource.SubagentModelCatalog {
            CreateWorkflowSource.Canonicalization.Failed("The model \"$it\" cannot be used.")
        }
        val result = source(catalog = catalog).resolve(
            json("script" to JsonPrimitive("x"), "subagent_model" to JsonPrimitive("nope")),
        )

        assertEquals(
            CreateWorkflowSource.Result.Failure("The model \"nope\" cannot be used."),
            result,
        )
    }

    @Test
    fun `Amend 的 subagent_model 为 null 时解除继承`() = runTest {
        val catalog = CreateWorkflowSource.SubagentModelCatalog {
            CreateWorkflowSource.Canonicalization.Ok("anthropic/$it")
        }
        val result = source(catalog = catalog)
            .resolveAmendSubagentModel(JsonNull, "openai/gpt-5")

        assertEquals(CreateWorkflowSource.AmendModelResult.Ok(null), result)
    }

    @Test
    fun `Amend 省略 subagent_model 时沿用并重新解析`() = runTest {
        val catalog = CreateWorkflowSource.SubagentModelCatalog {
            CreateWorkflowSource.Canonicalization.Ok("anthropic/canonical-$it")
        }
        val result = source(catalog = catalog)
            .resolveAmendSubagentModel(null, "openai/gpt-5")

        assertEquals(
            CreateWorkflowSource.AmendModelResult.Ok("anthropic/canonical-openai/gpt-5"),
            result,
        )
    }

    @Test
    fun `Amend 沿用的模型失效时标记为继承失败`() = runTest {
        val catalog = CreateWorkflowSource.SubagentModelCatalog {
            CreateWorkflowSource.Canonicalization.Failed("No configured model matches `$it`.")
        }
        val result = source(catalog = catalog)
            .resolveAmendSubagentModel(null, "openai/gpt-5")

        assertEquals(
            CreateWorkflowSource.AmendModelResult.Failure(
                inherited = true,
                message = "No configured model matches `openai/gpt-5`.",
            ),
            result,
        )
    }

    @Test
    fun `Amend 显式指定的模型失效时不算继承失败`() = runTest {
        val catalog = CreateWorkflowSource.SubagentModelCatalog {
            CreateWorkflowSource.Canonicalization.Failed("nope")
        }
        val result = source(catalog = catalog)
            .resolveAmendSubagentModel(JsonPrimitive("openai/gpt-5"), "anthropic/claude-x")

        assertEquals(
            CreateWorkflowSource.AmendModelResult.Failure(inherited = false, message = "nope"),
            result,
        )
    }

    @Test
    fun `宿主没有模型目录时 Amend 原样沿用前驱的模型`() = runTest {
        val result = source().resolveAmendSubagentModel(null, "openai/gpt-5")

        assertEquals(CreateWorkflowSource.AmendModelResult.Ok("openai/gpt-5"), result)
    }

    // —— saved ——

    private fun savedArgs(
        vararg pairs: Pair<String, JsonElement>,
        name: String = "review",
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("args", JsonObject(pairs.toMap()))
    }

    @Test
    fun `saved 来源剥出脚本并补默认值`() = runTest {
        val store = storeWith("/root/project/.zcode/workflows/review.dwf.ts" to savedBlock)
        val input = (source(store).resolve(
            json("saved" to savedArgs("topic" to JsonPrimitive("象棋"))),
        ) as CreateWorkflowSource.Result.Ok).input

        assertTrue(input.script.contains("const reviewer = agent('评审甲');"))
        assertFalse(input.script.contains("zcode-workflow"))
        assertEquals("review", input.saved?.name)
        assertEquals(SavedWorkflowStore.Scope.PROJECT, input.saved?.scope)
        assertEquals(JsonPrimitive("象棋"), input.saved?.args?.get("topic"))
        assertEquals(JsonPrimitive(2), input.saved?.args?.get("rounds"))
        assertTrue(input.scriptLineOffset > 0)
        // saved 的实参住在 saved.args 里；顶层 args 只属于 path 来源。
        assertTrue(input.args.isEmpty())
        // 未指定展示名时取保存的名字。
        assertEquals("review", input.name)
    }

    @Test
    fun `saved 缺必填参数时把声明一起复述`() = runTest {
        val store = storeWith("/root/project/.zcode/workflows/review.dwf.ts" to savedBlock)
        val result = source(store).resolve(json("saved" to savedArgs()))

        val message = (result as CreateWorkflowSource.Result.Failure).message
        assertTrue(message.startsWith("The arguments for saved workflow 'review' are not valid:"))
        assertTrue(message.contains("- missing required argument 'topic'"))
        assertTrue(message.contains("'review' declares:"))
        assertTrue(message.contains("topic (string, required)"))
        assertTrue(message.contains("rounds (number, optional, default 2)"))
    }

    @Test
    fun `saved 找不到时列出实际可用的名字`() = runTest {
        val store = storeWith(
            "/root/project/.zcode/workflows/review.dwf.ts" to savedBlock,
            "/root/.zcode/workflows/global-one.dwf.ts" to savedBlock,
        )
        val result = source(store).resolve(json("saved" to savedArgs(name = "nope")))

        val message = (result as CreateWorkflowSource.Result.Failure).message
        assertTrue(message.contains("No saved workflow named 'nope' in this project or globally."))
        assertTrue(message.contains("review (project)"))
        assertTrue(message.contains("global-one (global)"))
        assertTrue(message.contains("Use ListSavedWorkflows"))
    }

    @Test
    fun `给了 scope 就说该作用域下没有`() = runTest {
        val store = storeWith("/root/project/.zcode/workflows/review.dwf.ts" to savedBlock)
        val result = source(store).resolve(
            json(
                "saved" to buildJsonObject {
                    put("name", "review")
                    put("scope", "global")
                },
            ),
        )

        val message = (result as CreateWorkflowSource.Result.Failure).message
        assertTrue(message.startsWith("No global workflow named 'review'."))
    }

    @Test
    fun `一个保存的定义都没有时的劝告`() = runTest {
        val store = storeWith()
        val result = source(store).resolve(json("saved" to savedArgs(name = "nope")))

        val message = (result as CreateWorkflowSource.Result.Failure).message
        assertTrue(message.contains(CreateWorkflowSource.NO_SAVED_WORKFLOWS))
    }

    // —— path ——

    @Test
    fun `path 来源剥掉元数据块并校验实参`() = runTest {
        val absolute = "/root/project/draft.dwf.ts"
        val input = (source(readable = mapOf(absolute to savedBlock)).resolve(
            json(
                "path" to JsonPrimitive("draft.dwf.ts"),
                "args" to buildJsonObject { put("topic", "象棋") },
            ),
        ) as CreateWorkflowSource.Result.Ok).input

        assertEquals(absolute, input.path)
        assertFalse(input.script.contains("zcode-workflow"))
        assertEquals(JsonPrimitive("象棋"), input.args["topic"])
        assertEquals(JsonPrimitive(2), input.args["rounds"])
        assertTrue(input.scriptLineOffset > 0)
    }

    @Test
    fun `没有元数据块的文件给了 args 就是错`() = runTest {
        val absolute = "/root/project/plain.dwf.ts"
        val result = source(readable = mapOf(absolute to "log('hi');")).resolve(
            json(
                "path" to JsonPrimitive("plain.dwf.ts"),
                "args" to buildJsonObject { put("a", 1) },
            ),
        )

        val message = (result as CreateWorkflowSource.Result.Failure).message
        assertTrue(message.contains("declares no arguments"))
        assertTrue(message.contains("Drop `args`"))
    }

    @Test
    fun `没有元数据块的文件整个就是脚本`() = runTest {
        val absolute = "/root/project/plain.dwf.ts"
        val input = (source(readable = mapOf(absolute to "log('hi');\n")).resolve(
            json("path" to JsonPrimitive("plain.dwf.ts")),
        ) as CreateWorkflowSource.Result.Ok).input

        assertEquals("log('hi');\n", input.script)
        assertEquals(0, input.scriptLineOffset)
        assertTrue(input.args.isEmpty())
    }

    @Test
    fun `读不出来的文件点名文件本身`() = runTest {
        val result = source().resolve(json("path" to JsonPrimitive("missing.dwf.ts")))

        val message = (result as CreateWorkflowSource.Result.Failure).message
        assertTrue(message.startsWith("The workflow script file missing.dwf.ts could not be read."))
        assertTrue(message.contains("submit the script inline"))
    }

    @Test
    fun `元数据块坏掉时说清是块坏了`() = runTest {
        val absolute = "/root/project/broken.dwf.ts"
        val broken = "/* zcode-workflow\ndescription: x\n" + "const a = 1;\n"
        val result = source(readable = mapOf(absolute to broken)).resolve(
            json("path" to JsonPrimitive("broken.dwf.ts")),
        )

        val message = (result as CreateWorkflowSource.Result.Failure).message
        assertTrue(message.contains("metadata block that could not be read"))
        assertTrue(message.contains("Fix the block in that file"))
    }
}
