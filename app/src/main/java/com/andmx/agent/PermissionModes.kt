package com.andmx.agent

import com.andmx.agent.permission.CollaborationMode
import com.andmx.agent.permission.PermissionContext
import com.andmx.agent.permission.PermissionService
import com.andmx.agent.permission.PermissionToolCapability

object PermissionModes {
    enum class Kind { BUILD, EDIT, AUTO, YOLO, PLAN, GUARDED }
    enum class Effect { ALLOW, ASK, DENY }

    data class Result(val effect: Effect, val reason: String, val ruleId: String)

    fun decide(kind: Kind, tool: Tool): Result {
        val mode = when (kind) {
            Kind.BUILD -> CollaborationMode.BUILD
            Kind.EDIT -> CollaborationMode.EDIT
            Kind.AUTO -> CollaborationMode.AUTO
            Kind.YOLO -> CollaborationMode.YOLO
            Kind.PLAN -> CollaborationMode.PLAN
            Kind.GUARDED -> CollaborationMode.GUARDED
        }
        val decision = PermissionService().checkPermission(
            PermissionContext(toolName = tool.name, mode = mode, riskLevel = tool.riskLevel),
            PermissionToolCapability(
                userApprovalRule = tool.userApprovalRule,
                allowedInPlanMode = tool.allowedInPlanMode,
                alwaysAsk = tool.alwaysAsk,
                readOnly = tool.readOnly,
                destructive = tool.destructive,
                requiresUserInteraction = tool.requiresUserInteraction,
                sideEffectScope = tool.sideEffectScope,
                riskLevel = tool.riskLevel,
                needsApproval = tool.needsApproval,
                permissionName = tool.permissionName,
            ),
        )
        val effect = when (decision.decision) {
            "allow" -> Effect.ALLOW
            "deny" -> Effect.DENY
            else -> Effect.ASK
        }
        return Result(effect, decision.reason.orEmpty(), decision.ruleId)
    }
}
