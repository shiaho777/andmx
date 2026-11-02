package com.andmx.agent.permission

import com.andmx.agent.BashReadonlyPolicy
import com.andmx.agent.permission.guarded.DangerousCommandResult
import com.andmx.agent.permission.guarded.ShellDialect
import com.andmx.agent.permission.guarded.matchDangerousCommand

val GUARDED_DEFAULT_DIALECT = ShellDialect.POSIX

fun bashRuntimeCapability(
    command: String?,
    base: PermissionToolCapability,
    mode: CollaborationMode? = null,
): PermissionToolCapability {
    if (!command.isNullOrEmpty() && mode == CollaborationMode.GUARDED) {
        val match = matchDangerousCommand(command, GUARDED_DEFAULT_DIALECT)
        if (match is DangerousCommandResult.Matched) return base.copy(userApprovalRule = match.ruleId)
    }
    if (command.isNullOrBlank() || !BashReadonlyPolicy.isReadOnly(command)) return base
    return base.copy(
        readOnly = true,
        destructive = false,
        needsApproval = false,
        riskLevel = "low",
        sideEffectScope = "none",
    )
}
