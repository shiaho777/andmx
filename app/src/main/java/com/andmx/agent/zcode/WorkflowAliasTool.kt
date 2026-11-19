package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import kotlinx.serialization.json.JsonObject

class WorkflowAliasTool(private val delegate: Tool) : Tool {
    override val name = "Workflow"
    override val description =
        "Legacy alias of CreateWorkflow. Prefer CreateWorkflow; this name accepts the same arguments."
    override val parameters: JsonObject = delegate.parameters
    override val risk: ToolRisk = delegate.risk
    override val readOnly = delegate.readOnly
    override val concurrentSafe = delegate.concurrentSafe
    override val needsApproval = delegate.needsApproval
    override val timeoutMs = delegate.timeoutMs
    override val maxOutputBytes = delegate.maxOutputBytes
    override val sideEffectScope = delegate.sideEffectScope
    override val riskLevel = delegate.riskLevel
    override val permissionName = delegate.permissionName
    override val alwaysAsk = delegate.alwaysAsk
    override val destructive = delegate.destructive
    override val shouldDefer = delegate.shouldDefer
    override val stopTurnOnSuccess = delegate.stopTurnOnSuccess
    override val userApprovalRule = delegate.userApprovalRule

    override suspend fun execute(args: JsonObject): ToolResult = delegate.execute(args)
}