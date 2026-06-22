package com.andmx.agent.suggestions

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Ambient Suggestions — mirrors Codex's ambient-suggestions system.
 *
 * Provides proactive, context-aware suggestions to the user without requiring
 * an explicit prompt. Suggestions are generated based on:
 * - Recent file changes (from ChangeTracker)
 * - Git status (uncommitted changes, untracked files)
 * - Error patterns in recent tool outputs
 * - Time-based heuristics (e.g., "it's been a while since you committed")
 * - Workspace state (missing dependencies, TODO comments, etc.)
 *
 * Suggestions are low-priority and non-intrusive — they appear in a side panel
 * or notification area, not in the main conversation flow.
 */
class AmbientSuggestions(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false },
) {
    companion object {
        private const val TAG = "AmbientSuggestions"
        private const val SUGGESTIONS_DIR = "ambient-suggestions"
    }

    private val suggestionsDir = File(context.filesDir, SUGGESTIONS_DIR).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    data class Suggestion(
        val id: String,
        val type: SuggestionType,
        val title: String,
        val detail: String,
        val action: String = "",
        val priority: Int = 0,  // 0=low, 1=medium, 2=high
        val timestamp: Long = System.currentTimeMillis(),
        val dismissed: Boolean = false,
    )

    enum class SuggestionType {
        GIT_UNCOMMITTED,
        GIT_UNTRACKED,
        BUILD_ERROR,
        TEST_FAILURE,
        DEPENDENCY_MISSING,
        TODO_FOUND,
        CONTEXT_PRESSURE,
        SESSION_IDLE,
        CODE_SMELL,
        SECURITY_WARNING,
    }

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    init {
        loadPersisted()
    }

    /** Add a new suggestion. */
    fun add(suggestion: Suggestion) {
        val updated = (_suggestions.value.filterNot { it.id == suggestion.id } + suggestion)
            .sortedByDescending { it.priority }
            .take(50)
        _suggestions.value = updated
        persist(updated)
    }

    /** Add multiple suggestions at once. */
    fun addAll(newSuggestions: List<Suggestion>) {
        val existingIds = _suggestions.value.map { it.id }.toSet()
        val filtered = newSuggestions.filter { it.id !in existingIds }
        val updated = (_suggestions.value + filtered)
            .sortedByDescending { it.priority }
            .take(50)
        _suggestions.value = updated
        persist(updated)
    }

    /** Dismiss a suggestion by ID. */
    fun dismiss(id: String) {
        val updated = _suggestions.value.map {
            if (it.id == id) it.copy(dismissed = true) else it
        }
        _suggestions.value = updated
        persist(updated)
    }

    /** Remove a suggestion entirely. */
    fun remove(id: String) {
        val updated = _suggestions.value.filterNot { it.id == id }
        _suggestions.value = updated
        persist(updated)
    }

    /** Clear all suggestions. */
    fun clear() {
        _suggestions.value = emptyList()
        persist(emptyList())
    }

    /** Get active (non-dismissed) suggestions. */
    fun active(): List<Suggestion> = _suggestions.value.filter { !it.dismissed }

    /**
     * Scan for suggestions based on workspace state.
     * Called periodically or on specific triggers.
     */
    suspend fun scan(
        changedFiles: List<String>,
        recentErrors: List<String> = emptyList(),
        tokenEstimate: Int = 0,
        contextWindow: Int = 128_000,
        gitHasChanges: Boolean = false,
        untrackedFiles: List<String> = emptyList(),
        idleSeconds: Long = 0,
    ) {
        val newSuggestions = mutableListOf<Suggestion>()

        if (gitHasChanges && changedFiles.isNotEmpty()) {
            newSuggestions.add(Suggestion(
                id = "git-uncommitted",
                type = SuggestionType.GIT_UNCOMMITTED,
                title = "有未提交的变更",
                detail = "${changedFiles.size} 个文件有未提交的变更: ${changedFiles.take(3).joinToString(", ")}${if (changedFiles.size > 3) "..." else ""}",
                action = "考虑提交这些变更以保存进度",
                priority = 1,
            ))
        }

        if (untrackedFiles.isNotEmpty()) {
            newSuggestions.add(Suggestion(
                id = "git-untracked",
                type = SuggestionType.GIT_UNTRACKED,
                title = "有未跟踪的文件",
                detail = "${untrackedFiles.size} 个未跟踪文件: ${untrackedFiles.take(3).joinToString(", ")}",
                action = "将这些文件加入 .gitignore 或 git add",
                priority = 0,
            ))
        }

        recentErrors.forEachIndexed { idx, error ->
            if (error.contains(Regex("error:|Error:|ERROR|failed|FAIL"))) {
                newSuggestions.add(Suggestion(
                    id = "error-$idx-${error.hashCode()}",
                    type = SuggestionType.BUILD_ERROR,
                    title = "检测到构建/运行错误",
                    detail = error.take(200),
                    action = "检查错误并修复",
                    priority = 2,
                ))
            }
        }

        val pressure = tokenEstimate.toDouble() / contextWindow
        if (pressure > 0.75) {
            newSuggestions.add(Suggestion(
                id = "context-pressure",
                type = SuggestionType.CONTEXT_PRESSURE,
                title = "上下文窗口压力较高",
                detail = "当前上下文使用约 ${(pressure * 100).toInt()}% 的窗口 (${tokenEstimate}/${contextWindow} tokens)",
                action = "考虑压缩上下文或开始新会话",
                priority = if (pressure > 0.9) 2 else 1,
            ))
        }

        if (idleSeconds > 300) {
            newSuggestions.add(Suggestion(
                id = "session-idle",
                type = SuggestionType.SESSION_IDLE,
                title = "会话已空闲",
                detail = "会话已空闲 ${idleSeconds / 60} 分钟",
                action = "继续之前的工作或开始新任务",
                priority = 0,
            ))
        }

        if (newSuggestions.isNotEmpty()) {
            addAll(newSuggestions)
        }
    }

    private fun persist(list: List<Suggestion>) {
        scope.launch {
            runCatching {
                val file = File(suggestionsDir, "suggestions.json")
                file.writeText(json.encodeToString(ListSerializer(Suggestion.serializer()), list))
            }.onFailure { Log.w(TAG, "Failed to persist suggestions: ${it.message}") }
        }
    }

    private fun loadPersisted() {
        val file = File(suggestionsDir, "suggestions.json")
        if (!file.exists()) return
        runCatching {
            _suggestions.value = json.decodeFromString(ListSerializer(Suggestion.serializer()), file.readText())
        }.onFailure { Log.w(TAG, "Failed to load suggestions: ${it.message}") }
    }

    fun shutdown() {
        scope.cancel()
    }
}
