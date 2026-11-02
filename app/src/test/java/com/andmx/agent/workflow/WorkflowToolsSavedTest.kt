package com.andmx.agent.workflow

import android.app.Application
import com.andmx.agent.zcode.ARG_DECLARATION_ERROR
import com.andmx.agent.zcode.SAVE_WORKFLOW_SOURCE_ERROR
import com.andmx.agent.zcode.SAVE_WORKFLOW_TOOL_NAME
import com.andmx.agent.zcode.LIST_SAVED_WORKFLOWS_TOOL_NAME
import com.andmx.agent.zcode.WorkflowTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorkflowToolsSavedTest {

    private class FakeFs : WorkflowFileSystem {
        val files = LinkedHashMap<String, String>()
        private val dirs = mutableSetOf<String>()

        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun isDirectory(path: String): Boolean =
            dirs.contains(path) || files.keys.any { it.startsWith("$path/") }

        override fun readText(path: String): String =
            files[path] ?: throw IllegalStateException("no such file: $path")

        override fun writeText(path: String, content: String) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                dirs += parent
                parent = parent.substringBeforeLast('/', "")
            }
            files[path] = content
        }

        override fun list(dir: String): List<String> {
            val prefix = "$dir/"
            return files.keys
                .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map { it.removePrefix(prefix) }
        }
    }

    private val cwd = "/root/project"
    private val projectDir = "$cwd/.zcode/workflows"

    private fun tools(fs: FakeFs, reader: suspend (String) -> String? = { null }): WorkflowTools =
        WorkflowTools(
            savedWorkflows = SavedWorkflowStore(fs, { cwd }),
            readText = reader,
        )

    private fun saveArgs(
        name: String = "review",
        description: String = "Review a pull request",
        scope: String = "project",
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("scope", scope)
        put("script", "return 1\n")
        block()
    }

    @Test
    fun savesInlineScriptAndListsItBack() = runTest {
        val fs = FakeFs()
        val save = tools(fs).Save().execute(saveArgs())
        assertFalse(save.isError)
        val stored = fs.files.getValue("$projectDir/review.dwf.ts")
        val parsed = WorkflowScriptFrontmatter.parse(stored) as WorkflowScriptFrontmatter.Result.Ok
        assertEquals("Review a pull request", parsed.meta.description)
        assertEquals("return 1\n", parsed.script)

        val listed = tools(fs).ListSaved().execute(buildJsonObject { })
        assertFalse(listed.isError)
        assertTrue(listed.output.contains("review"))
        assertTrue(listed.output.contains("Review a pull request"))
    }

    @Test
    fun recordsArgumentDeclarations() = runTest {
        val fs = FakeFs()
        val result = tools(fs).Save().execute(
            saveArgs {
                putJsonObject("args") {
                    putJsonObject("pr") {
                        put("type", "string")
                        put("required", true)
                        put("description", "pull request")
                    }
                }
            },
        )
        assertFalse(result.isError)
        val listed = tools(fs).ListSaved().execute(buildJsonObject { })
        assertTrue(listed.output.contains("pr (string required)"))
    }

    @Test
    fun rejectsBadArgumentDeclaration() = runTest {
        val fs = FakeFs()
        val result = tools(fs).Save().execute(
            saveArgs {
                putJsonObject("args") {
                    putJsonObject("pr") { put("type", "integer") }
                }
            },
        )
        assertTrue(result.isError)
        assertEquals(ARG_DECLARATION_ERROR, result.output)
    }

    @Test
    fun rejectsAmbiguousScriptSources() = runTest {
        val fs = FakeFs()
        val both = tools(fs).Save().execute(saveArgs { put("script_path", "/tmp/x.ts") })
        assertTrue(both.isError)
        assertEquals(SAVE_WORKFLOW_SOURCE_ERROR, both.output)

        val neither = tools(fs).Save().execute(
            buildJsonObject {
                put("name", "x")
                put("description", "y")
                put("scope", "project")
            },
        )
        assertTrue(neither.isError)
        assertEquals(SAVE_WORKFLOW_SOURCE_ERROR, neither.output)
    }

    @Test
    fun rejectsUnknownScope() = runTest {
        val fs = FakeFs()
        val result = tools(fs).Save().execute(saveArgs(scope = "workspace"))
        assertTrue(result.isError)
        assertTrue(result.output.contains("project"))
    }

    @Test
    fun refusesToOverwriteWithoutTheFlag() = runTest {
        val fs = FakeFs()
        tools(fs).Save().execute(saveArgs())
        val second = tools(fs).Save().execute(saveArgs(description = "different"))
        assertTrue(second.isError)
        assertTrue(second.output.contains("overwrite: true"))

        val third = tools(fs).Save().execute(saveArgs(description = "different") { put("overwrite", true) })
        assertFalse(third.isError)
        assertTrue(third.output.contains("replaced"))
    }

    @Test
    fun readsScriptFromAPath() = runTest {
        val fs = FakeFs()
        val result = tools(fs, reader = { path -> if (path == "/root/draft.ts") "return 42\n" else null })
            .Save()
            .execute(
                buildJsonObject {
                    put("name", "from-path")
                    put("description", "Loaded from a draft")
                    put("scope", "global")
                    put("script_path", "/root/draft.ts")
                },
            )
        assertFalse(result.isError)
        val stored = fs.files.getValue("/root/.zcode/workflows/from-path.dwf.ts")
        val parsed = WorkflowScriptFrontmatter.parse(stored) as WorkflowScriptFrontmatter.Result.Ok
        assertEquals("return 42\n", parsed.script)
    }

    @Test
    fun unreadableScriptPathIsReported() = runTest {
        val fs = FakeFs()
        val result = tools(fs).Save().execute(
            buildJsonObject {
                put("name", "from-path")
                put("description", "x")
                put("scope", "project")
                put("script_path", "/root/missing.ts")
            },
        )
        assertTrue(result.isError)
        assertTrue(result.output.contains("/root/missing.ts"))
    }

    @Test
    fun listReportsUnusableFilesSeparately() = runTest {
        val fs = FakeFs()
        fs.writeText("$projectDir/broken.dwf.ts", "no sentinel here\n")
        val listed = tools(fs).ListSaved().execute(buildJsonObject { })
        assertFalse(listed.isError)
        assertTrue(listed.output.contains("Unusable files"))
        assertTrue(listed.output.contains("broken.dwf.ts"))
    }

    @Test
    fun toolNamesAreStillTheUpstreamOnes() {
        assertEquals("SaveWorkflow", SAVE_WORKFLOW_TOOL_NAME)
        assertEquals("ListSavedWorkflows", LIST_SAVED_WORKFLOWS_TOOL_NAME)
    }
}
