package com.andmx.agent.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedWorkflowStoreTest {

    private class FakeFs : WorkflowFileSystem {
        val files = LinkedHashMap<String, String>()

        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun isDirectory(path: String): Boolean =
            files.keys.any { it.startsWith("$path/") } || dirs.contains(path)

        private val dirs = mutableSetOf<String>()

        override fun readText(path: String): String =
            files[path] ?: throw NoSuchFileException("no such file: $path")

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

    private class NoSuchFileException(message: String) : Exception(message)

    private val cwd = "/root/project"
    private val projectDir = "$cwd/.zcode/workflows"
    private val globalDir = "/root/.zcode/workflows"

    private fun store(fs: FakeFs) = SavedWorkflowStore(fs, { cwd })

    private fun definition(description: String, script: String = "return 1\n"): String =
        WorkflowScriptFrontmatter.serialize(
            WorkflowScriptFrontmatter.Meta(
                description = description,
                args = mapOf("pr" to WorkflowScriptFrontmatter.ArgSpec("string", required = true)),
            ),
            script,
        )

    @Test
    fun listsProjectDefinitions() {
        val fs = FakeFs()
        fs.writeText("$projectDir/review.dwf.ts", definition("Review a PR"))
        val result = store(fs).list()
        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals("review", entry.name)
        assertEquals("Review a PR", entry.description)
        assertEquals(SavedWorkflowStore.Scope.PROJECT, entry.scope)
        assertTrue(entry.args.containsKey("pr"))
        assertTrue(result.invalid.isEmpty())
    }

    @Test
    fun ignoresFilesWithForeignExtension() {
        val fs = FakeFs()
        fs.writeText("$projectDir/notes.md", "hello")
        fs.writeText("$projectDir/real.dwf.ts", definition("Real"))
        val result = store(fs).list()
        assertEquals(listOf("real"), result.entries.map { it.name })
    }

    @Test
    fun badFileIsReportedWithoutBreakingTheList() {
        val fs = FakeFs()
        fs.writeText("$projectDir/good.dwf.ts", definition("Good"))
        fs.writeText("$projectDir/broken.dwf.ts", "description: no sentinel\n")
        fs.writeText("$projectDir/bad name.dwf.ts", definition("Bad name"))
        val result = store(fs).list()
        assertEquals(listOf("good"), result.entries.map { it.name })
        assertEquals(2, result.invalid.size)
        assertTrue(result.invalid.any { it.path.endsWith("broken.dwf.ts") })
        assertTrue(result.invalid.any { it.reason.contains("not a usable workflow name") })
    }

    @Test
    fun projectShadowsGlobalInTheDefaultListing() {
        val fs = FakeFs()
        fs.writeText("$projectDir/deploy.dwf.ts", definition("Project deploy"))
        fs.writeText("$globalDir/deploy.dwf.ts", definition("Global deploy"))
        fs.writeText("$globalDir/other.dwf.ts", definition("Global other"))

        val both = store(fs).list()
        assertEquals(listOf("deploy", "other"), both.entries.map { it.name }.sorted())
        assertEquals(
            SavedWorkflowStore.Scope.PROJECT,
            both.entries.first { it.name == "deploy" }.scope,
        )

        val globalOnly = store(fs).list(SavedWorkflowStore.Scope.GLOBAL)
        assertEquals(2, globalOnly.entries.size)
        assertEquals("Global deploy", globalOnly.entries.first { it.name == "deploy" }.description)
    }

    @Test
    fun resolvePrefersProjectThenGlobal() {
        val fs = FakeFs()
        fs.writeText("$globalDir/deploy.dwf.ts", definition("Global deploy"))
        assertEquals(
            "Global deploy",
            (store(fs).resolve("deploy") as SavedWorkflowStore.ResolveResult.Ok).workflow.meta.description,
        )
        fs.writeText("$projectDir/deploy.dwf.ts", definition("Project deploy"))
        assertEquals(
            "Project deploy",
            (store(fs).resolve("deploy") as SavedWorkflowStore.ResolveResult.Ok).workflow.meta.description,
        )
    }

    @Test
    fun resolveRejectsPathTraversal() {
        val fs = FakeFs()
        fs.writeText("/root/.ssh/id_rsa", "secret")
        val result = store(fs).resolve("../../.ssh/id_rsa")
        val failure = result as SavedWorkflowStore.ResolveResult.Failure
        assertEquals(SavedWorkflowStore.FailureReason.INVALID_NAME, failure.reason)
    }

    @Test
    fun resolveReportsNotFoundAndParseErrorSeparately() {
        val fs = FakeFs()
        val missing = store(fs).resolve("nope") as SavedWorkflowStore.ResolveResult.Failure
        assertEquals(SavedWorkflowStore.FailureReason.NOT_FOUND, missing.reason)

        fs.writeText("$projectDir/broken.dwf.ts", "not a workflow file\n")
        val broken = store(fs).resolve("broken") as SavedWorkflowStore.ResolveResult.Failure
        assertEquals(SavedWorkflowStore.FailureReason.PARSE_ERROR, broken.reason)
        assertTrue(broken.path!!.endsWith("broken.dwf.ts"))
    }

    @Test
    fun savesAndReadsBackTheSameBytes() {
        val fs = FakeFs()
        val meta = WorkflowScriptFrontmatter.Meta(
            description = "Ship it",
            whenToUse = "When release day comes",
            args = mapOf(
                "target" to WorkflowScriptFrontmatter.ArgSpec("string", required = true, description = "env"),
            ),
        )
        val script = "const a = 1\nreturn a\n"
        val saved = store(fs).save("ship", meta, script)
        assertEquals("$projectDir/ship.dwf.ts", saved.path)
        assertEquals(SavedWorkflowStore.Scope.PROJECT, saved.scope)
        assertFalse(saved.overwritten)

        val resolved = (store(fs).resolve("ship") as SavedWorkflowStore.ResolveResult.Ok).workflow
        assertEquals(meta, resolved.meta)
        assertEquals(script, resolved.script)
        assertEquals(fs.files["$projectDir/ship.dwf.ts"], resolved.source)

        assertTrue(store(fs).save("ship", meta, script).overwritten)
    }

    @Test
    fun reportsShadowingBothWays() {
        val fs = FakeFs()
        val meta = WorkflowScriptFrontmatter.Meta(description = "x")
        store(fs).save("dual", meta, "return 0\n", SavedWorkflowStore.Scope.GLOBAL)
        assertEquals(
            SavedWorkflowStore.Shadowing.HIDES_GLOBAL,
            store(fs).shadowing("dual", SavedWorkflowStore.Scope.PROJECT),
        )

        store(fs).save("proj-only", meta, "return 0\n", SavedWorkflowStore.Scope.PROJECT)
        assertEquals(
            SavedWorkflowStore.Shadowing.HIDDEN_BY_PROJECT,
            store(fs).shadowing("proj-only", SavedWorkflowStore.Scope.GLOBAL),
        )
        assertNull(store(fs).shadowing("absent", SavedWorkflowStore.Scope.PROJECT))
    }

    @Test
    fun missingDirectoryIsNotAnError() {
        val result = store(FakeFs()).list()
        assertTrue(result.entries.isEmpty())
        assertTrue(result.invalid.isEmpty())
    }
}
