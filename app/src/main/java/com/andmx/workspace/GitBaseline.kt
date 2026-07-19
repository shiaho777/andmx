package com.andmx.workspace

import android.content.Context
import android.util.Log
import com.andmx.exec.ExecutionEnvironment
import com.andmx.exec.ProcessSpec
import com.andmx.exec.proot.LocalProotEnvironment
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.remote.RemoteSshEnvironment
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

    private val appContext = context.applicationContext
    private val runtime = ProotRuntime(context)
    private val localEnv = LocalProotEnvironment(context, runtime)
    private val projectManager = ProjectManager(context)
    private val baselineDir = "${context.filesDir.absolutePath}/$BASELINE_DIR"

    /** Git info snapshot for a workspace. */
    @Serializable
    data class GitInfo(
        val sha: String = "",
        val branch: String = "",
        val originUrl: String = "",
        val hasChanges: Boolean = false,
        val dirtyFileCount: Int = 0,
        val isWorktree: Boolean = false,
        val isRepo: Boolean = false,
        val trackingBranch: String = "",
        val ahead: Int = 0,
        val behind: Int = 0,
        val hasIdentity: Boolean = false,
        val userName: String = "",
        val userEmail: String = "",
    ) {
        val hasUpstream: Boolean get() = trackingBranch.isNotBlank()
        val needsPush: Boolean get() = isRepo && branch.isNotBlank() && (ahead > 0 || !hasUpstream)
    }

    /** Result of a baseline operation. */
    data class BaselineResult(
        val ok: Boolean,
        val message: String = "",
        val gitInfo: GitInfo? = null,
    )

    private val localSh get() = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"

    private data class ExecTarget(
        val env: ExecutionEnvironment,
        val workspacePath: String,
        val sh: String,
        val isRemote: Boolean,
    )

    private fun resolveTarget(workspacePath: String): ExecTarget {
        val remoteSpec = when {
            workspacePath.startsWith("ssh://") -> projectManager.currentRemoteSpec()
            projectManager.isRemote && projectManager.hostPath.value == workspacePath ->
                projectManager.currentRemoteSpec()
            else -> null
        }
        if (remoteSpec != null) {
            val remotePath = remoteSpec.remotePath.ifBlank {
                parseRemotePath(workspacePath)
            }.ifBlank { "~" }
            return ExecTarget(
                env = RemoteSshEnvironment(appContext, remoteSpec, remotePath),
                workspacePath = remotePath,
                sh = "/bin/sh",
                isRemote = true,
            )
        }
        return ExecTarget(
            env = localEnv,
            workspacePath = workspacePath,
            sh = localSh,
            isRemote = false,
        )
    }

    private fun parseRemotePath(uri: String): String {
        if (!uri.startsWith("ssh://")) return uri
        val after = uri.removePrefix("ssh://")
        val slash = after.indexOf('/')
        if (slash < 0) return "~"
        val path = after.substring(slash)
        return path.ifBlank { "~" }
    }

    private suspend fun runShell(workspacePath: String, script: String, login: Boolean = true): com.andmx.exec.ProcessResult {
        val target = resolveTarget(workspacePath)
        val flag = if (login) "-lc" else "-c"
        val actual = target.workspacePath
        val rewritten = if (target.isRemote && workspacePath != actual) {
            script
                .replace("cd '$workspacePath'", "cd '$actual'")
                .replace("cd \"$workspacePath\"", "cd \"$actual\"")
                .replace("'$workspacePath'", "'$actual'")
                .replace("\"$workspacePath\"", "\"$actual\"")
        } else {
            script
        }
        return target.env.execute(
            ProcessSpec(
                argv = listOf(target.sh, flag, rewritten),
                workingDir = actual,
                redirectErrorStream = true,
            ),
        )
    }


    /**
     * Initialize a git baseline repo for the given workspace.
     * This snapshots the current working tree state.
     */
    suspend fun initialize(workspacePath: String): BaselineResult = withContext(Dispatchers.IO) {
        // Ensure git is available
        ensureGit(workspacePath)

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
        val res = runShell(workspacePath, script, login = true)
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
        val res = runShell(workspacePath, script, login = true)
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
        val res = runShell(workspacePath, script, login = true)
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
        val res = runShell(workspacePath, script, login = true)
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
        val res = runShell(workspacePath, script, login = true)
        val ok = res.stdout.contains("COMMIT_OK")
        BaselineResult(ok, if (ok) "Baseline updated" else res.stderr)
    }

    /**
     * Collect git information about a workspace.
     * Mirrors Codex's gitInfo structure (sha, branch, originUrl, hasChanges, isWorktree).
     */
    suspend fun collectGitInfo(workspacePath: String): GitInfo = withContext(Dispatchers.IO) {
        ensureGit(workspacePath)
        val script = buildString {
            append("cd '$workspacePath' 2>/dev/null || exit 0; ")
            append("echo SHA=$(git rev-parse HEAD 2>/dev/null || echo ''); ")
            append("echo BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo ''); ")
            append("echo ORIGIN=$(git remote get-url origin 2>/dev/null || echo ''); ")
            append("echo CHANGES=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' '); ")
            append("echo WORKTREE=$(git rev-parse --is-inside-work-tree 2>/dev/null || echo 'false'); ")
            append("echo ISREPO=$(git rev-parse --is-inside-work-tree 2>/dev/null || echo 'false'); ")
            append("echo TOPLEVEL=$(git rev-parse --show-toplevel 2>/dev/null || echo ''); ")
            append("echo TRACKING=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || echo ''); ")
            append("echo AHEAD_BEHIND=$(git rev-list --left-right --count '@{u}...HEAD' 2>/dev/null || echo '0	0'); ")
            append("echo UNAME=$(git config user.name 2>/dev/null || echo ''); ")
            append("echo UEMAIL=$(git config user.email 2>/dev/null || echo '')")
        }
        val res = runShell(workspacePath, script, login = true)
        val output = res.stdout

        val sha = extractVar(output, "SHA=")
        val branch = extractVar(output, "BRANCH=")
        val origin = extractVar(output, "ORIGIN=")
        val dirtyCount = extractVar(output, "CHANGES=").toIntOrNull() ?: 0
        val worktree = extractVar(output, "WORKTREE=") == "true"
        val topLevel = extractVar(output, "TOPLEVEL=").trimEnd('/')
        val wsNorm = workspacePath.trimEnd('/')
        val inside = extractVar(output, "ISREPO=") == "true" || extractVar(output, "WORKTREE=") == "true"
        val isRepo = inside && topLevel.isNotBlank() && (
            topLevel == wsNorm || topLevel.endsWith(wsNorm) || wsNorm.endsWith(topLevel)
        )
        val tracking = extractVar(output, "TRACKING=")
        val ab = extractVar(output, "AHEAD_BEHIND=").split('	', ' ').filter { it.isNotBlank() }
        val behind = ab.getOrNull(0)?.toIntOrNull() ?: 0
        val ahead = ab.getOrNull(1)?.toIntOrNull() ?: 0
        val uname = extractVar(output, "UNAME=")
        val uemail = extractVar(output, "UEMAIL=")

        if (!isRepo) return@withContext GitInfo()
        GitInfo(
            sha = sha,
            branch = branch,
            originUrl = origin,
            hasChanges = dirtyCount > 0,
            dirtyFileCount = dirtyCount,
            isWorktree = worktree,
            isRepo = true,
            trackingBranch = tracking,
            ahead = ahead,
            behind = behind,
            hasIdentity = uname.isNotBlank() && uemail.isNotBlank(),
            userName = uname,
            userEmail = uemail,
        )
    }

    /**
     * Host-side git probe that does not depend on proot.
     * Reads `.git/HEAD` and `git status --porcelain` when host `git` is available,
     * so the empty-composer branch pill still appears before the guest is ready.
     */
    fun collectHostGitInfo(hostWorkspacePath: String): GitInfo {
        val root = java.io.File(hostWorkspacePath)
        val gitDir = resolveGitDir(root) ?: return GitInfo()
        val headFile = java.io.File(gitDir, "HEAD")
        if (!headFile.isFile) return GitInfo()
        val head = runCatching { headFile.readText().trim() }.getOrDefault("")
        val branch = when {
            head.startsWith("ref: refs/heads/") -> head.removePrefix("ref: refs/heads/").trim()
            head.startsWith("ref:") -> head.substringAfterLast('/').trim()
            head.isNotBlank() -> "HEAD"
            else -> ""
        }
        if (branch.isBlank()) return GitInfo()
        val sha = when {
            head.startsWith("ref:") -> {
                val refPath = head.removePrefix("ref:").trim()
                val refFile = java.io.File(gitDir, refPath)
                runCatching { refFile.readText().trim() }.getOrDefault("")
            }
            else -> head
        }
        val dirtyCount = hostGitDirtyCount(root.absolutePath)
        return GitInfo(
            sha = sha,
            branch = branch,
            hasChanges = dirtyCount > 0,
            dirtyFileCount = dirtyCount,
            isRepo = true,
        )
    }

    private fun findGitRoot(start: java.io.File): java.io.File? {
        var cursor: java.io.File? = start
        var depth = 0
        while (cursor != null && depth < 24) {
            if (resolveGitDir(cursor) != null) return cursor
            val parent = cursor.parentFile
            if (parent == null || parent == cursor) break
            cursor = parent
            depth++
        }
        return null
    }

    private fun resolveGitDir(root: java.io.File): java.io.File? {
        val git = java.io.File(root, ".git")
        if (git.isDirectory) {
            val head = java.io.File(git, "HEAD")
            if (head.isFile) return git
            return null
        }
        if (git.isFile) {
            val content = runCatching { git.readText().trim() }.getOrDefault("")
            if (content.startsWith("gitdir:")) {
                val rel = content.removePrefix("gitdir:").trim()
                val dir = java.io.File(rel)
                val resolved = if (dir.isAbsolute) dir else java.io.File(root, rel)
                if (resolved.isDirectory) return resolved
            }
        }
        return null
    }

    private fun hostGitDirtyCount(hostPath: String): Int {
        return runCatching {
            val pb = ProcessBuilder("git", "-C", hostPath, "status", "--porcelain")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0) 0 else out.lineSequence().count { it.isNotBlank() }
        }.getOrDefault(0)
    }

    /** Check if a git baseline has been initialized. */
    fun isInitialized(): Boolean = java.io.File(baselineDir, ".git").exists()

    private suspend fun ensureGit(workspacePath: String? = null) {
        val script =
            "command -v git >/dev/null 2>&1 || " +
                "(command -v apk >/dev/null 2>&1 && apk add --no-cache git >/dev/null 2>&1) || true"
        if (workspacePath != null) {
            runShell(workspacePath, script, login = true)
        } else {
            localEnv.execute(
                ProcessSpec(
                    argv = listOf(localSh, "-lc", script),
                    redirectErrorStream = true,
                ),
            )
        }
    }

    /**
     * List local branches of the workspace repo. The current branch is marked
     * with a leading `*` in git's output. Returns (name, isCurrent) pairs.
     * Returns empty list if not a git repo or git unavailable.
     */
    suspend fun listBranches(workspacePath: String): List<BranchInfo> = withContext(Dispatchers.IO) {
        ensureGit(workspacePath)
        val script = "cd '$workspacePath' 2>/dev/null && git branch --list 2>/dev/null"
        val res = runShell(workspacePath, script, login = false)
        res.stdout.lines()
            .mapNotNull { line ->
                val current = line.startsWith("*")
                val name = line.removePrefix("*").trim()
                if (name.isNotEmpty()) BranchInfo(name, current) else null
            }
    }

    fun listHostBranches(hostWorkspacePath: String): List<BranchInfo> {
        val root = java.io.File(hostWorkspacePath)
        if (!root.isDirectory) return emptyList()
        val fromGit = runCatching {
            val pb = ProcessBuilder("git", "-C", hostWorkspacePath, "branch", "--list")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) emptyList()
            else out.lines().mapNotNull { line ->
                val current = line.startsWith("*")
                val name = line.removePrefix("*").trim()
                if (name.isNotEmpty()) BranchInfo(name, current) else null
            }
        }.getOrDefault(emptyList())
        if (fromGit.isNotEmpty()) return fromGit
        val gitDir = resolveGitDir(root) ?: return emptyList()
        val heads = java.io.File(gitDir, "refs/heads")
        if (!heads.isDirectory) return emptyList()
        val current = collectHostGitInfo(hostWorkspacePath).branch
        return heads.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(heads).path }
            .sorted()
            .map { BranchInfo(it, it == current) }
            .toList()
    }

    /** Branch list entry. */
    data class BranchInfo(val name: String, val isCurrent: Boolean)


    /**
     * Checkout an existing branch, or create+checkout a new one when
     * [create] is true. Returns the result message from git.
     */
    suspend fun checkout(
        workspacePath: String,
        branch: String,
        create: Boolean = false,
    ): BaselineResult = withContext(Dispatchers.IO) {
        switchBranch(workspacePath, branch, create).toBaselineResult()
    }

    data class ChangedFile(
        val path: String,
        val status: String,
        val untracked: Boolean,
    )

    enum class SwitchIssue {
        NONE,
        TRACKED_OVERWRITE,
        UNTRACKED_OVERWRITE,
        CONFLICTS,
        INVALID_NAME,
        ALREADY_EXISTS,
        NOT_FOUND,
        UNKNOWN,
    }

    data class SwitchResult(
        val ok: Boolean,
        val created: Boolean = false,
        val message: String = "",
        val issue: SwitchIssue = SwitchIssue.NONE,
        val affectedFiles: List<String> = emptyList(),
        val dirtyFiles: List<ChangedFile> = emptyList(),
        val gitInfo: GitInfo? = null,
    ) {
        fun toBaselineResult() = BaselineResult(ok, message, gitInfo)
    }

    suspend fun listChangedFiles(workspacePath: String): List<ChangedFile> = withContext(Dispatchers.IO) {
        ensureGit(workspacePath)
        val script = "cd '$workspacePath' 2>/dev/null && git status --porcelain 2>/dev/null"
        val res = runShell(workspacePath, script, login = false)
        res.stdout.lines().mapNotNull { line ->
            if (line.length < 4) return@mapNotNull null
            val code = line.take(2)
            val path = line.drop(3).trim().trim('"', '\'')
            if (path.isEmpty()) null
            else ChangedFile(
                path = path,
                status = code.trim(),
                untracked = code == "??",
            )
        }
    }

    /**
     * ZCode-aligned branch switch: attempts checkout/create; on overwrite risk
     * returns TRACKED_OVERWRITE / UNTRACKED_OVERWRITE with affected paths so UI
     * can offer commit-and-switch.
     */
    suspend fun switchBranch(
        workspacePath: String,
        branch: String,
        create: Boolean = false,
    ): SwitchResult = withContext(Dispatchers.IO) {
        ensureGit(workspacePath)
        val name = branch.trim()
        val invalid = name.isEmpty() ||
            name.contains("..") ||
            name.any { ch -> ch.isWhitespace() || ch == '\\' }
        if (invalid) {
            return@withContext SwitchResult(
                ok = false,
                message = "分支名无效，请换一个名称。",
                issue = SwitchIssue.INVALID_NAME,
            )
        }

        val dirty = listChangedFiles(workspacePath)
        val flag = if (create) "-b" else ""
        val script = "cd '$workspacePath' 2>/dev/null && git checkout $flag '$name' 2>&1"
        val res = runShell(workspacePath, script, login = false)
        val out = buildString {
            append(res.stdout)
            if (res.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(res.stderr)
            }
        }.trim()
        val lower = out.lowercase()

        val success = out.contains("Switched to") ||
            out.contains("Already on") ||
            out.contains("新分支") ||
            out.contains("new branch") ||
            (res.exitCode == 0 && !lower.contains("error") && !lower.contains("fatal"))

        if (success) {
            val info = collectGitInfo(workspacePath)
            val msg = if (create) "已创建并切换到分支 $name" else "已切换到分支 $name"
            return@withContext SwitchResult(
                ok = true,
                created = create,
                message = msg,
                dirtyFiles = dirty,
                gitInfo = info,
            )
        }

        val issue = when {
            "already exists" in lower || "已存在" in out -> SwitchIssue.ALREADY_EXISTS
            "did not match" in lower || "pathspec" in lower || "unknown revision" in lower -> SwitchIssue.NOT_FOUND
            "untracked working tree files would be overwritten" in lower -> SwitchIssue.UNTRACKED_OVERWRITE
            "your local changes to the following files would be overwritten" in lower ||
                "would be overwritten by checkout" in lower -> SwitchIssue.TRACKED_OVERWRITE
            "you need to resolve your current index first" in lower || "conflict" in lower -> SwitchIssue.CONFLICTS
            else -> SwitchIssue.UNKNOWN
        }

        val affected = parseOverwritePaths(out).ifEmpty {
            dirty.map { it.path }.take(20)
        }

        val message = when (issue) {
            SwitchIssue.ALREADY_EXISTS -> "该分支已存在，请换一个名称。"
            SwitchIssue.NOT_FOUND -> "目标分支不存在。"
            SwitchIssue.CONFLICTS -> "仓库仍有未解决的冲突，请先处理再切换分支。"
            SwitchIssue.TRACKED_OVERWRITE -> "切换被阻止：已跟踪文件将被覆盖。"
            SwitchIssue.UNTRACKED_OVERWRITE -> "切换被阻止：未跟踪文件将被覆盖。"
            SwitchIssue.INVALID_NAME -> "分支名无效，请换一个名称。"
            SwitchIssue.UNKNOWN -> out.ifBlank { "切换分支失败，请重试。" }
            SwitchIssue.NONE -> out
        }

        SwitchResult(
            ok = false,
            created = create,
            message = message,
            issue = issue,
            affectedFiles = affected,
            dirtyFiles = dirty,
            gitInfo = collectGitInfo(workspacePath),
        )
    }

    /**
     * Stage all workspace changes and commit, then switch/create branch.
     * Mirrors ZCode commit-and-switch.
     */
    suspend fun commitAndSwitch(
        workspacePath: String,
        targetBranch: String,
        create: Boolean,
        message: String,
    ): SwitchResult = withContext(Dispatchers.IO) {
        ensureGit(workspacePath)
        val info = collectGitInfo(workspacePath)
        if (!info.hasIdentity) {
            return@withContext SwitchResult(
                ok = false,
                message = "当前没有可用的 Git 提交身份，请先配置 user.name 和 user.email。",
                issue = SwitchIssue.UNKNOWN,
                dirtyFiles = listChangedFiles(workspacePath),
                gitInfo = info,
            )
        }
        val commitMsg = message.trim().ifBlank {
            "chore: save work before switching to $targetBranch"
        }
        val escaped = shellEscape(commitMsg)
        val commitScript = buildString {
            append("cd '$workspacePath' 2>/dev/null || exit 1; ")
            append("git add -A 2>&1; ")
            append("git commit -m $escaped --allow-empty 2>&1; ")
            append("echo COMMIT_EXIT=\$?")
        }
        val commitRes = runShell(workspacePath, commitScript, login = true)
        val commitOut = buildString {
            append(commitRes.stdout)
            if (commitRes.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(commitRes.stderr)
            }
        }.trim()
        val lower = commitOut.lowercase()
        val hardFail = ("error" in lower || "fatal" in lower) &&
            "nothing to commit" !in lower &&
            "working tree clean" !in lower
        if (hardFail && "COMMIT_EXIT=0" !in commitOut) {
            return@withContext SwitchResult(
                ok = false,
                message = "提交失败：${commitOut.take(300)}",
                issue = SwitchIssue.UNKNOWN,
                dirtyFiles = listChangedFiles(workspacePath),
                gitInfo = collectGitInfo(workspacePath),
            )
        }

        switchBranch(workspacePath, targetBranch, create)
    }

    private fun parseOverwritePaths(output: String): List<String> {
        val lines = output.lines()
        val out = mutableListOf<String>()
        var capture = false
        for (line in lines) {
            val t = line.trim()
            val lower = t.lowercase()
            if (lower.endsWith("would be overwritten by checkout:") ||
                lower.endsWith("would be overwritten by merge:") ||
                ("would be overwritten" in lower && t.endsWith(":"))
            ) {
                capture = true
                continue
            }
            if (capture) {
                if (t.isEmpty() ||
                    t.startsWith("Please") ||
                    t.startsWith("error:") ||
                    t.startsWith("Aborting") ||
                    t.startsWith("fatal:")
                ) {
                    if (out.isNotEmpty()) break
                    continue
                }
                val looksLikePath = !t.contains(' ') || t.contains('/') ||
                    line.startsWith("\t") || line.startsWith("    ")
                if (looksLikePath) {
                    out += t.trim('"', '\'')
                } else {
                    break
                }
            }
        }
        return out.distinct()
    }


    data class CommitResult(
        val ok: Boolean,
        val message: String = "",
        val gitInfo: GitInfo? = null,
    )

    data class PushResult(
        val ok: Boolean,
        val message: String = "",
        val setUpstream: Boolean = false,
        val remoteName: String = "origin",
        val trackingBranch: String = "",
        val gitInfo: GitInfo? = null,
    )

    suspend fun diffStat(workspacePath: String, maxLines: Int = 80): String = withContext(Dispatchers.IO) {
        ensureGit(workspacePath)
        val script = "cd '$workspacePath' 2>/dev/null && git diff --stat HEAD 2>/dev/null; echo '---'; git status --porcelain 2>/dev/null | head -n 40"
        val res = runShell(workspacePath, script, login = true)
        res.stdout.lines().take(maxLines).joinToString("\n")
    }

    suspend fun commitWorkspace(
        workspacePath: String,
        message: String,
        allowEmpty: Boolean = false,
    ): CommitResult = withContext(Dispatchers.IO) {
        ensureGit(workspacePath)
        val info = collectGitInfo(workspacePath)
        if (!info.hasIdentity) {
            return@withContext CommitResult(false, "当前没有可用的 Git 提交身份，请先配置 user.name 和 user.email。", info)
        }
        val commitMsg = message.trim()
        if (commitMsg.isEmpty()) {
            return@withContext CommitResult(false, "请先输入或生成提交消息。", info)
        }
        if (!info.hasChanges && !allowEmpty) {
            return@withContext CommitResult(false, "当前没有可提交的更改。", info)
        }
        val escaped = shellEscape(commitMsg)
        val emptyFlag = if (allowEmpty) " --allow-empty" else ""
        val script = buildString {
            append("cd '$workspacePath' 2>/dev/null || exit 1; ")
            append("git add -A 2>&1; ")
            append("git commit -m $escaped$emptyFlag 2>&1; ")
            append("echo COMMIT_EXIT=\$?")
        }
        val res = runShell(workspacePath, script, login = true)
        val out = combineOut(res.stdout, res.stderr)
        val ok = "COMMIT_EXIT=0" in out || res.exitCode == 0 ||
            "files changed" in out || "create mode" in out
        val lower = out.lowercase()
        val failed = !ok && ("error" in lower || "fatal" in lower)
        CommitResult(
            ok = ok && !failed,
            message = if (ok && !failed) "已提交当前更改" else "提交失败：${out.take(300)}",
            gitInfo = collectGitInfo(workspacePath),
        )
    }

    suspend fun push(workspacePath: String): PushResult = withContext(Dispatchers.IO) {
        ensureGit(workspacePath)
        val info = collectGitInfo(workspacePath)
        if (!info.isRepo) {
            return@withContext PushResult(false, "当前工作区不是 Git 仓库。", gitInfo = info)
        }
        if (info.branch.isBlank() || info.branch == "HEAD") {
            return@withContext PushResult(false, "当前不在命名分支上，无法推送。", gitInfo = info)
        }
        if (info.originUrl.isBlank()) {
            return@withContext PushResult(false, "未配置远程 origin。", gitInfo = info)
        }
        if (info.hasUpstream && info.ahead == 0) {
            return@withContext PushResult(true, "当前分支没有需要推送的提交。", gitInfo = info)
        }
        val setUpstream = !info.hasUpstream
        val script = if (setUpstream) {
            "cd '$workspacePath' 2>/dev/null && git push -u origin HEAD 2>&1; echo PUSH_EXIT=\$?"
        } else {
            "cd '$workspacePath' 2>/dev/null && git push 2>&1; echo PUSH_EXIT=\$?"
        }
        val res = runShell(workspacePath, script, login = true)
        val out = combineOut(res.stdout, res.stderr)
        val ok = "PUSH_EXIT=0" in out || (
            res.exitCode == 0 && "error" !in out.lowercase() && "fatal" !in out.lowercase()
        )
        val next = collectGitInfo(workspacePath)
        PushResult(
            ok = ok,
            message = if (ok) {
                val target = next.trackingBranch.ifBlank { "origin/${info.branch}" }
                "已推送到 $target"
            } else {
                "推送失败：${out.take(400)}"
            },
            setUpstream = setUpstream,
            remoteName = "origin",
            trackingBranch = next.trackingBranch,
            gitInfo = next,
        )
    }

    suspend fun commitAndPush(
        workspacePath: String,
        message: String,
    ): Pair<CommitResult, PushResult?> = withContext(Dispatchers.IO) {
        val commit = commitWorkspace(workspacePath, message)
        if (!commit.ok) return@withContext commit to null
        commit to push(workspacePath)
    }

    private fun shellEscape(value: String): String {
        val q = 39.toChar()
        val bs = 92.toChar()
        return buildString {
            append(q)
            value.forEach { ch ->
                if (ch == q) {
                    append(q)
                    append(bs)
                    append(q)
                    append(q)
                } else {
                    append(ch)
                }
            }
            append(q)
        }
    }

    private fun combineOut(stdout: String, stderr: String): String = buildString {
        append(stdout)
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append(stderr)
        }
    }.trim()

    private fun extractVar(output: String, prefix: String): String {
        val line = output.lines().firstOrNull { it.startsWith(prefix) } ?: return ""
        return line.removePrefix(prefix).trim()
    }
}
