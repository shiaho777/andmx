package com.andmx.agent.hooks

import com.andmx.workspace.ProjectManager
import com.andmx.workspace.WorkspaceAccess

/**
 * Discovery for workspace-owned hooks. The file is read relative to the current
 * workspace root, so local and SSH workspaces both use the same path.
 */
object ProjectHooks {
    const val RELATIVE_PATH = ".zcode/hooks.json"

    data class Snapshot(
        val workspaceKey: String,
        val path: String,
        val raw: String,
        val fingerprint: String,
        val hooks: List<HookSystem.HookConfig>,
        val trusted: Boolean,
    )

    fun workspaceKey(projectManager: ProjectManager): String =
        projectManager.hostPath.value?.takeIf { it.isNotBlank() }
            ?: projectManager.guestMountPath

    suspend fun load(access: WorkspaceAccess, workspaceKey: String): Snapshot? {
        if (workspaceKey.isBlank()) return null
        val exists = runCatching { access.exists(RELATIVE_PATH) }.getOrDefault(false)
        if (!exists) return null
        val raw = runCatching { access.readText(RELATIVE_PATH) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val fingerprint = HookTrustStore.fingerprint(raw)
        val hooks = HookConfigParser.parse(
            raw = raw,
            source = HookSystem.HookSource.PROJECT,
            cwd = access.guestCwd(),
        )
        return Snapshot(
            workspaceKey = workspaceKey,
            path = access.resolvePath(RELATIVE_PATH),
            raw = raw,
            fingerprint = fingerprint,
            hooks = hooks,
            trusted = HookTrustStore.isTrusted(workspaceKey, fingerprint),
        )
    }
}
