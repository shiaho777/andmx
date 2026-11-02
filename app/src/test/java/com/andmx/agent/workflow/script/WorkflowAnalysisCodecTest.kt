package com.andmx.agent.workflow.script

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码层对着**上游真实输出**做断言。
 *
 * `app/src/test/resources/dwf/` 下的 `*.json` 不是手写的期望值，是
 * `tools/dwf-analyzer/emit-fixtures.mjs`
 * 用打包好的上游分析器跑真脚本产出的。上游一旦改了字段形状，重跑那个脚本会让这里的断言失败——
 * 这正是我们要的：解码层被上游钉住，而不是被我的记忆钉住。
 */
class WorkflowAnalysisCodecTest {

    private fun fixture(name: String): JsonObject {
        val stream = javaClass.getResourceAsStream("/dwf/$name.json")
            ?: error("缺少金标样本 /dwf/$name.json，先跑 node tools/dwf-analyzer/emit-fixtures.mjs")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun analysis(name: String) = WorkflowAnalysisCodec.decode(fixture(name))

    @Test
    fun `可提交的脚本解出站点图`() {
        val result = analysis("ok-xiangqi")

        assertTrue(result.ok)
        assertTrue(result.diagnostics.isEmpty())
        assertFalse(result.compileFailed)

        val graph = result.siteGraph
        assertNotNull(graph)
        assertEquals(4, graph!!.nodes.size)
        assertEquals(7, graph.edges.size)
        assertEquals(listOf("赤霄", "玄弈"), graph.actors.map { it.name })
    }

    @Test
    fun `虚拟端点的 kind 认得出来`() {
        val graph = analysis("ok-xiangqi").siteGraph!!

        val source = graph.nodes.first { it.id == "source" }
        val sink = graph.nodes.first { it.id == "sink" }
        assertEquals(SiteKind.SOURCE, source.kindOrNull)
        assertEquals(SiteKind.SINK, sink.kindOrNull)
        // 虚拟端点没有位置；有位置的是真实调用站点。
        assertNull(source.loc)
        assertNotNull(graph.nodes.first { it.kind == SiteKind.ASK.wire }.loc)
    }

    @Test
    fun `边保留精确性与产物类型`() {
        val graph = analysis("models").siteGraph!!

        val data = graph.edges.first { it.from == "ask#1" && it.to == "ask#2" }
        assertEquals("data", data.kind)
        assertTrue(data.exact)
        assertEquals("string", data.type)
        assertNull(data.port)
    }

    @Test
    fun `类型错误解出上游诊断码与位置`() {
        val result = analysis("type-errors")

        assertFalse(result.ok)
        assertTrue(result.compileFailed)
        assertEquals(
            listOf(1184, 2322, 2345, 2304),
            result.diagnostics.map { it.code },
        )
        assertEquals(
            listOf(6, 7, 8, 9),
            result.diagnostics.map { it.line },
        )
        assertEquals(
            listOf(1, 9, 15, 10),
            result.diagnostics.map { it.column },
        )
        assertTrue(result.diagnostics.all { it.message.isNotBlank() })
    }

    @Test
    fun `编不过就不给图`() {
        val result = analysis("type-errors")

        assertNull(result.siteGraph)
        assertTrue(result.withholdsGraphs)
        // 但原始 JSON 里照旧有这四个字段，缺省而非缺席，消费点不必分辨「没有」与「没能分析」。
        assertTrue(result.raw.containsKey("declaredArtifacts"))
        assertTrue(result.raw.containsKey("modelReferences"))
    }

    @Test
    fun `模型引用逐字保留`() {
        val result = analysis("models")

        assertEquals(
            listOf("claude-haiku-4", "claude-sonnet-4"),
            result.modelReferences.map { it.name },
        )
        assertEquals(listOf(4, 6), result.modelReferences.map { it.line })
        assertEquals(listOf(20, 54), result.modelReferences.map { it.column })
    }

    @Test
    fun `图的三层投影原样留在 raw 里`() {
        val result = analysis("models")

        // causality / flow / handoff 目前没有 Kotlin 消费方，所以不建类型；
        // 但它们必须在 raw 里一个字节没丢，等看板要做时直接读，不必重跑分析。
        for (key in listOf("causality", "flow", "handoff")) {
            assertTrue("raw 里应当保留 $key", result.raw.containsKey(key))
        }
        val steps = result.raw["causality"]?.jsonObject?.get("steps")?.jsonArray
        assertEquals(2, steps?.size)
    }

    @Test
    fun `空对象解成一份可判定的空结果而不是抛异常`() {
        val empty = WorkflowAnalysisCodec.decode(JsonObject(emptyMap()))

        assertFalse(empty.ok)
        assertTrue(empty.diagnostics.isEmpty())
        assertTrue(empty.declaredArtifacts.isEmpty())
        assertTrue(empty.modelReferences.isEmpty())
        assertNull(empty.siteGraph)
        // 缺 ok 就是不可提交：失败关闭，绝不默认通过。
        assertTrue(empty.compileFailed)
    }

    @Test
    fun `authoring 规则码与 TypeScript 诊断码分得开`() {
        val typeErrors = analysis("type-errors").diagnostics
        assertTrue(typeErrors.none { it.isAuthoringRule })

        val authoring = CompileDiagnostic(WorkflowDiagnosticCode.HOLE, 3, 1, "hole")
        assertTrue(authoring.isAuthoringRule)
        assertEquals(
            WorkflowDiagnosticCode.AUTHORING_RULE_RANGE,
            9001..9012,
        )
        assertFalse(CompileDiagnostic(WorkflowDiagnosticCode.MISPLACED_MODIFIER, 1, 1, "").isAuthoringRule)
    }

    @Test
    fun `fan-out 静态名是唯一不扣下图的那一条`() {
        val onlyFanout = WorkflowAnalysis(
            ok = false,
            diagnostics = listOf(CompileDiagnostic(WorkflowDiagnosticCode.FANOUT_ACTOR_NAME, 9, 3, "")),
            declaredArtifacts = emptyList(),
            modelReferences = emptyList(),
            siteGraph = analysis("models").siteGraph,
            raw = JsonObject(emptyMap()),
        )
        assertFalse(onlyFanout.withholdsGraphs)
        assertNotNull(onlyFanout.siteGraph)

        val withOthers = onlyFanout.copy(
            diagnostics = onlyFanout.diagnostics +
                CompileDiagnostic(WorkflowDiagnosticCode.DUPLICATE_ACTOR_NAME, 4, 1, ""),
        )
        assertTrue(withOthers.withholdsGraphs)
    }

    @Test
    fun `compile 档只解两个字段`() {
        val outcome = WorkflowAnalysisCodec.decodeCompileOutcome(fixture("type-errors"))

        assertFalse(outcome.ok)
        assertEquals(listOf(1184, 2322, 2345, 2304), outcome.diagnostics.map { it.code })
    }

    @Test
    fun `诊断按 行 冒号 列 渲染`() {
        val diagnostic = CompileDiagnostic(2322, 7, 9, "Type 'string' is not assignable to type 'number'.")
        assertEquals("7:9: Type 'string' is not assignable to type 'number'.", diagnostic.render())
    }

    // —— 单槽记忆 ——

    private class CountingAnalyzer : WorkflowScriptAnalyzer {
        var analyzeCalls = 0
        var compileCalls = 0

        override suspend fun analyze(script: String): WorkflowAnalysis {
            analyzeCalls++
            return WorkflowAnalysis.EMPTY.copy(ok = true, diagnostics = emptyList())
        }

        override suspend fun compile(script: String): CompileOutcome {
            compileCalls++
            return CompileOutcome(ok = true, diagnostics = emptyList())
        }
    }

    @Test
    fun `紧邻的重复分析只编一次`() = runTest {
        val inner = CountingAnalyzer()
        val memo = MemoizedWorkflowScriptAnalyzer(inner)

        memo.analyze("const a = 1;")
        memo.analyze("const a = 1;")

        assertEquals(1, inner.analyzeCalls)
    }

    @Test
    fun `单槽只留一格 换脚本就换掉`() = runTest {
        val inner = CountingAnalyzer()
        val memo = MemoizedWorkflowScriptAnalyzer(inner)

        memo.analyze("A")
        memo.analyze("B")
        memo.analyze("A")

        // 刻意不是缓存：留 A、留 B、再回 A 是三趟编译。多留几格就是缓存，
        // 缓存要处理失效，那才是真正的复杂度。
        assertEquals(3, inner.analyzeCalls)
    }

    @Test
    fun `compile 与 analyze 共用同一个槽`() = runTest {
        val inner = CountingAnalyzer()
        val memo = MemoizedWorkflowScriptAnalyzer(inner)

        memo.analyze("S")
        val outcome = memo.compile("S")

        assertTrue(outcome.ok)
        assertEquals(1, inner.analyzeCalls)
        assertEquals(0, inner.compileCalls)
    }
}
