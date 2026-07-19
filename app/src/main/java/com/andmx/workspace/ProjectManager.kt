package com.andmx.workspace

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class WorkspaceKind { LOCAL, REMOTE }

class ProjectManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val guestMountPath: String = "/root/project"

    private val _hostPath = MutableStateFlow(prefs.getString(KEY_HOST_PATH, null))
    val hostPath: StateFlow<String?> = _hostPath.asStateFlow()

    private val _workspaceKind = MutableStateFlow(
        runCatching { WorkspaceKind.valueOf(prefs.getString(KEY_KIND, WorkspaceKind.LOCAL.name)!!) }
            .getOrDefault(WorkspaceKind.LOCAL),
    )
    val workspaceKind: StateFlow<WorkspaceKind> = _workspaceKind.asStateFlow()

    private val _remoteSpecJson = MutableStateFlow(prefs.getString(KEY_REMOTE_SPEC, null))
    val remoteSpecJson: StateFlow<String?> = _remoteSpecJson.asStateFlow()

    private val _recentPaths = MutableStateFlow(loadRecent())
    val recentPaths: StateFlow<List<String>> = _recentPaths.asStateFlow()

    val hasProject: Boolean get() = _hostPath.value != null

    val isRemote: Boolean get() = _workspaceKind.value == WorkspaceKind.REMOTE

    val projectName: String
        get() = when {
            isRemote -> remoteLabel()
            else -> displayName(_hostPath.value)
        }

    fun selectProject(path: String) {
        val normalized = path.trimEnd('/')
        prefs.edit()
            .putString(KEY_HOST_PATH, normalized)
            .putString(KEY_KIND, WorkspaceKind.LOCAL.name)
            .remove(KEY_REMOTE_SPEC)
            .apply()
        _hostPath.value = normalized
        _workspaceKind.value = WorkspaceKind.LOCAL
        _remoteSpecJson.value = null
        com.andmx.exec.proot.ProotRuntime.extraBind = normalized to guestMountPath
        pushRecent(normalized)
        WorkspaceIndex.get(appContext).onProjectSelected(normalized)
    }

    fun selectRemoteProject(spec: RemoteWorkspaceSpec) {
        val uri = spec.workspaceUri
        val encoded = json.encodeToString(RemoteWorkspaceSpec.serializer(), spec)
        prefs.edit()
            .putString(KEY_HOST_PATH, uri)
            .putString(KEY_KIND, WorkspaceKind.REMOTE.name)
            .putString(KEY_REMOTE_SPEC, encoded)
            .apply()
        _hostPath.value = uri
        _workspaceKind.value = WorkspaceKind.REMOTE
        _remoteSpecJson.value = encoded
        com.andmx.exec.proot.ProotRuntime.extraBind = null
        pushRecent(uri)
    }

    fun currentRemoteSpec(): RemoteWorkspaceSpec? {
        if (!isRemote) return null
        val raw = _remoteSpecJson.value ?: return null
        return runCatching {
            json.decodeFromString(RemoteWorkspaceSpec.serializer(), raw)
        }.getOrNull()
    }

    fun clearProject() {
        prefs.edit()
            .remove(KEY_HOST_PATH)
            .remove(KEY_REMOTE_SPEC)
            .putString(KEY_KIND, WorkspaceKind.LOCAL.name)
            .apply()
        _hostPath.value = null
        _workspaceKind.value = WorkspaceKind.LOCAL
        _remoteSpecJson.value = null
        com.andmx.exec.proot.ProotRuntime.extraBind = null
        WorkspaceIndex.get(appContext).onProjectSelected(null)
    }

    fun restoreBinding() {
        if (isRemote) {
            com.andmx.exec.proot.ProotRuntime.extraBind = null
        } else {
            _hostPath.value?.let { com.andmx.exec.proot.ProotRuntime.extraBind = it to guestMountPath }
        }
    }

    fun suggestedRoots(): List<String> {
        val sd = Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
        return listOf(
            "$sd/Documents",
            "$sd/Download",
            "$sd",
        ).filter { File(it).exists() }
    }

    fun workspaceCandidates(): List<String> {
        val recent = _recentPaths.value
        val suggested = suggestedRoots()
        val current = _hostPath.value
        val out = LinkedHashSet<String>()
        current?.let { out.add(it) }
        recent.forEach { out.add(it) }
        suggested.forEach { out.add(it) }
        return out.toList().filter {
            it.startsWith("ssh://") || File(it).exists() || it == current
        }
    }

    private fun remoteLabel(): String {
        val spec = currentRemoteSpec()
        if (spec != null) return spec.displayName
        val path = _hostPath.value ?: return "远程工作区"
        return displayName(path.removePrefix("ssh://").substringAfter('/').let {
            if (it.startsWith("/")) it else "/$it"
        }.ifBlank { path })
    }

    private fun pushRecent(path: String) {
        val next = (listOf(path) + _recentPaths.value.filter { it != path }).take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, next.joinToString("\n")).apply()
        _recentPaths.value = next
    }

    private fun loadRecent(): List<String> {
        val raw = prefs.getString(KEY_RECENT, "") ?: ""
        val current = prefs.getString(KEY_HOST_PATH, null)
        return (raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() } + listOfNotNull(current))
            .distinct()
            .take(MAX_RECENT)
    }



    companion object {
        private const val PREFS = "andmx_project"
        private const val KEY_HOST_PATH = "host_path"
        private const val KEY_RECENT = "recent_paths"
        private const val KEY_KIND = "workspace_kind"
        private const val KEY_REMOTE_SPEC = "remote_spec_json"
        private const val MAX_RECENT = 12

        fun displayName(path: String?): String {
            if (path.isNullOrBlank()) return "选择项目"
            if (path.startsWith("ssh://")) {
                val after = path.removePrefix("ssh://")
                val hostPart = after.substringBefore('/')
                val remotePath = after.substringAfter('/', missingDelimiterValue = "")
                val leaf = remotePath.trimEnd('/').substringAfterLast('/').ifBlank { remotePath }
                return listOfNotNull(hostPart.takeIf { it.isNotBlank() }, leaf.takeIf { it.isNotBlank() })
                    .joinToString(":")
                    .ifBlank { path }
            }
            return path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        }
    }
}
