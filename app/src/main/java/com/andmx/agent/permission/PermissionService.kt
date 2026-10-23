package com.andmx.agent.permission

data class PermissionContext(
    val toolName: String,
    val input: Any? = null,
    val riskLevel: String = "medium",
    val mode: CollaborationMode,
    val planEnabled: Boolean? = null,
    val prePlanMode: CollaborationMode? = null,
    val workingDirectory: String? = null,
)

data class PermissionToolCapability(
    val userApprovalRule: String? = null,
    val allowedInPlanMode: Boolean = false,
    val alwaysAsk: Boolean = false,
    val readOnly: Boolean? = null,
    val destructive: Boolean? = null,
    val requiresUserInteraction: Boolean? = null,
    val sideEffectScope: String? = null,
    val riskLevel: String? = null,
    val needsApproval: Boolean? = null,
    val permissionName: String? = null,
    val permissionCapabilityGroup: String? = null,
)

data class PermissionRuleValue(
    val toolName: String,
    val ruleContent: String? = null,
)

data class PermissionRuleset(
    val allow: List<PermissionRuleValue> = emptyList(),
    val ask: List<PermissionRuleValue> = emptyList(),
    val deny: List<PermissionRuleValue> = emptyList(),
)

data class PermissionConfig(
    val allowedTools: Set<String> = emptySet(),
    val disallowedTools: Set<String> = emptySet(),
    val autoApproveHighRisk: Boolean = false,
)

data class PermissionDecisionResult(
    val decision: String,
    val allowed: Boolean,
    val reason: String?,
    val escalated: Boolean,
    val mode: CollaborationMode,
    val ruleId: String,
    val riskLevel: String,
    val sideEffectScope: String?,
    val alwaysAsk: Boolean = false,
    val approvalMode: String? = null,
)

