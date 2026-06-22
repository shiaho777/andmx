package com.andmx.workspace

import android.content.Context
import android.util.Log
import com.andmx.exec.ProcessSpec
import com.andmx.exec.proot.LocalProotEnvironment
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Git Baseline system — mirrors Codex's git baseline repo mechanism.
 *
 * Codex maintains a separate "baseline" git repository that tracks the
 * initial state of the workspace before the agent makes changes. This allows:
 * - Accurate diff generation (comparing against baseline, not HEAD)
 * - Rollback to the pre-agent state
 * - Tracking changes across multiple agent turns
 * - Worktree-aware operation (detecting if we're in a git worktree)
 *
 * The baseline repo is stored in a fixed location and uses read-tree --reset
 * to snapshot the working tree state at session start.
 */
class GitBaseline(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false },
) {
    companion object {
        private const val TAG = "GitBaseline"
        private const val BASELINE_DIR = ".andmx-git-baseline"
    }

    private val runtime = ProotRuntime(context)
    private val env = LocalProotEnvironment(context, runtime)
    private val baselineDir = "${context.filesDir.absolutePath}/$BASELINE_DIR"

    /** Git info snapshot for a workspace. */
    @Serializable
    data class GitInfo(
        val sha: String = "",
        val branch: String = "",
        val originUrl: String = "",
        val hasChanges: Boolean = false,
        val isWorktree: Boolean = false,
        val isRepo: Boolean = false,
    )

    /** Result of a baseline operation. */
    data class BaselineResult(
        val ok: Boolean,
        val message: String = "",
        val gitInfo: GitInfo? = null,
    )

    private val sh get() = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"

    /**
     * Initialize a git baseline repo for the given workspace.
     * This snapshots the current working tree state.
     */
    suspend fun initialize(workspacePath: String): BaselineResult = withContext(Dispatchers.IO) {
        // Ensure git is available
        ensureGit()

        // Collect git info from the workspace
        val gitInfo = collectGitInfo(workspacePath)

        // Initialize the baseline repo
        val script = buildString {
            append("mkdir -p '$baselineDir'; ")
            append("cd '$baselineDir'; ")
            append("git init -q 2>/dev/null; ")
            // Read the workspace tree into the baseline index
            append("cd '$workspacePath'; ")
            append("GIT_DIR='$baselineDir/.git' git add -A 2>/dev/null; ")
            // Commit the baseline
            append("GIT_DIR='$baselineDir/.git' git commit -q -m 'AndMX baseline' --allow-empty 2>/dev/null; ")
            append("echo BASELINE_OK")
        }
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", script)))
        val ok = res.stdout.contains("BASELINE_OK")
        if (!ok) Log.w(TAG, "Baseline init may have failed: ${res.stderr.take(200)}")
        BaselineResult(ok, if (ok) "Baseline initialized" else res.stderr, gitInfo)
    }

    /**
     * Get the diff between the current workspace state and the baseline.
     */
    suspend fun diff(workspacePath: String): String = withContext(Dispatchers.IO) {
        val script = buildString {
            append("cd '$workspacePath'; ")
            append("GIT_DIR='$baselineDir/.git' git diff --no-color HEAD -- . 2>/dev/null; ")
        }
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", script)))
        res.stdout.trim()
    }

    /**
     * Get a list of changed files (relative to baseline).
     */
    suspend fun changedFiles(workspacePath: String): List<String> = withContext(Dispatchers.IO) {
        val script = buildString {
            append("cd '$workspacePath'; ")
            append("GIT_DIR='$baselineDir/.git' git diff --name-only HEAD -- . 2>/dev/null; ")
        }
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", script)))
        res.stdout.lines().filter { it.isNotBlank() }
    }

    /**
     * Reset the workspace back to the baseline state (undo all agent changes).
     * This is a destructive operation.
     */
    suspend fun reset(workspacePath: String): BaselineResult = withContext(Dispatchers.IO) {
        val script = buildString {
            append("cd '$workspacePath'; ")
            append("GIT_DIR='$baselineDir/.git' git checkout -- . 2>/dev/null; ")
            // Also clean untracked files
            append("GIT_DIR='$baselineDir/.git' git clean -fd 2>/dev/null; ")
            append("echo RESET_OK")
        }
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", script)))
        val ok = res.stdout.contains("RESET_OK")
        BaselineResult(ok, if (ok) "Reset to baseline" else res.stderr)
    }

    /**
     * Update the baseline to the current workspace state.
     * Call this after the user has reviewed and accepted changes.
     */
    suspend fun commit(workspacePath: String, message: String = "Agent changes accepted"): BaselineResult = withContext(Dispatchers.IO) {
        val script = buildString {
            append("cd '$workspacePath'; ")
            append("GIT_DIR='$baselineDir/.git' git add -A 2>/dev/null; ")
            append("GIT_DIR='$baselineDir/.git' git commit -q -m '$message' --allow-empty 2>/dev/null; ")
            append("echo COMMIT_OK")
        }
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", script)))
        val ok = res.stdout.contains("COMMIT_OK")
        BaselineResult(ok, if (ok) "Baseline updated" else res.stderr)
    }

    /**
     * Collect git information about a workspace.
     * Mirrors Codex's gitInfo structure (sha, branch, originUrl, hasChanges, isWorktree).
     */
    suspend fun collectGitInfo(workspacePath: String): GitInfo = withContext(Dispatchers.IO) {
        ensureGit()
        val script = buildString {
            append("cd '$workspacePath' 2>/dev/null || exit 0; ")
            append("echo SHA=$(git rev-parse HEAD 2>/dev/null || echo ''); ")
            append("echo BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo ''); ")
            append("echo ORIGIN=$(git remote get-url origin 2>/dev/null || echo ''); ")
            append("echo CHANGES=$(git status --porcelain 2>/dev/null | head -1); ")
            append("echo WORKTREE=$(git rev-parse --is-inside-work-tree 2>/dev/null || echo 'false'); ")
            append("echo ISREPO=$(git rev-parse --is-inside-git-dir 2>/dev/null && echo 'false' || git rev-parse HEAD 2>/dev/null && echo 'true' || echo 'false')")
        }
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", script)))
        val output = res.stdout

        val sha = extractVar(output, "SHA=")
        val branch = extractVar(output, "BRANCH=")
        val origin = extractVar(output, "ORIGIN=")
        val changes = extractVar(output, "CHANGES=")
        val worktree = extractVar(output, "WORKTREE=") == "true"
        val isRepo = sha.isNotEmpty() || branch.isNotEmpty()

        GitInfo(
            sha = sha,
            branch = branch,
            originUrl = origin,
            hasChanges = changes.isNotEmpty(),
            isWorktree = worktree,
            isRepo = isRepo,
        )
    }

    /** Check if a git baseline has been initialized. */
    fun isInitialized(): Boolean = java.io.File(baselineDir, ".git").exists()

    private suspend fun ensureGit() {
        val script = "command -v git >/dev/null 2>&1 || apk add --no-cache git >/dev/null 2>&1"
        env.execute(ProcessSpec(argv = listOf(sh, "-lc", script)))
    }

    private fun extractVar(output: String, prefix: String): String {
        val line = output.lines().firstOrNull { it.startsWith(prefix) } ?: return ""
        return line.removePrefix(prefix).trim()
    }
}
