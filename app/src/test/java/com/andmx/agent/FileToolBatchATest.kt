package com.andmx.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.permission.PermissionToolCapability
import com.andmx.agent.permission.bashRuntimeCapability
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.agent.zcode.buildZCodeToolSurface
import com.andmx.exec.policy.NetworkPolicy
import com.andmx.workspace.WorkspaceAccess
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
class FileToolBatchATest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun surface(legacy: Boolean = false): List<Tool> = buildZCodeToolSurface(
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
    fun readWriteEditBashAreRegisteredOnceWithUpstreamSchema() {
        val tools = surface(legacy = true)
        assertEquals(1, tools.count { it.name == "Read" })
        assertEquals(1, tools.count { it.name == "Write" })
        assertEquals(1, tools.count { it.name == "Edit" })
        assertEquals(1, tools.count { it.name == "Bash" })
        assertTrue(tools.none { it.name == "read_file" || it.name == "write_file" || it.name == "edit_file" || it.name == "run_shell" })

        val read = tools.single { it.name == "Read" }
        assertEquals(listOf("file_path"), required(read))
        assertEquals(setOf("file_path", "offset", "limit", "pages"), props(read).keys)
        assertTrue(read.description.contains("`file_path` must be an absolute path."))
        assertTrue(read.description.contains("Reads up to 2000 lines by default."))
        assertTrue(read.description.contains("substitution"))
        assertTrue(read.readOnly)
        assertFalse(read.destructive)
        assertTrue(read.concurrentSafe)
        assertFalse(read.needsApproval)
        assertEquals("read", read.permissionName)
        assertEquals("none", read.sideEffectScope)
        assertEquals("low", read.riskLevel)
        assertEquals(30_000L, read.timeoutMs)
        assertEquals(ReadTextFormatter.MAX_FILE_BYTES, read.maxOutputBytes)

        val write = tools.single { it.name == "Write" }
        assertEquals(listOf("file_path", "content"), required(write))
        assertFalse(props(write).containsKey("path"))
        assertEquals("edit", write.permissionName)
        assertFalse(write.readOnly)
        assertFalse(write.destructive)
        assertFalse(write.concurrentSafe)
        assertTrue(write.needsApproval)
        assertEquals("workspace", write.sideEffectScope)
        assertEquals("medium", write.riskLevel)
        assertEquals(30_000L, write.timeoutMs)
        assertEquals(1_000_000, write.maxOutputBytes)
        assertTrue(write.description.startsWith("Writes a file to the local filesystem, overwriting if one exists."))

        val edit = tools.single { it.name == "Edit" }
        assertEquals(listOf("file_path", "old_string", "new_string"), required(edit))
        assertEquals(setOf("file_path", "old_string", "new_string", "replace_all"), props(edit).keys)
        assertFalse(props(edit).containsKey("old_str"))
        assertEquals("edit", edit.permissionName)
        assertEquals(30_000L, edit.timeoutMs)
        assertEquals(1_000_000, edit.maxOutputBytes)

        val bash = tools.single { it.name == "Bash" }
        assertEquals(listOf("command"), required(bash))
        assertEquals(
            setOf("command", "timeout", "description", "run_in_background", "dangerouslyDisableSandbox"),
            props(bash).keys,
        )
        assertFalse(props(bash).containsKey("timeout_ms"))
        assertFalse(bash.readOnly)
        assertFalse(bash.destructive)
        assertTrue(bash.needsApproval)
        assertEquals("bash", bash.permissionName)
        assertEquals("system", bash.sideEffectScope)
        assertEquals("high", bash.riskLevel)
        assertEquals(120_000L, bash.timeoutMs)
        assertEquals(10_000_000, bash.maxOutputBytes)
        assertTrue(bash.description.contains("`timeout` is in milliseconds: default 120000, max 600000."))
        assertEquals(600_000L, bash.resolveTimeoutMs(buildJsonObject { put("timeout", 900_000) }))
        assertEquals(120_000L, bash.resolveTimeoutMs(buildJsonObject { put("command", "ls") }))
    }

    @Test
    fun readonlyBashOverridesCapabilityAndWritesDoNot() {
        val base = PermissionToolCapability(
            readOnly = false,
            destructive = false,
            needsApproval = true,
            riskLevel = "high",
            sideEffectScope = "system",
            permissionName = "bash",
        )
        val read = bashRuntimeCapability("ls -la", base)
        assertTrue(read.readOnly == true)
        assertFalse(read.needsApproval == true)
        assertEquals("low", read.riskLevel)
        assertEquals("none", read.sideEffectScope)
        val mutate = bashRuntimeCapability("rm x", base)
        assertEquals(base, mutate)
    }

    @Test
    fun readSchemaExposesPdfPages() {
        val read = ReadFileTool(context, ReadFileState())
        val pages = read.parameters["properties"]!!.jsonObject["pages"]!!.jsonObject
        assertEquals("string", pages["type"]!!.jsonPrimitive.content)
        val description = pages["description"]!!.jsonPrimitive.content
        assertTrue(description.contains("1-5"))
        assertTrue(description.contains("20"))
        assertTrue(read.description.contains("`pages`"))
    }

    @Test
    fun schemaRejectsMissingFilePathAndEmptyPath() = runTest {
        val read = ReadFileTool(context, ReadFileState())
        val missing = read.execute(buildJsonObject { })
        assertTrue(missing.isError)
        assertEquals("file_path is required", missing.output)
        val empty = read.execute(buildJsonObject { put("file_path", "") })
        assertTrue(empty.isError)
        assertEquals("Tool path must not be empty", empty.output)
        val edit = EditFileTool(context, ReadFileState())
        val same = edit.execute(buildJsonObject {
            put("file_path", "/root/unused.txt")
            put("old_string", "a")
            put("new_string", "a")
        })
        assertTrue(same.isError)
        assertEquals("No changes to make: old_string and new_string are exactly the same.", same.output)
    }

    @Test
    fun editWithoutReadFailsAndReadThenEditMatchesModelText() = runTest {
        val path = "/root/batch-a-${System.nanoTime()}.txt"
        val access = WorkspaceAccess(context)
        val state = ReadFileState()
        val edit = EditFileTool(context, state)
        try {
            access.writeText(path, "alpha\nbeta\n")
            val unread = edit.execute(buildJsonObject {
                put("file_path", path)
                put("old_string", "alpha")
                put("new_string", "gamma")
            })
            assertTrue(unread.isError)
            assertEquals(
                "File has not been read yet. Read it first before writing to it.",
                unread.output,
            )
            val read = ReadFileTool(context, state)
            val viewed = read.execute(buildJsonObject { put("file_path", path) })
            assertFalse(viewed.isError)
            assertTrue(viewed.output.startsWith("1\talpha"))
            val window = read.execute(buildJsonObject {
                put("file_path", path)
                put("offset", 2)
                put("limit", 1)
            })
            assertTrue(window.output.startsWith("2\tbeta"))
            val edited = edit.execute(buildJsonObject {
                put("file_path", path)
                put("old_string", "alpha")
                put("new_string", "gamma")
            })
            assertFalse(edited.output, edited.isError)
            assertEquals(
                "The file $path has been updated successfully.${ReadTextFormatter.FRESHNESS_SUFFIX}",
                edited.output,
            )
            val all = edit.execute(buildJsonObject {
                put("file_path", path)
                put("old_string", "e")
                put("new_string", "E")
                put("replace_all", true)
            })
            assertFalse(all.isError)
            assertEquals(
                "The file $path has been updated. All occurrences were successfully replaced.${ReadTextFormatter.FRESHNESS_SUFFIX}",
                all.output,
            )
        } finally {
            access.deleteFile(path)
        }
    }

    @Test
    fun writeCreateAndUpdateUseUpstreamModelText() = runTest {
        val path = "/root/batch-a-write-${System.nanoTime()}.txt"
        val state = ReadFileState()
        val write = WriteFileTool(context, state)
        val access = WorkspaceAccess(context)
        try {
            val created = write.execute(buildJsonObject {
                put("file_path", path)
                put("content", "one")
            })
            assertFalse(created.isError)
            assertEquals(
                "File created successfully at: $path${ReadTextFormatter.FRESHNESS_SUFFIX}",
                created.output,
            )
            val updated = write.execute(buildJsonObject {
                put("path", path)
                put("content", "two")
            })
            assertFalse(updated.output, updated.isError)
            assertEquals(
                "The file $path has been updated successfully.${ReadTextFormatter.FRESHNESS_SUFFIX}",
                updated.output,
            )
        } finally {
            access.deleteFile(path)
        }
    }

    private fun props(tool: Tool): JsonObject = tool.parameters["properties"]!!.jsonObject

    private fun required(tool: Tool): List<String> =
        tool.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }
}
