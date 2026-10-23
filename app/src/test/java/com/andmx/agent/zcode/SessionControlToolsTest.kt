package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.permission.CollaborationMode
import com.andmx.ui2.chat.ExecMode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlToolsTest {
    @Test
    fun schemaRequiredFields() {
        val todoRead = TodoReadTool(TodoState())
        val todoWrite = TodoWriteTool(TodoState(), UpdatePlanTool())
        val enter = EnterPlanModeTool(PlanModeState())
        val exit = ExitPlanModeTool(PlanModeState(ExecMode.PLAN))
        val ask = AskUserQuestionTool { _, _ -> "" }
        val skill = SkillTool { _, _ -> "" }
        val session = ReadSessionContextTool { _, _, _, _ ->
            SessionContextRead(status = "not_found")
        }
        val models = ListModelsTool({ emptyList() }, { "" to "" })

        assertNull(todoRead.parameters["required"])
        assertEquals(listOf("todos"), required(todoWrite))
        assertEquals(listOf("content", "status", "priority"), required(todoItem(todoWrite)))
        assertNull(enter.parameters["required"])
        assertEquals(listOf("plan"), required(exit))
        assertEquals(listOf("skill"), required(skill))
        assertNull(skill.parameters["additionalProperties"])
        assertEquals(listOf("query"), required(session))
        assertNull(models.parameters["required"])
        assertEquals(false, models.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(
            "https://json-schema.org/draft/2020-12/schema",
            todoWrite.parameters["\$schema"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun askUserSchema() {
        val ask = AskUserQuestionTool { _, _ -> "" }
        assertEquals(listOf("questions"), required(ask))
        assertEquals(false, ask.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        val questions = props(ask)["questions"]!!.jsonObject
        assertEquals(1, questions["minItems"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, questions["maxItems"]!!.jsonPrimitive.content.toInt())
        assertEquals("Questions to ask the user (1-4 questions)", questions["description"]!!.jsonPrimitive.content)
        val item = questions["items"]!!.jsonObject
        assertEquals(listOf("question", "header", "options", "multiSelect"), required(item))
        assertEquals(false, item["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        val itemProps = item["properties"]!!.jsonObject
        assertEquals(false, itemProps["multiSelect"]!!.jsonObject["default"]!!.jsonPrimitive.booleanOrNull)
        val options = itemProps["options"]!!.jsonObject
        assertEquals(2, options["minItems"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, options["maxItems"]!!.jsonPrimitive.content.toInt())
        val option = options["items"]!!.jsonObject
        assertEquals(listOf("label", "description"), required(option))
        assertEquals(
            "The display text for this option that the user will see and select. Should be concise (1-5 words) and clearly describe the choice.",
            option["properties"]!!.jsonObject["label"]!!.jsonObject["description"]!!.jsonPrimitive.content,
        )
        assertTrue(ask.description.endsWith("\n"))
        assertTrue(ask.description.contains("ExitPlanMode"))
        assertEquals(true, ask.readOnly)
        assertEquals(true, ask.concurrentSafe)
        assertEquals(true, ask.requiresUserInteraction)
        assertEquals(true, ask.needsApproval)
        assertEquals("userInteraction", ask.sideEffectScope)
        assertEquals("low", ask.riskLevel)
        assertEquals(30_000L, ask.timeoutMs)
        assertEquals(100_000, ask.maxOutputBytes)
    }

    @Test
    fun enterPlanSwitchesModeToPlan() = runTest {
        val state = PlanModeState(ExecMode.EDIT)
        val seen = mutableListOf<ExecMode>()
        val tool = EnterPlanModeTool(state, onMode = { seen += it })
        val result = tool.execute(buildJsonObject {})
        assertFalse(result.isError)
        assertTrue(result.output.contains("Entered plan mode."))
        assertTrue(result.output.contains("DO NOT write or edit any files yet"))
        assertEquals(ExecMode.PLAN, state.mode)
        assertEquals(CollaborationMode.PLAN, state.collaboration)
        assertEquals(ExecMode.EDIT, state.previous)
        assertEquals(listOf(ExecMode.PLAN), seen)
        assertEquals(false, tool.readOnly)
        assertEquals(false, tool.needsApproval)
        assertEquals(false, tool.requiresUserInteraction)
        assertEquals("session", tool.sideEffectScope)
        assertEquals("plan.enter", tool.permissionName)
    }

    @Test
    fun exitPlanRestoresCapturedNonPlanMode() = runTest {
        val state = PlanModeState(ExecMode.YOLO)
        val seen = mutableListOf<ExecMode>()
        EnterPlanModeTool(state, onMode = { seen += it }).execute(buildJsonObject {})
        val exit = ExitPlanModeTool(
            state,
            onMode = { seen += it },
            requestPlanApproval = { true },
        )
        val result = exit.execute(buildJsonObject { put("plan", "  1. Restore the mode\n") })
        assertFalse(result.isError)
        assertEquals(ExecMode.YOLO, state.mode)
        assertEquals(CollaborationMode.YOLO, state.collaboration)
        assertEquals(listOf(ExecMode.PLAN, ExecMode.YOLO), seen)
        assertEquals(
            "User has approved your plan. You can now start coding. Start with updating your todo list if applicable.\n" +
                "\n" +
                "## Approved Plan:\n" +
                "1. Restore the mode",
            result.output,
        )
        val outside = PlanModeState(ExecMode.BUILD)
        val denied = ExitPlanModeTool(outside).execute(buildJsonObject { put("plan", "1. Stay put") })
        assertTrue(denied.isError)
        assertEquals(
            "You are not in plan mode. This tool is only for exiting plan mode after writing a plan. " +
                "If your plan was already approved, continue with implementation.",
            denied.output,
        )
        assertEquals(ExecMode.BUILD, outside.mode)
        assertEquals(true, exit.requiresUserInteraction)
        assertEquals(true, exit.needsApproval)
        assertEquals(false, exit.readOnly)
        assertEquals("plan.exit", exit.permissionName)
    }

    @Test
    fun skillMissingNameErrorMatchesUpstream() = runTest {
        val calls = AtomicInteger(0)
        val tool = SkillTool { name, _ ->
            calls.incrementAndGet()
            "loaded $name"
        }
        val missing = tool.execute(buildJsonObject {})
        assertTrue(missing.isError)
        assertEquals(
            "<tool_use_error>InputValidationError: Skill failed due to the following issue:\n" +
                "The required parameter `skill` is missing</tool_use_error>",
            missing.output,
        )
        val blank = tool.execute(buildJsonObject { put("skill", "  ") })
        assertEquals(missing.output, blank.output)
        val legacyBlank = tool.execute(buildJsonObject { put("name", "") })
        assertEquals(missing.output, legacyBlank.output)
        assertEquals(0, calls.get())
        val legacy = tool.execute(buildJsonObject { put("name", "demo-skill") })
        assertFalse(legacy.isError)
        assertEquals("loaded demo-skill", legacy.output)
        assertEquals(1, calls.get())
        assertEquals(true, tool.readOnly)
        assertEquals("session", tool.sideEffectScope)
        assertEquals(204_096, tool.maxOutputBytes)
        assertEquals("skill", tool.permissionName)
    }

    private fun props(tool: Tool): JsonObject = tool.parameters["properties"]!!.jsonObject

    private fun required(tool: Tool): List<String> = required(tool.parameters)

    private fun required(schema: JsonObject): List<String> =
        schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun todoItem(tool: Tool): JsonObject =
        props(tool)["todos"]!!.jsonObject["items"]!!.jsonObject
}
