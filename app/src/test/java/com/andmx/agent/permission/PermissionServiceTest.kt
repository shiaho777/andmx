package com.andmx.agent.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PermissionServiceTest {
    @Test
    fun webSearchFallsBackToReadOnlyInBuild() {
        val decision = PermissionService().checkPermission(
            PermissionContext(toolName = "WebSearch", mode = CollaborationMode.BUILD, riskLevel = "low"),
        )
        assertEquals("allow", decision.decision)
        assertEquals("mode.build.readOnly", decision.ruleId)
        assertEquals("low", decision.riskLevel)
        assertEquals("none", decision.sideEffectScope)
    }

    @Test
    fun sixModesMatchUpstreamAllowAskDeny() {
        val service = PermissionService()
        assertEquals("allow", service.checkPermission(ctx("Read", CollaborationMode.PLAN), readCap).decision)
        assertEquals("deny", service.checkPermission(ctx("Edit", CollaborationMode.PLAN), editCap).decision)
        assertEquals("mode.plan.nonReadOnly", service.checkPermission(ctx("Edit", CollaborationMode.PLAN), editCap).ruleId)
        assertEquals("allow", service.checkPermission(ctx("Read", CollaborationMode.BUILD), readCap).decision)
        assertEquals("ask", service.checkPermission(ctx("Edit", CollaborationMode.BUILD), editCap).decision)
        assertEquals("allow", service.checkPermission(ctx("Edit", CollaborationMode.EDIT), editCap).decision)
        assertEquals("mode.edit.fileEdit", service.checkPermission(ctx("Edit", CollaborationMode.EDIT), editCap).ruleId)
        assertEquals("ask", service.checkPermission(ctx("Bash", CollaborationMode.EDIT), bashCap).decision)
        assertEquals("allow", service.checkPermission(ctx("Bash", CollaborationMode.YOLO), bashCap).decision)
        assertEquals("mode.yolo", service.checkPermission(ctx("Bash", CollaborationMode.YOLO), bashCap).ruleId)
        assertEquals("allow", service.checkPermission(ctx("Bash", CollaborationMode.GUARDED), bashCap).decision)
        assertEquals("ask", service.checkPermission(ctx("Bash", CollaborationMode.AUTO), bashCap).decision)
        assertEquals("mode.auto.destructive", service.checkPermission(ctx("Bash", CollaborationMode.AUTO), bashCap).ruleId)
        assertEquals("allow", service.checkPermission(ctx("Edit", CollaborationMode.AUTO), editCap).decision)
        assertEquals("mode.auto.lowRisk", service.checkPermission(ctx("Edit", CollaborationMode.AUTO), editCap).ruleId)
    }

    @Test
    fun guardedDangerousCommandAsksOnce() {
        val decision = PermissionService().checkPermission(
            ctx("Bash", CollaborationMode.GUARDED, input = mapOf("command" to "rm -rf /")),
            bashCap.copy(userApprovalRule = "bash.guarded.dangerous"),
        )
        assertEquals("ask", decision.decision)
        assertEquals("bash.guarded.dangerous", decision.ruleId)
        assertEquals("user-once", decision.approvalMode)
    }

    @Test
    fun alwaysAskStillAsksUnderYoloAndYieldsToHardBlocks() {
        val asking = PermissionToolCapability(alwaysAsk = true, readOnly = true, destructive = false, needsApproval = true, riskLevel = "low", sideEffectScope = "none")
        val yolo = PermissionService().checkPermission(ctx("Read", CollaborationMode.YOLO), asking)
        assertEquals("ask", yolo.decision)
        assertEquals("tool.alwaysAsk", yolo.ruleId)
        assertTrue(yolo.alwaysAsk)
        val disallowed = PermissionService(PermissionConfig(disallowedTools = setOf("Read")))
            .checkPermission(ctx("Read", CollaborationMode.YOLO), asking)
        assertEquals("deny", disallowed.decision)
        assertEquals("rule.disallowedTools", disallowed.ruleId)
        val denied = PermissionService().checkPermission(
            ctx("Read", CollaborationMode.BUILD),
            asking,
            PermissionRuleset(deny = listOf(PermissionRuleValue("Read"))),
        )
        assertEquals("deny", denied.decision)
        assertEquals("rule.project.deny", denied.ruleId)
        val auto = PermissionService().checkPermission(ctx("Read", CollaborationMode.AUTO), asking)
        assertEquals("ask", auto.decision)
        assertEquals("tool.alwaysAsk", auto.ruleId)
    }

    @Test
    fun sessionAllowLiftsAlwaysAskAndDiesWithTheService() {
        val asking = PermissionToolCapability(alwaysAsk = true, needsApproval = true, readOnly = false, destructive = false, riskLevel = "medium", sideEffectScope = "workspace")
        val service = PermissionService()
        service.grantSessionAllow("Edit", null)
        val allowed = service.checkPermission(ctx("Edit", CollaborationMode.PLAN), asking)
        assertEquals("allow", allowed.decision)
        assertEquals("rule.session.allow", allowed.ruleId)
        val fresh = PermissionService().checkPermission(ctx("Edit", CollaborationMode.PLAN), asking)
        assertEquals("ask", fresh.decision)
        assertEquals("tool.alwaysAsk", fresh.ruleId)
    }

    @Test
    fun sessionDenyUsesItsOwnRuleId() {
        val service = PermissionService()
        service.denyForSession("Bash", null)
        val decision = service.checkPermission(ctx("Bash", CollaborationMode.BUILD), bashCap)
        assertEquals("deny", decision.decision)
        assertEquals("rule.session.deny", decision.ruleId)
    }

    @Test
    fun planWritesAreNotBypassedByAllowRulesOrGuarded() {
        val allowedTools = PermissionService(PermissionConfig(allowedTools = setOf("Edit")))
        val byConfig = allowedTools.checkPermission(ctx("Edit", CollaborationMode.PLAN), editCap)
        assertEquals("deny", byConfig.decision)
        assertEquals("mode.plan.nonReadOnly", byConfig.ruleId)
        val byProject = PermissionService().checkPermission(
            ctx("Edit", CollaborationMode.PLAN),
            editCap,
            PermissionRuleset(allow = listOf(PermissionRuleValue("Edit"))),
        )
        assertEquals("deny", byProject.decision)
        assertEquals("mode.plan.nonReadOnly", byProject.ruleId)
        val guarded = PermissionService().checkPermission(
            ctx("Edit", CollaborationMode.GUARDED, planEnabled = true),
            editCap,
        )
        assertEquals("deny", guarded.decision)
        assertEquals("mode.plan.nonReadOnly", guarded.ruleId)
    }

    @Test
    fun yoloBypassesDisallowedTools() {
        val decision = PermissionService(PermissionConfig(disallowedTools = setOf("Bash")))
            .checkPermission(ctx("Bash", CollaborationMode.YOLO), bashCap)
        assertEquals("allow", decision.decision)
        assertEquals("mode.yolo", decision.ruleId)
    }

    @Test
    fun planModeTransitionsAndExplicitSessionCapability() {
        val enter = PermissionService().checkPermission(ctx("EnterPlanMode", CollaborationMode.BUILD), editCap)
        assertEquals("allow", enter.decision)
        assertEquals("tool.plan.enter", enter.ruleId)
        val exitOutside = PermissionService().checkPermission(
            ctx("ExitPlanMode", CollaborationMode.BUILD),
            PermissionToolCapability(requiresUserInteraction = true, needsApproval = true, readOnly = false, destructive = false, riskLevel = "low", sideEffectScope = "session"),
        )
        assertEquals("deny", exitOutside.decision)
        assertEquals("mode.plan.exitOnly", exitOutside.ruleId)
        val exitInside = PermissionService().checkPermission(
            ctx("ExitPlanMode", CollaborationMode.PLAN),
            PermissionToolCapability(requiresUserInteraction = true, needsApproval = true, readOnly = false, destructive = false, riskLevel = "low", sideEffectScope = "session"),
        )
        assertEquals("ask", exitInside.decision)
        assertEquals("tool.userInteraction", exitInside.ruleId)
        val sessionControl = PermissionService().checkPermission(
            ctx("SendMessage", CollaborationMode.PLAN),
            PermissionToolCapability(
                allowedInPlanMode = true,
                readOnly = false,
                destructive = false,
                needsApproval = false,
                riskLevel = "low",
                sideEffectScope = "session",
                permissionName = "agent.message.respond",
            ),
        )
        assertEquals("allow", sessionControl.decision)
        assertEquals("mode.plan.explicitSessionCapability", sessionControl.ruleId)
    }

    @Test
    fun projectDenyBeatsPlanMcpExceptionAndPrefixRulesMatch() {
        val mcp = PermissionToolCapability(
            readOnly = false,
            destructive = false,
            needsApproval = true,
            riskLevel = "medium",
            sideEffectScope = "network",
            permissionName = "mcp",
        )
        val allowed = PermissionService().checkPermission(ctx("mcp_tool", CollaborationMode.PLAN), mcp)
        assertEquals("allow", allowed.decision)
        assertEquals("mode.plan.mcp", allowed.ruleId)
        val denied = PermissionService().checkPermission(
            ctx("mcp_tool", CollaborationMode.PLAN),
            mcp,
            PermissionRuleset(deny = listOf(PermissionRuleValue("mcp_tool"))),
        )
        assertEquals("deny", denied.decision)
        assertEquals("rule.project.deny", denied.ruleId)
        val prefix = PermissionService().checkPermission(
            ctx("Bash", CollaborationMode.BUILD, input = mapOf("command" to "git status")),
            bashCap,
            PermissionRuleset(allow = listOf(PermissionRuleValue("Bash", "git:*"))),
        )
        assertEquals("allow", prefix.decision)
        assertEquals("rule.project.allow", prefix.ruleId)
    }

    @Test
    fun webFetchPreapproveAndDomainDeny() {
        val fetch = PermissionToolCapability(
            readOnly = true,
            destructive = false,
            needsApproval = true,
            riskLevel = "medium",
            sideEffectScope = "network",
            permissionName = "webfetch",
        )
        val preapproved = PermissionService().checkPermission(
            ctx("WebFetch", CollaborationMode.BUILD, input = mapOf("url" to "https://docs.python.org/3/library/pathlib.html")),
            fetch,
        )
        assertEquals("allow", preapproved.decision)
        assertEquals("tool.webfetch.preapproved", preapproved.ruleId)
        val vendor = PermissionService().checkPermission(
            ctx("WebFetch", CollaborationMode.BUILD, input = mapOf("url" to "https://github.com/anthropics/example")),
            fetch,
        )
        assertEquals("ask", vendor.decision)
        assertEquals("mode.build.sideEffect", vendor.ruleId)
        val domainDeny = PermissionService().checkPermission(
            ctx("WebFetch", CollaborationMode.BUILD, input = mapOf("url" to "https://docs.python.org/3/library/pathlib.html")),
            fetch,
            PermissionRuleset(deny = listOf(PermissionRuleValue("WebFetch", "domain:docs.python.org"))),
        )
        assertEquals("deny", domainDeny.decision)
        assertEquals("rule.project.deny", domainDeny.ruleId)
        val fullUrl = PermissionService().checkPermission(
            ctx("WebFetch", CollaborationMode.BUILD, input = mapOf("url" to "https://example.com/docs")),
            fetch,
            PermissionRuleset(deny = listOf(PermissionRuleValue("WebFetch", "https://example.com/docs"))),
        )
        assertEquals("ask", fullUrl.decision)
    }

    @Test
    fun workflowDraftWriteIsPreapprovedOutsidePlan() {
        val root = Files.createTempDirectory("andmx-drafts").toFile()
        val draft = root.resolve(".zcode/workflow-drafts/run.md")
        draft.parentFile.mkdirs()
        draft.writeText("draft")
        val edit = PermissionToolCapability(
            readOnly = false,
            destructive = false,
            needsApproval = true,
            riskLevel = "medium",
            sideEffectScope = "workspace",
            permissionName = "edit",
        )
        val allowed = PermissionService().checkPermission(
            PermissionContext(
                toolName = "Edit",
                input = mapOf("file_path" to draft.absolutePath),
                mode = CollaborationMode.BUILD,
                workingDirectory = root.absolutePath,
            ),
            edit,
        )
        assertEquals("allow", allowed.decision)
        assertEquals("tool.workflowDraft.preapproved", allowed.ruleId)
        val planned = PermissionService().checkPermission(
            PermissionContext(
                toolName = "Edit",
                input = mapOf("file_path" to draft.absolutePath),
                mode = CollaborationMode.PLAN,
                workingDirectory = root.absolutePath,
            ),
            edit,
        )
        assertEquals("deny", planned.decision)
        assertEquals("mode.plan.nonReadOnly", planned.ruleId)
        root.deleteRecursively()
    }

    @Test
    fun ownedWorkflowAmendSkipsAlwaysAsk() {
        val cap = PermissionToolCapability(alwaysAsk = true, needsApproval = true, readOnly = false, destructive = false, riskLevel = "medium", sideEffectScope = "workspace")
        val owned = PermissionService().checkPermission(
            ctx(
                "AmendWorkflow",
                CollaborationMode.BUILD,
                input = mapOf(
                    "predecessor" to mapOf(
                        "owned_by_this_session" to true,
                        "stop_reason" to "completed",
                    ),
                ),
            ),
            cap,
        )
        assertEquals("allow", owned.decision)
        assertEquals("rule.session.workflowOwner", owned.ruleId)
        val foreign = PermissionService().checkPermission(
            ctx(
                "AmendWorkflow",
                CollaborationMode.YOLO,
                input = mapOf(
                    "predecessor" to mapOf(
                        "owned_by_this_session" to false,
                        "stop_reason" to "completed",
                    ),
                ),
            ),
            cap,
        )
        assertEquals("ask", foreign.decision)
        assertEquals("tool.alwaysAsk", foreign.ruleId)
    }

    @Test
    fun officialCuaRuleDoesNotMatchOrdinaryTools() {
        val ordinary = PermissionService().checkPermission(
            ctx("Bash", CollaborationMode.BUILD),
            bashCap,
            PermissionRuleset(allow = listOf(PermissionRuleValue("official_cua"))),
        )
        assertFalse(ordinary.ruleId == "rule.project.allow")
        val cua = PermissionService().checkPermission(
            ctx("computer", CollaborationMode.BUILD),
            bashCap.copy(permissionCapabilityGroup = "official_cua", readOnly = false, needsApproval = true, riskLevel = "high", sideEffectScope = "system"),
            PermissionRuleset(allow = listOf(PermissionRuleValue("official_cua"))),
        )
        assertEquals("allow", cua.decision)
        assertEquals("rule.project.allow", cua.ruleId)
    }

    private fun ctx(name: String, mode: CollaborationMode, input: Any? = null, planEnabled: Boolean? = null) =
        PermissionContext(toolName = name, input = input, mode = mode, planEnabled = planEnabled)

    private val readCap = PermissionToolCapability(
        readOnly = true,
        destructive = false,
        needsApproval = false,
        riskLevel = "low",
        sideEffectScope = "none",
    )
    private val editCap = PermissionToolCapability(
        readOnly = false,
        destructive = false,
        needsApproval = true,
        riskLevel = "medium",
        sideEffectScope = "workspace",
        permissionName = "edit",
    )
    private val bashCap = PermissionToolCapability(
        readOnly = false,
        destructive = true,
        needsApproval = true,
        riskLevel = "high",
        sideEffectScope = "system",
    )
}
