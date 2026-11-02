package com.andmx.agent.zcode

import com.andmx.agent.workflow.WorkflowScriptNotes
import com.andmx.agent.workflow.script.ActorSite
import com.andmx.agent.workflow.script.CompileDiagnostic
import com.andmx.agent.workflow.script.CompileOutcome
import com.andmx.agent.workflow.script.DeclaredArtifact
import com.andmx.agent.workflow.script.ModelReference
import com.andmx.agent.workflow.script.ScriptLoc
import com.andmx.agent.workflow.script.SiteGraph
import com.andmx.agent.workflow.script.SiteNode
import com.andmx.agent.workflow.script.WorkflowAnalysis
import com.andmx.agent.workflow.script.WorkflowScriptAnalyzer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CreateWorkflow` 的接线：三源归一化 → 静态分析 → 按上游文案回报。
 *
 * 这里用假分析器，真分析器由 `tools/dwf-analyzer/verify.mjs` 与金标样本单测各自把关——
 * 单元测试要钉的是**接线是否按上游的话说**，而不是再验一遍 TypeScript。
 */
class CreateWorkflowToolTest {

    private class FakeAnalyzer(private val result: WorkflowAnalysis) : WorkflowScriptAnalyzer {
        var lastScript: String? = null
            private set

        override suspend fun analyze(script: String): WorkflowAnalysis {
            lastScript = script
            return result
        }

        override suspend fun compile(script: String): CompileOutcome =
            CompileOutcome(result.ok, result.diagnostics)
    }

    private val okAnalysis = WorkflowAnalysis(
        ok = true,
        diagnostics = emptyList(),
        declaredArtifacts = listOf(DeclaredArtifact("report", "markdown")),
        modelReferences = listOf(ModelReference("claude-haiku-4", 4, 20)),
        siteGraph = SiteGraph(
            nodes = listOf(
                SiteNode("source", "source", null, "source", null, emptyList(), null, null),
                SiteNode("ask#1", "ask", ScriptLoc(9, 32), "reviewer", null, listOf("actor#1"), null, "string"),
            ),
            actors = listOf(ActorSite("actor#1", ScriptLoc(5, 20), "评审甲", null, null)),
            edges = emptyList(),
        ),
        raw = JsonObject(emptyMap()),
    )

    private val failingAnalysis = WorkflowAnalysis(
        ok = false,
        diagnostics = listOf(
            CompileDiagnostic(2322, 6, 9, "Type 'string' is not assignable to type 'number'."),
            CompileDiagnostic(2304, 9, 10, "Cannot find name 'undeclaredThing'."),
        ),
        declaredArtifacts = emptyList(),
        modelReferences = emptyList(),
        siteGraph = null,
        raw = JsonObject(emptyMap()),
    )

    private fun createTool(
        analyzer: WorkflowScriptAnalyzer?,
        readable: Map<String, String> = emptyMap(),
    ) = WorkflowTools(
        analyzer = analyzer,
        readText = { path -> readable[path] },
        cwd = { "/root/project" },
    ).all().first { it.name == CREATE_WORKFLOW_TOOL_NAME }

    private fun inlineArgs(script: String) = buildJsonObject { put("script", script) }

    @Test
    fun `编不过时按正文行报诊断并明说没有执行`() = runTest {
        val result = createTool(FakeAnalyzer(failingAnalysis)).execute(inlineArgs("const a = 1;"))

        assertTrue(result.isError)
        assertTrue(result.output.startsWith("The workflow script has errors:"))
        assertTrue(result.output.contains("L6:C9 Type 'string' is not assignable to type 'number'."))
        assertTrue(result.output.contains("L9:C10 Cannot find name 'undeclaredThing'."))
        assertTrue(result.output.contains(WorkflowScriptNotes.DIAGNOSTICS_NOT_EXECUTED))
    }

    @Test
    fun `编得过时交出分析事实并明说本会话跑不了`() = runTest {
        val result = createTool(FakeAnalyzer(okAnalysis)).execute(inlineArgs("const a = 1;"))

        assertFalse(result.isError)
        assertTrue(result.output.contains("The workflow script compiles: no diagnostics."))
        assertTrue(result.output.contains("Source: inline script."))
        assertTrue(result.output.contains("Artifacts declared: report (markdown)"))
        assertTrue(result.output.contains("Models named by the script: claude-haiku-4"))
        assertTrue(result.output.contains("Sites: 2 node(s) across 1 actor(s)."))
        assertTrue(result.output.contains(WorkflowScriptNotes.EXECUTION_UNAVAILABLE))
    }

    @Test
    fun `分析器收到的就是归一化之后的脚本`() = runTest {
        val analyzer = FakeAnalyzer(okAnalysis)
        val script = "const a = agent('x');\nreturn 1;\n"
        createTool(analyzer).execute(inlineArgs(script))

        assertEquals(script, analyzer.lastScript)
    }

    @Test
    fun `来源缺失时回上游那句来源错误`() = runTest {
        val result = createTool(FakeAnalyzer(okAnalysis)).execute(buildJsonObject { })

        assertTrue(result.isError)
        assertEquals(CREATE_WORKFLOW_SOURCE_ERROR, result.output)
    }

    @Test
    fun `没有分析器时拒绝执行而不是放行`() = runTest {
        val result = createTool(analyzer = null).execute(inlineArgs("const a = 1;"))

        // 失败关闭：编不过的检查不能因为少了分析器就变成「默认通过」。
        assertTrue(result.isError)
        assertTrue(result.output.contains("no workflow script analyzer"))
        assertTrue(result.output.contains("Nothing was executed"))
    }

    @Test
    fun `分析器不可用时也拒绝执行`() = runTest {
        val broken = object : WorkflowScriptAnalyzer {
            override suspend fun analyze(script: String): WorkflowAnalysis =
                throw com.andmx.agent.workflow.script.WorkflowAnalyzerUnavailableException("WebView 未就绪")

            override suspend fun compile(script: String): CompileOutcome =
                throw com.andmx.agent.workflow.script.WorkflowAnalyzerUnavailableException("WebView 未就绪")
        }

        val result = createTool(broken).execute(inlineArgs("const a = 1;"))

        assertTrue(result.isError)
        assertTrue(result.output.contains("could not be typechecked"))
        assertTrue(result.output.contains("WebView 未就绪"))
    }

    @Test
    fun `path 来源的诊断带文件名 行号按文件数`() = runTest {
        val absolute = "/root/project/draft.dwf.ts"
        val source = "/* zcode-workflow\ndescription: x\n*/\nconst a = 1;\n"
        val analyzer = FakeAnalyzer(
            failingAnalysis.copy(diagnostics = listOf(CompileDiagnostic(2322, 1, 9, "读错了"))),
        )

        val result = createTool(analyzer, mapOf(absolute to source)).execute(
            buildJsonObject { put("path", "draft.dwf.ts") },
        )

        assertTrue(result.isError)
        // 元数据块占 3 行，正文第 1 行就是文件第 4 行。
        assertTrue(result.output.contains("draft.dwf.ts:L4:C9 读错了"))
        assertTrue(result.output.contains("The script file is draft.dwf.ts."))
        assertTrue(result.output.contains("do not paste the script inline again"))
    }

    @Test
    fun `saved 来源编不过时点名保存的文件`() = runTest {
        val store = com.andmx.agent.workflow.SavedWorkflowStore(
            com.andmx.agent.workflow.InMemoryWorkflowFileSystem(
                mapOf(
                    "/root/project/.zcode/workflows/review.dwf.ts" to
                        "/* zcode-workflow\ndescription: 评审\n*/\nconst a = 1;\n",
                ),
            ),
            cwd = { "/root/project" },
        )
        val tools = WorkflowTools(
            analyzer = FakeAnalyzer(failingAnalysis),
            savedWorkflows = store,
            cwd = { "/root/project" },
        ).all().first { it.name == CREATE_WORKFLOW_TOOL_NAME }

        val result = tools.execute(
            buildJsonObject { put("saved", buildJsonObject { put("name", "review") }) },
        )

        assertTrue(result.isError)
        assertTrue(
            result.output.startsWith(
                "The saved workflow 'review' (/root/project/.zcode/workflows/review.dwf.ts) has errors:",
            ),
        )
        assertTrue(result.output.contains(WorkflowScriptNotes.SAVED_FILE_NEEDS_FIXING))
    }

    @Test
    fun `脚本正文里的尾部换行不会被吃掉`() = runTest {
        val analyzer = FakeAnalyzer(okAnalysis)
        val script = "log('a');\n\n"
        createTool(analyzer).execute(buildJsonObject { put("script", JsonPrimitive(script)) })

        assertEquals(script, analyzer.lastScript)
    }
}
