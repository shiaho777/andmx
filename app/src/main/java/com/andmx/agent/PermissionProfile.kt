package com.andmx.agent

/**
 * Managed filesystem sandbox permissions — mirrors Codex's
 * ManagedFileSystemPermissions.
 *
 * Two modes:
 * - [restricted]: Only paths in [entries] are accessible, up to [globScanMaxDepth]
 * - [unrestricted]: All filesystem access allowed (danger-full-access mode)
 */
sealed class ManagedFileSystemPermissions {
    /** Allow access only to listed paths. */
    data class Restricted(
        val entries: List<FsEntry>,
        val globScanMaxDepth: Int = 10,
    ) : ManagedFileSystemPermissions()

    /** Allow all filesystem access. */
    object Unrestricted : ManagedFileSystemPermissions()

    /** A single filesystem permission entry. */
    data class FsEntry(
        val path: String,
        val access: Access = Access.READ_WRITE,
        val kind: Kind = Kind.DIRECTORY,
    ) {
        enum class Access { READ_ONLY, READ_WRITE }
        enum class Kind { FILE, DIRECTORY, GLOB }
    }
}

/**
 * Permission profile — mirrors Codex's AdditionalPermissionProfile.
 *
 * An overlay that can extend the base sandbox permissions for a session or
 * individual command. Used for "always allow" style approvals.
 */
data class PermissionProfile(
    val name: String,
    val sandboxMode: SandboxMode,
    val networkAccess: Boolean = false,
    val additionalWritableRoots: List<String> = emptyList(),
) {
    enum class SandboxMode {
        READ_ONLY,
        WORKSPACE_WRITE,
        DANGER_FULL_ACCESS,
    }

    companion object {
        val READ_ONLY = PermissionProfile(
            name = "read-only",
            sandboxMode = SandboxMode.READ_ONLY,
            networkAccess = false,
        )

        val WORKSPACE_WRITE = PermissionProfile(
            name = "workspace-write",
            sandboxMode = SandboxMode.WORKSPACE_WRITE,
            networkAccess = true,
        )

        val DANGER_FULL_ACCESS = PermissionProfile(
            name = "danger-full-access",
            sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
            networkAccess = true,
        )
    }
}

/**
 * Guardian action types — mirrors Codex's Guardian approval categories.
 */
enum class ActionType(val wire: String) {
    EXECVE("execve"),
    APPLY_PATCH("applyPatch"),
    NETWORK_ACCESS("networkAccess"),
    MCP_TOOL_CALL("mcpToolCall"),
    REQUEST_PERMISSIONS("requestPermissions"),
}
