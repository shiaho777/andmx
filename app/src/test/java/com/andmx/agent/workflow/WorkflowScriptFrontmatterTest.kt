package com.andmx.agent.workflow

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowScriptFrontmatterTest {

    private fun file(yaml: String, script: String = "return 1\n"): String =
        "${WorkflowScriptFrontmatter.SENTINEL}\n" + yaml.trimEnd('\n') + "\n*/\n" + script

    @Test
    fun parsesFlowStyleArgs() {
        val result = WorkflowScriptFrontmatter.parse(
            file(
                """
                description: Review a pull request
                whenToUse: When the user asks for a review
                args:
                  pr: { type: string, required: true }
                  depth: { type: number, required: false, description: how deep }
                """.trimIndent(),
            ),
        )
        val ok = result as WorkflowScriptFrontmatter.Result.Ok
        assertEquals("Review a pull request", ok.meta.description)
        assertEquals("When the user asks for a review", ok.meta.whenToUse)
        assertEquals(setOf("pr", "depth"), ok.meta.args.keys)
        assertEquals("string", ok.meta.args.getValue("pr").type)
        assertEquals(true, ok.meta.args.getValue("pr").required)
        assertEquals("how deep", ok.meta.args.getValue("depth").description)
        assertEquals("return 1\n", ok.script)
    }

    @Test
    fun parsesBlockStyleArgsAndDefaults() {
        val result = WorkflowScriptFrontmatter.parse(
            file(
                """
                description: Build the thing
                args:
                  target:
                    type: string
                    required: true
                  flags:
                    type: json
                    default: [a, b]
                """.trimIndent(),
            ),
        )
        val ok = result as WorkflowScriptFrontmatter.Result.Ok
        assertNull(ok.meta.args.getValue("flags").required)
        assertEquals("json", ok.meta.args.getValue("flags").type)
        assertEquals("[\"a\",\"b\"]", ok.meta.args.getValue("flags").default.toString())
    }

    @Test
    fun parsesBlockScalarDescription() {
        val result = WorkflowScriptFrontmatter.parse(
            file(
                """
                description: |
                  first line
                  second line
                """.trimIndent(),
            ),
        )
        val ok = result as WorkflowScriptFrontmatter.Result.Ok
        assertTrue(ok.meta.description.startsWith("first line"))
        assertTrue(ok.meta.description.contains("second line"))
    }

    @Test
    fun rejectedWhenSentinelIsMissing() {
        val result = WorkflowScriptFrontmatter.parse("description: nope\nreturn 1\n")
        val fail = result as WorkflowScriptFrontmatter.Result.Fail
        assertEquals(WorkflowScriptFrontmatter.Reason.MISSING_FRONTMATTER, fail.reason)
    }

    @Test
    fun rejectedWhenUnterminated() {
        val result = WorkflowScriptFrontmatter.parse(
            "${WorkflowScriptFrontmatter.SENTINEL}\ndescription: x\nreturn 1\n",
        )
        val fail = result as WorkflowScriptFrontmatter.Result.Fail
        assertEquals(WorkflowScriptFrontmatter.Reason.UNTERMINATED_FRONTMATTER, fail.reason)
    }

    @Test
    fun rejectedOnUnknownMetadataKey() {
        val result = WorkflowScriptFrontmatter.parse(file("description: x\nnam: y\n"))
        val fail = result as WorkflowScriptFrontmatter.Result.Fail
        assertEquals(WorkflowScriptFrontmatter.Reason.INVALID_METADATA, fail.reason)
        assertTrue(fail.detail.contains("nam"))
    }

    @Test
    fun rejectedOnUnknownArgType() {
        val result = WorkflowScriptFrontmatter.parse(
            file("description: x\nargs:\n  a: { type: integer }\n"),
        )
        val fail = result as WorkflowScriptFrontmatter.Result.Fail
        assertEquals(WorkflowScriptFrontmatter.Reason.INVALID_METADATA, fail.reason)
        assertTrue(fail.detail.contains("integer"))
    }

    @Test
    fun rejectedOnMissingDescription() {
        val result = WorkflowScriptFrontmatter.parse(file("whenToUse: x\n"))
        val fail = result as WorkflowScriptFrontmatter.Result.Fail
        assertEquals(WorkflowScriptFrontmatter.Reason.INVALID_METADATA, fail.reason)
    }

    @Test
    fun rejectedOnBadIndentationWithLineNumber() {
        val result = WorkflowScriptFrontmatter.parse(
            file("description: x\n    whenToUse: y\n"),
        )
        val fail = result as WorkflowScriptFrontmatter.Result.Fail
        assertEquals(WorkflowScriptFrontmatter.Reason.INVALID_YAML, fail.reason)
        assertTrue(fail.detail.contains("line 3"))
    }

    @Test
    fun roundTripIsStable() {
        val meta = WorkflowScriptFrontmatter.Meta(
            description = "A workflow with: colons, commas and a #tag",
            whenToUse = "When you need it",
            args = mapOf(
                "pr" to WorkflowScriptFrontmatter.ArgSpec("string", required = true, description = "pull request"),
                "flags" to WorkflowScriptFrontmatter.ArgSpec(
                    "json",
                    required = false,
                    default = JsonPrimitive("plain"),
                ),
            ),
        )
        val script = "const x = 1\nreturn x\n"
        val first = WorkflowScriptFrontmatter.serialize(meta, script)
        val parsed = WorkflowScriptFrontmatter.parse(first) as WorkflowScriptFrontmatter.Result.Ok
        assertEquals(meta, parsed.meta)
        assertEquals(script, parsed.script)
        assertEquals(first, WorkflowScriptFrontmatter.serialize(parsed.meta, parsed.script))
    }

    @Test
    fun roundTripKeepsMultilineDescription() {
        val meta = WorkflowScriptFrontmatter.Meta(
            description = "line one\nline two",
        )
        val text = WorkflowScriptFrontmatter.serialize(meta, "return 0\n")
        val parsed = WorkflowScriptFrontmatter.parse(text) as WorkflowScriptFrontmatter.Result.Ok
        assertEquals(meta.description, parsed.meta.description)
    }

    @Test
    fun scriptIsPreservedAndOffsetPointsAtItsFirstLine() {
        val source = "${WorkflowScriptFrontmatter.SENTINEL}\ndescription: x\n*/\nconst a = 1\nconst b = 2\n"
        val ok = WorkflowScriptFrontmatter.parse(source) as WorkflowScriptFrontmatter.Result.Ok
        assertEquals("const a = 1\nconst b = 2\n", ok.script)
        assertEquals(3, ok.bodyLineOffset)
        assertEquals("const a = 1", source.split('\n')[ok.bodyLineOffset])
    }

    @Test
    fun leadingBlankLinesBeforeSentinelAreAllowed() {
        val source = "\n\n${WorkflowScriptFrontmatter.SENTINEL}\ndescription: x\n*/\nreturn 1\n"
        val ok = WorkflowScriptFrontmatter.parse(source) as WorkflowScriptFrontmatter.Result.Ok
        assertEquals("x", ok.meta.description)
        assertEquals("return 1\n", ok.script)
    }
}
