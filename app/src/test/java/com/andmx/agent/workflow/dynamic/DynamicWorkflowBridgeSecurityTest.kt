package com.andmx.agent.workflow.dynamic

import com.andmx.workspace.GuestPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DynamicWorkflowBridgeSecurityTest {

    @Test
    fun worldReadWhitelistAcceptsKnownOps() {
        listOf("read", "glob", "grep", "git-changed-files", "git-diff", "git-status", "git-log", "run")
            .forEach { assertTrue(it, DynamicWorkflowBridgeSecurity.isAllowedWorldReadOp(it)) }
    }

    @Test
    fun worldReadWhitelistRejectsUnknownOps() {
        listOf("", "write", "delete", "exec", "READ", "read ", "../read", "shell")
            .forEach { assertFalse(it, DynamicWorkflowBridgeSecurity.isAllowedWorldReadOp(it)) }
        assertNull(DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs("write", "[\"a\"]", "/root/project"))
    }

    @Test
    fun relativeReadPathResolvesAgainstRunCwd() {
        val out = DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs(
            "read",
            "[\"a.txt\"]",
            "/root/project",
        )!!
        val first = Json.parseToJsonElement(out).jsonArray[0].jsonPrimitive.content
        assertEquals("/root/project/a.txt", first)
    }

    @Test
    fun traversalInsideCwdIsNormalizedNotRejected() {
        val out = DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs(
            "read",
            "[\"sub/../b.txt\", 512]",
            "/root/project",
        )!!
        val array = Json.parseToJsonElement(out).jsonArray
        assertEquals("/root/project/b.txt", array[0].jsonPrimitive.content)
        assertEquals(2, array.size)
    }

    @Test
    fun traversalEscapingGuestRootIsRejected() {
        assertNull(
            DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs(
                "read",
                "[\"../../../../etc/passwd\"]",
                "/root/project",
            ),
        )
    }

    @Test
    fun absolutePathClampsAtGuestRootLikeReadTool() {
        val escaped = DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs(
            "read",
            "[\"/root/project/../../../../etc/passwd\"]",
            "/root/project",
        )!!
        assertEquals(
            "/etc/passwd",
            Json.parseToJsonElement(escaped).jsonArray[0].jsonPrimitive.content,
        )
        val sibling = DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs(
            "read",
            "[\"/root/project/../secrets.txt\"]",
            "/root/project",
        )!!
        assertEquals(
            "/root/secrets.txt",
            Json.parseToJsonElement(sibling).jsonArray[0].jsonPrimitive.content,
        )
    }

    @Test
    fun siblingPrefixDirectoryIsRejected() {
        assertNull(
            DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs(
                "read",
                "[\"../project-evil/a.txt\"]",
                "/root/project",
            ),
        )
    }

    @Test
    fun absoluteReadPathKeepsGuestSemantics() {
        val out = DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs(
            "read",
            "[\"/root/project/a/../b.txt\"]",
            "/other",
        )!!
        assertEquals(
            "/root/project/b.txt",
            Json.parseToJsonElement(out).jsonArray[0].jsonPrimitive.content,
        )
    }

    @Test
    fun blankReadPathPassesThrough() {
        val out = DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs("read", "[\"\"]", "/root/project")!!
        assertEquals("", Json.parseToJsonElement(out).jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun nonReadOpsPassArgsThroughUnchanged() {
        val args = "[\"pattern\",\"*.kt\"]"
        val out = DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs("grep", args, "/root/project")!!
        assertEquals(
            Json.parseToJsonElement(args).jsonArray,
            Json.parseToJsonElement(out).jsonArray,
        )
    }

    @Test
    fun malformedArgsBecomeEmptyArray() {
        val out = DynamicWorkflowBridgeSecurity.normalizeWorldReadArgs("glob", "not json", "/root/project")!!
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(out).jsonArray)
    }

    @Test
    fun runIdGuardAcceptsGeneratedIds() {
        assertTrue(DynamicWorkflowBridgeSecurity.isSafeRunId("dwf_abc123def456"))
        assertTrue(DynamicWorkflowBridgeSecurity.isSafeRunId("dwfeval-0a1b-2c3d"))
        assertTrue(DynamicWorkflowBridgeSecurity.isSafeRunId("probe-1730000000000"))
        assertTrue(DynamicWorkflowBridgeSecurity.isSafeRunId("run:legacy"))
    }

    @Test
    fun runIdGuardRejectsTraversalAndInjection() {
        listOf("", "../evil", "a/b", "a\\b", "a b", "a\u0000b", "x".repeat(129))
            .forEach { assertFalse(it, DynamicWorkflowBridgeSecurity.isSafeRunId(it)) }
    }

    @Test
    fun guestPathsResolveStaysInsideRootfs() {
        val root = File(System.getProperty("java.io.tmpdir"), "dwf-security-test-root").apply { mkdirs() }
        try {
            val inside = GuestPaths.resolve(root, "/root/project/../../etc/hosts")
            assertTrue(inside.canonicalPath.startsWith(root.canonicalPath))
            assertEquals("/etc/hosts", GuestPaths.normalize("/root/project/../../etc/hosts"))
        } finally {
            root.delete()
        }
    }

    @Test
    fun guestPathsNormalizeClampsDotDotAtRoot() {
        assertEquals("/x", GuestPaths.normalize("/../../../x"))
        assertEquals("/root/a/b", GuestPaths.normalize("a/./b"))
        assertEquals("/root/a", GuestPaths.normalize("/root/a/../a"))
    }
}