class PermissionService(
    private val config: PermissionConfig = PermissionConfig(),
) {
    private var sessionAllow: PermissionRuleset = PermissionRuleset()
    private var sessionDeny: List<PermissionRuleValue> = emptyList()

    fun grantSessionAllow(toolName: String, ruleContent: String?) {
        sessionAllow = sessionAllow.copy(
            allow = sessionAllow.allow + PermissionRuleValue(toolName, ruleContent),
        )
    }

    fun denyForSession(toolName: String, ruleContent: String?) {
        sessionDeny = sessionDeny + PermissionRuleValue(toolName, ruleContent)
    }

    fun checkPermission(
        context: PermissionContext,
        toolCapability: PermissionToolCapability? = null,
        projectRules: PermissionRuleset? = null,
    ): PermissionDecisionResult {
        val capability = resolveCapability(context, toolCapability)
        val planTransition = resolvePlanModeTransition(context)
        if (planTransition != null) {
            return if (planTransition.first == "allow") {
                allow(context, capability, planTransition.second, planTransition.third)
            } else {
                deny(context, capability, planTransition.second, planTransition.third)
            }
        }

        if (capability.requiresUserInteraction) {
            if (context.toolName in config.disallowedTools) {
                return deny(context, capability, "rule.disallowedTools", "Tool ${context.toolName} is explicitly disallowed")
            }
            return ask(context, capability, "tool.userInteraction", "Tool ${context.toolName} requires user interaction")
        }

        val planEnabled = context.planEnabled ?: (context.mode == CollaborationMode.PLAN)
        if (!planEnabled && context.mode == CollaborationMode.GUARDED && capability.userApprovalRule != null) {
            return ask(
                context,
                capability,
                capability.userApprovalRule,
                "Dangerous command requires your approval for this execution",
            ).copy(approvalMode = "user-once")
        }

        if (capability.alwaysAsk) {
            return checkAlwaysAsk(context, capability, projectRules)
        }

        if ((context.mode == CollaborationMode.YOLO || context.mode == CollaborationMode.GUARDED) && !planEnabled) {
            return allow(context, capability, "mode.yolo", "Yolo mode bypasses permission prompts")
        }

        if (context.mode == CollaborationMode.AUTO) {
            return deny(context, capability, "mode.auto.unimplemented", "Auto mode is reserved but not implemented yet")
        }

        if (context.toolName in config.disallowedTools) {
            return deny(context, capability, "rule.disallowedTools", "Tool ${context.toolName} is explicitly disallowed")
        }

        if (matches(projectRules, "deny", context, capability)) {
            return deny(context, capability, "rule.project.deny", "Tool ${context.toolName} is denied by project permission rules")
        }

        if (matchesRules(sessionDeny, context, capability)) {
            return deny(context, capability, "rule.session.deny", "Tool ${context.toolName} is denied for this session")
        }

        if (matches(projectRules, "ask", context, capability)) {
            return ask(context, capability, "rule.project.ask", "Tool ${context.toolName} requires approval by project permission rules")
        }

        if (
            context.toolName == "ReplyToChannel" &&
            capability.permissionName == "channel.reply" &&
            capability.sideEffectScope == "network" &&
            capability.riskLevel == "medium" &&
            !capability.destructive &&
            !capability.needsApproval
        ) {
            return allow(context, capability, "tool.channelReply.preapproved", "The originating bot conversation is already authorized")
        }

        if (planEnabled) {
            return checkPlanMode(context, capability)
        }

        if (matches(projectRules, "allow", context, capability)) {
            return allow(context, capability, "rule.project.allow", "Tool ${context.toolName} is allowed by project permission rules")
        }

        if (isPreapprovedWebFetch(context)) {
            return allow(context, capability, "tool.webfetch.preapproved", "WebFetch URL is preapproved")
        }

        if (isPreapprovedWorkflowDraftWrite(context.toolName, context.input, context.workingDirectory)) {
            return allow(context, capability, "tool.workflowDraft.preapproved", "Workflow draft file is preapproved")
        }

        if (context.toolName in config.allowedTools) {
            return allow(context, capability, "rule.allowedTools", "Tool ${context.toolName} is explicitly allowed")
        }

        if (context.mode == CollaborationMode.EDIT) {
            return checkEditMode(context, capability)
        }

        return checkBuildMode(context, capability)
    }

    private fun checkAlwaysAsk(
        context: PermissionContext,
        capability: ResolvedCapability,
        projectRules: PermissionRuleset?,
    ): PermissionDecisionResult {
        if (context.mode == CollaborationMode.AUTO) {
            return deny(context, capability, "mode.auto.unimplemented", "Auto mode is reserved but not implemented yet")
        }
        if (context.toolName in config.disallowedTools) {
            return deny(context, capability, "rule.disallowedTools", "Tool ${context.toolName} is explicitly disallowed")
        }
        if (matches(projectRules, "deny", context, capability)) {
            return deny(context, capability, "rule.project.deny", "Tool ${context.toolName} is denied by project permission rules")
        }
        if (matchesRules(sessionDeny, context, capability)) {
            return deny(context, capability, "rule.session.deny", "Tool ${context.toolName} is denied for this session")
        }
        if (matches(sessionAllow, "allow", context, capability)) {
            return allow(context, capability, "rule.session.allow", "Tool ${context.toolName} was allowed for this session")
        }
        if (isOwnedWorkflowAmend(context)) {
            return allow(context, capability, "rule.session.workflowOwner", "Tool ${context.toolName} amends a run this session started")
        }
        return ask(context, capability, "tool.alwaysAsk", "Tool ${context.toolName} always requires explicit approval")
    }

    private fun isOwnedWorkflowAmend(context: PermissionContext): Boolean {
        val input = jsonObject(context.input) ?: return false
        return when (context.toolName) {
            "AmendWorkflow" -> isAmendWorkflowOwnedPredecessor(input["predecessor"])
            "FillWorkflowHole" -> isFillWorkflowHoleOwnedRun(input["hole"])
            else -> false
        }
    }

    private fun checkPlanMode(context: PermissionContext, capability: ResolvedCapability): PermissionDecisionResult {
        if (capability.readOnly && !capability.destructive) {
            return allow(context, capability, "mode.plan.readOnly", "Plan mode allows read-only tool execution")
        }
        if (capability.permissionName == "mcp" && !capability.destructive) {
            return allow(context, capability, "mode.plan.mcp", "Plan mode allows non-destructive MCP tool execution")
        }
        if (
            capability.allowedInPlanMode &&
            capability.sideEffectScope == "session" &&
            !capability.destructive &&
            !capability.needsApproval
        ) {
            return allow(
                context,
                capability,
                "mode.plan.explicitSessionCapability",
                "Plan mode allows this explicit non-destructive session control action",
            )
        }
        return deny(context, capability, "mode.plan.nonReadOnly", "Plan mode only allows read-only, non-destructive tools")
    }

    private fun checkBuildMode(context: PermissionContext, capability: ResolvedCapability): PermissionDecisionResult {
        if (capability.readOnly && !capability.destructive && !capability.needsApproval) {
            return allow(context, capability, "mode.build.readOnly", "Build mode allows read-only tools")
        }
        if (capability.riskLevel == "critical") {
            return ask(context, capability, "mode.build.criticalRisk", "Critical risk tools require explicit approval")
        }
        if (capability.riskLevel == "high" && !config.autoApproveHighRisk) {
            return ask(context, capability, "mode.build.highRisk", "High risk tools require explicit approval")
        }
        if (
            capability.sideEffectScope == "session" &&
            capability.riskLevel == "low" &&
            !capability.destructive &&
            !capability.needsApproval
        ) {
            return allow(context, capability, "mode.build.sessionState", "Build mode allows low-risk session-local state updates")
        }
        if (capability.needsApproval || capability.destructive || capability.sideEffectScope != "none") {
            return ask(context, capability, "mode.build.sideEffect", "Tool has side effects and requires approval")
        }
        return allow(context, capability, "mode.build.lowRisk", "Build mode allows low-risk tool execution")
    }

    private fun checkEditMode(context: PermissionContext, capability: ResolvedCapability): PermissionDecisionResult {
        if (capability.permissionName == "edit" && capability.sideEffectScope == "workspace") {
            return allow(context, capability, "mode.edit.fileEdit", "Edit mode allows file edit tools")
        }
        return checkBuildMode(context, capability)
    }

    private fun resolveCapability(context: PermissionContext, tool: PermissionToolCapability?): ResolvedCapability {
        val readOnly = tool?.readOnly ?: isReadOnlyTool(context.toolName)
        val destructive = tool?.destructive ?: (context.toolName == "Bash")
        val scope = tool?.sideEffectScope ?: if (readOnly) "none" else "workspace"
        val risk = tool?.riskLevel ?: defaultRisk(context.toolName, tool)
        return ResolvedCapability(
            allowedInPlanMode = tool?.allowedInPlanMode ?: false,
            alwaysAsk = tool?.alwaysAsk ?: false,
            readOnly = readOnly,
            destructive = destructive,
            requiresUserInteraction = tool?.requiresUserInteraction ?: (scope == "userInteraction"),
            sideEffectScope = scope,
            riskLevel = risk,
            needsApproval = tool?.needsApproval ?: !readOnly,
            permissionName = tool?.permissionName,
            userApprovalRule = tool?.userApprovalRule,
            permissionCapabilityGroup = tool?.permissionCapabilityGroup,
        )
    }

    private fun defaultRisk(name: String, tool: PermissionToolCapability?): String {
        if (tool?.riskLevel != null) return tool.riskLevel
        if (isReadOnlyTool(name)) return "low"
        if (name == "Bash") return "high"
        if (name in setOf("Write", "Edit", "ApplyPatch")) return "medium"
        return "medium"
    }

    private fun isReadOnlyTool(name: String): Boolean = name in setOf(
        "Read", "Glob", "Grep", "WebSearch", "WebFetch", "TodoRead", "TodoWrite", "AskUserQuestion", "Agent", "Task", "Skill",
    )

    private fun isPreapprovedWebFetch(context: PermissionContext): Boolean {
        if (context.toolName != "WebFetch") return false
        val url = jsonString(context.input, "url") ?: return false
        return WebFetchPreapproved.isPreapprovedUrl(url)
    }

    private fun matches(ruleset: PermissionRuleset?, behavior: String, context: PermissionContext, capability: ResolvedCapability): Boolean {
        val rules = when (behavior) {
            "allow" -> ruleset?.allow
            "ask" -> ruleset?.ask
            "deny" -> ruleset?.deny
            else -> null
        }.orEmpty()
        return matchesRules(rules, context, capability)
    }

    private fun matchesRules(rules: List<PermissionRuleValue>, context: PermissionContext, capability: ResolvedCapability): Boolean {
        return rules.any { rule ->
            matchesScope(rule, context.toolName, capability) &&
                (rule.ruleContent == null || subjects(context).any { matchesContent(it, rule.ruleContent) })
        }
    }

    private fun matchesScope(rule: PermissionRuleValue, toolName: String, capability: ResolvedCapability): Boolean {
        if (rule.toolName == "official_cua") {
            return capability.permissionCapabilityGroup == "official_cua"
        }
        if (rule.toolName == toolName) return true
        return toolName == "Write" && rule.toolName == "Edit"
    }

    private fun subjects(context: PermissionContext): List<String> {
        if (context.input is String) return listOf(context.input)
        if (context.toolName == "WebFetch") {
            val url = jsonString(context.input, "url") ?: return emptyList()
            return WebFetchPreapproved.domainSubject(url)?.let { listOf(it) } ?: emptyList()
        }
        for (key in listOf("command", "url", "file_path", "path", "pattern", "patch_text")) {
            val value = jsonString(context.input, key)
            if (value != null) return listOf(value)
        }
        return emptyList()
    }

    private fun matchesContent(subject: String, ruleContent: String): Boolean {
        if (ruleContent.endsWith(":*")) {
            val prefix = ruleContent.dropLast(2)
            return subject == prefix || subject.startsWith("$prefix ") || subject.startsWith("$prefix\t")
        }
        if (ruleContent.contains("*")) {
            val body = ruleContent.split("*").joinToString(".*") { Regex.escape(it) }
            return Regex("^$body$").matches(subject)
        }
        return subject == ruleContent
    }

    private fun allow(context: PermissionContext, capability: ResolvedCapability, ruleId: String, reason: String) =
        result("allow", context, capability, ruleId, reason)

    private fun ask(context: PermissionContext, capability: ResolvedCapability, ruleId: String, reason: String) =
        result("ask", context, capability, ruleId, reason)

    private fun deny(context: PermissionContext, capability: ResolvedCapability, ruleId: String, reason: String) =
        result("deny", context, capability, ruleId, reason)

    private fun result(
        decision: String,
        context: PermissionContext,
        capability: ResolvedCapability,
        ruleId: String,
        reason: String,
    ) = PermissionDecisionResult(
        decision = decision,
        allowed = decision == "allow",
        reason = reason,
        escalated = decision == "ask",
        mode = context.mode,
        ruleId = ruleId,
        riskLevel = capability.riskLevel,
        sideEffectScope = capability.sideEffectScope,
        alwaysAsk = capability.alwaysAsk,
    )

    private data class ResolvedCapability(
        val allowedInPlanMode: Boolean,
        val alwaysAsk: Boolean,
        val readOnly: Boolean,
        val destructive: Boolean,
        val requiresUserInteraction: Boolean,
        val sideEffectScope: String,
        val riskLevel: String,
        val needsApproval: Boolean,
        val permissionName: String?,
        val userApprovalRule: String?,
        val permissionCapabilityGroup: String?,
    )
}

private fun resolvePlanModeTransition(context: PermissionContext): Triple<String, String, String>? {
    if (context.toolName == "EnterPlanMode") {
        return Triple("allow", "tool.plan.enter", "EnterPlanMode switches to plan mode without a permission prompt")
    }
    val planEnabled = context.planEnabled ?: (context.mode == CollaborationMode.PLAN)
    if (context.toolName == "ExitPlanMode" && !planEnabled) {
        return Triple("deny", "mode.plan.exitOnly", "ExitPlanMode can only be used while plan mode is active")
    }
    return null
}
