package com.andmx.workspace

import android.content.Context
import com.andmx.BuildConfig
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.proot.RootfsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

data class LocalWorktree(val project: String, val path: String) {
    fun hostDirectory(context: Context): File = GuestPaths.resolve(ProotRuntime(context).rootfsDir, path)

    fun context(): CoroutineContext = active.asContextElement(this)

    companion object {
        private const val ROOT = "/root/.andmx/worktrees"
        private val active = ThreadLocal<LocalWorktree>()

        fun current(): LocalWorktree? = active.get()

        fun inherit(): CoroutineContext = current()?.context() ?: EmptyCoroutineContext

        fun restore(project: String, path: String): LocalWorktree? =
            if (project.startsWith("/") && path.matches(Regex("$ROOT/[a-f0-9-]{36}"))) LocalWorktree(project, path)
            else null

        suspend fun create(
            context: Context,
            project: String,
            source: LocalWorktree? = null,
            carryChanges: Boolean = false,
        ): LocalWorktree = withContext(Dispatchers.IO) {
            require(BuildConfig.FLAVOR == "proot") { "Worktree requires the proot build." }
            require(RootfsInstaller(ProotRuntime(context)).isInstalled()) { "Open the terminal to set up Alpine first." }
            val root = File(project).canonicalFile
            require(root.isDirectory && File(root, ".git").exists()) { "Select a local Git repository first." }
            val id = UUID.randomUUID().toString()
            val worktree = LocalWorktree(root.path, "$ROOT/$id")
            val directory = worktree.hostDirectory(context)
            check(directory.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                "Unable to create the worktree directory."
            }
            require(source == null || File(source.project).canonicalFile == root)
            val sourcePath = source?.path ?: "/root/project"
            val branch = "andmx/task-$id"
            withContext(worktree.context()) {
                val access = WorkspaceAccess(context)
                suspend fun git(command: String): String {
                    val result = access.executeShell(command, cwd = sourcePath)
                    check(result.exitCode == 0) {
                        result.stderr.ifBlank { result.stdout }.ifBlank { result.error ?: "Git worktree operation failed." }.takeLast(500)
                    }
                    return result.stdout
                }

                GitBaseline(context).ensureGit(sourcePath)
                try {
                    git("git worktree add -b $branch ${worktree.path} HEAD")
                    check(directory.isDirectory)
                    if (carryChanges) {
                        val patches = java.nio.file.Files.createTempDirectory(directory.parentFile.toPath(), "$id-patches-").toFile()
                        val guestPatches = "$ROOT/${patches.name}"
                        try {
                            git("git diff --no-ext-diff --binary --cached HEAD -- > $guestPatches/staged.patch")
                            git("git diff --no-ext-diff --binary -- > $guestPatches/unstaged.patch")
                            if (File(patches, "staged.patch").length() > 0) {
                                git("git -C ${worktree.path} apply --index $guestPatches/staged.patch")
                            }
                            if (File(patches, "unstaged.patch").length() > 0) {
                                git("git -C ${worktree.path} apply $guestPatches/unstaged.patch")
                            }
                            val sourceDirectory = source?.hostDirectory(context) ?: root
                            git("git ls-files --others --exclude-standard -z").split('\u0000')
                                .filter { it.isNotEmpty() }
                                .forEach { relative ->
                                    val from = File(sourceDirectory, relative)
                                    val to = File(directory, relative)
                                    require(from.canonicalFile.toPath().startsWith(sourceDirectory.canonicalFile.toPath()))
                                    require(to.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()))
                                    require(from.isFile && from.canonicalFile == from.absoluteFile) {
                                        "Cannot carry untracked file: $relative"
                                    }
                                    to.parentFile?.mkdirs()
                                    from.copyTo(to)
                                    if (from.canExecute()) to.setExecutable(true)
                                }
                        } finally {
                            patches.deleteRecursively()
                        }
                    }
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        access.executeShell("git worktree remove --force ${worktree.path}", cwd = sourcePath)
                        access.executeShell("git branch -D $branch", cwd = sourcePath)
                    }
                    throw failure
                }
            }
            worktree
        }
    }
}
