package com.andmx.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.agent.zcode.buildZCodeToolSurface
import com.andmx.exec.policy.NetworkPolicy
import com.andmx.workspace.WorkspaceAccess
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GrepGlobToolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun surface(legacy: Boolean = true): List<Tool> = buildZCodeToolSurface(
        context = context,
        networkPolicy = NetworkPolicy(),
        planTool = UpdatePlanTool(),
        goalState = GoalToolState(),
        todo = TodoState(),
        planMode = PlanModeState(),
        cwdProvider = { "/root" },
        includeLegacyAliases = legacy,
    )

    @Test
    fun schemaCapabilitiesAndSingleRegistration() {
        val legacy = surface(legacy = true)
        val direct = surface(legacy = false)
        for (tools in listOf(legacy, direct)) {
            assertEquals(1, tools.count { it.name == "Grep" })
            assertEquals(1, tools.count { it.name == "Glob" })
            assertTrue(tools.none { it.name == "grep" || it.name == "glob" })
            assertEquals(1, tools.count { it.name == "WebFetch" })
            assertEquals(1, tools.count { it.name == "WebSearch" })
            assertTrue(tools.any { it.name == "Bash" })
        }
        assertTrue(legacy.any { it.name == "browse" })
        assertTrue(legacy.any { it.name == "web_search" })
        assertTrue(direct.none { it.name == "browse" || it.name == "web_search" })

        val grep = legacy.single { it.name == "Grep" }
        val glob = legacy.single { it.name == "Glob" }
        assertEquals(listOf("pattern"), required(grep))
        assertEquals(listOf("pattern"), required(glob))
        assertEquals(
            setOf(
                "pattern", "path", "glob", "output_mode", "-B", "-A", "-C", "context",
                "-n", "-i", "-o", "type", "head_limit", "offset", "multiline",
            ),
            props(grep).keys,
        )
        assertEquals(setOf("pattern", "path"), props(glob).keys)
        assertFalse(props(grep).containsKey("case_insensitive"))
        assertFalse(props(grep).containsKey("max_results"))
        assertEquals(false, grep.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, glob.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(
            "The regular expression pattern to search for in file contents",
            props(grep)["pattern"]!!.jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob",
            props(grep)["glob"]!!.jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertTrue(props(grep)["path"]!!.jsonObject.getValue("description").jsonPrimitive.content.contains("current working directory"))
        assertTrue(props(glob)["path"]!!.jsonObject.getValue("description").jsonPrimitive.content.contains("current working directory"))
        assertFalse(props(grep)["output_mode"]!!.jsonObject.getValue("description").jsonPrimitive.content.contains("Defaults to \"content\""))
        assertEquals(
            listOf("content", "files_with_matches", "count"),
            props(grep)["output_mode"]!!.jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("number", props(grep)["head_limit"]!!.jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("boolean", props(grep)["-i"]!!.jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "The glob pattern to match files against",
            props(glob)["pattern"]!!.jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertTrue(grep.description.startsWith("Content search built on ripgrep."))
        assertTrue(grep.description.contains("files_with_matches"))
        assertTrue(grep.description.contains("interface\\{\\}"))
        assertEquals(
            "Fast file pattern matching. Supports glob patterns like \"**/*.js\" or \"src/**/*.ts\". Returns matching file paths sorted by modification time.",
            glob.description,
        )
        for (tool in listOf(grep, glob)) {
            assertTrue(tool.readOnly)
            assertFalse(tool.destructive)
            assertTrue(tool.concurrentSafe)
            assertFalse(tool.needsApproval)
            assertEquals("read", tool.permissionName)
            assertEquals("none", tool.sideEffectScope)
            assertEquals("low", tool.riskLevel)
            assertEquals(30_000L, tool.timeoutMs)
        }
        assertEquals(20_000, grep.maxOutputBytes)
        assertEquals(100_000, glob.maxOutputBytes)

        val bash = legacy.single { it.name == "Bash" }
        assertTrue(
            bash.description.contains(
                "Avoid using this tool to run `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands",
            ),
        )
    }

    @Test
    fun defaultOutputModeAndEmptyText() = runTest {
        val dir = "/root/grep-empty-${System.nanoTime()}"
        val access = WorkspaceAccess(context)
        val grep = surface().single { it.name == "Grep" }
        val glob = surface().single { it.name == "Glob" }
        try {
            access.writeText("$dir/note.txt", "hello\n")
            val missed = grep.execute(buildJsonObject {
                put("pattern", "missing")
                put("path", dir)
            })
            assertFalse(missed.isError)
            assertEquals("No files found", missed.output)
            val content = grep.execute(buildJsonObject {
                put("pattern", "missing")
                put("path", dir)
                put("output_mode", "content")
            })
            assertEquals("No matches found", content.output)
            val count = grep.execute(buildJsonObject {
                put("pattern", "missing")
                put("path", dir)
                put("output_mode", "count")
            })
            assertEquals("No matches found\n\nFound 0 total occurrences across 0 files.", count.output)
            val hit = grep.execute(buildJsonObject {
                put("pattern", "hello")
                put("path", dir)
            })
            assertEquals("Found 1 file\n${display(dir, "note.txt")}", hit.output)
            val emptyGlob = glob.execute(buildJsonObject {
                put("pattern", "no-such-*.never")
                put("path", dir)
            })
            assertEquals("No files found", emptyGlob.output)
            val blankGlob = glob.execute(buildJsonObject {
                put("pattern", "   ")
                put("path", dir)
            })
            assertTrue(blankGlob.isError)
            assertEquals("Glob pattern must not be empty", blankGlob.output)
        } finally {
            access.hostFile(dir)?.deleteRecursively()
        }
    }

    @Test
    fun acceptsUpstreamArgumentNamesAndGlobTruncation() = runTest {
        val dir = "/root/grep-args-${System.nanoTime()}"
        val access = WorkspaceAccess(context)
        val grep = surface().single { it.name == "Grep" }
        val glob = surface().single { it.name == "Glob" }
        try {
            access.writeText("$dir/a.txt", "keep\nAlpha\nafter\n")
            access.writeText("$dir/b.kt", "Alpha\nbeta\n")
            access.writeText("$dir/span.txt", "foo\nbar\n")
            access.writeText("$dir/count.txt", "aa\naa\n")
            access.hostFile("$dir/a.txt")!!.setLastModified(1_000)
            access.hostFile("$dir/b.kt")!!.setLastModified(5_000)
            access.hostFile("$dir/span.txt")!!.setLastModified(2_000)
            access.hostFile("$dir/count.txt")!!.setLastModified(500)
            val ignored = grep.execute(buildJsonObject {
                put("pattern", "alpha")
                put("path", dir)
            })
            assertEquals("No files found", ignored.output)
            val insensitive = grep.execute(buildJsonObject {
                put("pattern", "alpha")
                put("path", dir)
                put("-i", true)
                put("output_mode", "files_with_matches")
            })
            assertEquals(
                "Found 2 files\n${display(dir, "b.kt")}\n${display(dir, "a.txt")}",
                insensitive.output,
            )
            val limited = grep.execute(buildJsonObject {
                put("pattern", "alpha")
                put("path", dir)
                put("-i", true)
                put("head_limit", 1)
            })
            assertEquals(
                "Found 1 file limit: 1\n${display(dir, "b.kt")}",
                limited.output,
            )
            val typed = grep.execute(buildJsonObject {
                put("pattern", "Alpha")
                put("path", dir)
                put("type", "kt")
            })
            assertEquals("Found 1 file\n${display(dir, "b.kt")}", typed.output)
            val after = grep.execute(buildJsonObject {
                put("pattern", "Alpha")
                put("path", "$dir/a.txt")
                put("output_mode", "content")
                put("-A", 1)
            })
            assertEquals(
                "${display(dir, "a.txt")}:2:Alpha\n${display(dir, "a.txt")}:3:after",
                after.output,
            )
            val before = grep.execute(buildJsonObject {
                put("pattern", "Alpha")
                put("path", "$dir/a.txt")
                put("output_mode", "content")
                put("-B", 1)
                put("-n", false)
            })
            assertEquals(
                "${display(dir, "a.txt")}:keep\n${display(dir, "a.txt")}:Alpha",
                before.output,
            )
            val around = grep.execute(buildJsonObject {
                put("pattern", "Alpha")
                put("path", "$dir/a.txt")
                put("output_mode", "content")
                put("-C", 1)
            })
            assertEquals(
                "${display(dir, "a.txt")}:1:keep\n${display(dir, "a.txt")}:2:Alpha\n${display(dir, "a.txt")}:3:after",
                around.output,
            )
            val counted = grep.execute(buildJsonObject {
                put("pattern", "aa")
                put("path", "$dir/count.txt")
                put("output_mode", "count")
            })
            assertEquals(
                "${display(dir, "count.txt")}:2\n\nFound 2 total occurrences across 1 file.",
                counted.output,
            )
            val spanned = grep.execute(buildJsonObject {
                put("pattern", "foo\nbar")
                put("path", "$dir/span.txt")
                put("output_mode", "content")
                put("multiline", true)
            })
            assertEquals("${display(dir, "span.txt")}:1:foo", spanned.output)
            val plain = glob.execute(buildJsonObject {
                put("pattern", "*.txt")
                put("path", dir)
            })
            assertEquals(
                "${display(dir, "span.txt")}\n${display(dir, "a.txt")}\n${display(dir, "count.txt")}",
                plain.output,
            )
            for (i in 0 until 101) {
                val name = "n%03d.txt".format(i)
                access.writeText("$dir/many/$name", "x")
                access.hostFile("$dir/many/$name")!!.setLastModified(10_000L + i)
            }
            val truncated = glob.execute(buildJsonObject {
                put("pattern", "*.txt")
                put("path", "$dir/many")
            })
            val lines = truncated.output.lines()
            assertEquals(101, lines.size)
            assertEquals(display("$dir/many", "n100.txt"), lines.first())
            assertEquals(
                "(Results are truncated. Consider using a more specific path or pattern.)",
                lines.last(),
            )
        } finally {
            access.hostFile(dir)?.deleteRecursively()
        }
    }

    @Test
    fun symlinksAreNotFollowed() = runTest {
        val dir = "/root/grep-link-${System.nanoTime()}"
        val access = WorkspaceAccess(context)
        val grep = surface().single { it.name == "Grep" }
        val glob = surface().single { it.name == "Glob" }
        try {
            access.writeText("$dir/real/hit.txt", "NEEDLE\n")
            val realDir = access.hostFile("$dir/real")!!.toPath()
            val realFile = access.hostFile("$dir/real/hit.txt")!!.toPath()
            Files.createSymbolicLink(access.hostFile("$dir/link")!!.toPath(), realDir)
            Files.createSymbolicLink(access.hostFile("$dir/alias.txt")!!.toPath(), realFile)
            val listed = glob.execute(buildJsonObject {
                put("pattern", "**/*.txt")
                put("path", dir)
            })
            assertEquals(display(dir, "real/hit.txt"), listed.output)
            val found = grep.execute(buildJsonObject {
                put("pattern", "NEEDLE")
                put("path", dir)
            })
            assertEquals("Found 1 file\n${display(dir, "real/hit.txt")}", found.output)
        } finally {
            access.hostFile("$dir/link")?.delete()
            access.hostFile("$dir/alias.txt")?.delete()
            access.hostFile(dir)?.deleteRecursively()
        }
    }

    private fun display(dir: String, name: String): String {
        val cwd = "/root"
        val path = "$dir/$name"
        return if (path.startsWith("$cwd/")) path.removePrefix("$cwd/") else path
    }

    private fun props(tool: Tool): JsonObject = tool.parameters["properties"]!!.jsonObject

    private fun required(tool: Tool): List<String> =
        tool.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }
}
