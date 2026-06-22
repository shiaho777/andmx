package com.andmx.workspace

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Manages the "project" as a **real directory on the phone's storage** that the
 * user picks (e.g. /sdcard/Documents/my-app), then bind-mounts into the proot
 * guest at `/root/project` so the agent operates on the user's actual files —
 * not an isolated sandbox copy. This mirrors Codex: a project is the user's
 * chosen cwd, never a built-in folder.
 *
 * Storage access uses MANAGE_EXTERNAL_STORAGE so proot can access the path
 * directly via the kernel filesystem (no SAF content-URI indirection). The
 * bind mount is set up by [com.andmx.exec.proot.ProotRuntime] when it builds
 * the proot argv.
 */
class ProjectManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The guest-side path where the active project is always mounted. */
    val guestMountPath: String = "/root/project"

    private val _hostPath = MutableStateFlow(prefs.getString(KEY_HOST_PATH, null))
    /** The phone-side directory (e.g. /sdcard/Documents/my-app), or null if none selected. */
    val hostPath: StateFlow<String?> = _hostPath.asStateFlow()

    /** True if a project directory has been chosen and persisted. */
    val hasProject: Boolean get() = _hostPath.value != null

    /** Display name for the current project (last path segment of the host dir). */
    val projectName: String
        get() = _hostPath.value?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { "项目" } ?: "未选择"

    /**
     * Adopt a phone-side directory as the active project. [path] must be an
     * absolute filesystem path accessible with MANAGE_EXTERNAL_STORAGE (e.g.
     * `/sdcard/Documents/my-app`). Persists the choice and notifies
     * [hostPath] observers so ProotRuntime re-binds on next proot launch.
     */
    fun selectProject(path: String) {
        prefs.edit().putString(KEY_HOST_PATH, path).apply()
        _hostPath.value = path
        // Configure the proot bind mount so all guest processes see this
        // directory at /root/project (the agent's working directory).
        com.andmx.exec.proot.ProotRuntime.extraBind = path to guestMountPath
    }

    /** Clear the project binding (back to "no project"). */
    fun clearProject() {
        prefs.edit().remove(KEY_HOST_PATH).apply()
        _hostPath.value = null
        com.andmx.exec.proot.ProotRuntime.extraBind = null
    }

    /**
     * Re-apply the persisted bind mount on app start (so proot picks it up
     * after a process restart without the user re-selecting).
     */
    fun restoreBinding() {
        _hostPath.value?.let { com.andmx.exec.proot.ProotRuntime.extraBind = it to guestMountPath }
    }

    /**
     * Suggest common starting directories for a directory picker (so the UI can
     * offer quick-access locations). These are the standard Android public dirs.
     */
    fun suggestedRoots(): List<String> {
        val sd = Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
        return listOf(
            "$sd/Documents",
            "$sd/Download",
            "$sd",
        )
    }

    companion object {
        private const val PREFS = "andmx_project"
        private const val KEY_HOST_PATH = "host_path"
    }
}
