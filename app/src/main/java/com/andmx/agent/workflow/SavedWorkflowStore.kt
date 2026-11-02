package com.andmx.agent.workflow

import com.andmx.exec.files.GuestFs

/**
 * 保存的工作流定义库，对齐 ZCode `saved-workflows/store.ts`。
 *
 * 落点：
 * - 项目档 `<cwd>/.zcode/workflows/<name>.dwf.ts`
 * - 全局档 `<home>/.zcode/workflows/<name>.dwf.ts`
 *
 * 查找顺序 `[project, global]`，同名 first-wins：项目里的那份永远赢过全局那份。
 * 列表要回答的是「调用这个名字会跑到什么」，所以被遮蔽的那份**不出现**；
 * 但给了 `scope` 时只扫那一根，不做遮蔽。
 */
class SavedWorkflowStore(
    private val files: WorkflowFileSystem,
    private val cwd: () -> String,
    private val homeDir: String = DEFAULT_GUEST_HOME,
) {

    enum class Scope(val wire: String) {
        PROJECT("project"),
        GLOBAL("global"),
        ;

        companion object {
            fun from(value: String?): Scope? =
                entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
        }
    }

    data class Root(val scope: Scope, val dir: String)

    data class Entry(
        val name: String,
        val description: String,
        val whenToUse: String?,
        val args: Map<String, WorkflowScriptFrontmatter.ArgSpec>,
        val scope: Scope,
        val path: String,
    )

    /** 存在但读不出来的文件。**列表不因为一个坏文件而失败**，它只被单独列出来。 */
    data class InvalidEntry(val path: String, val reason: String)

    data class ListResult(val entries: List<Entry>, val invalid: List<InvalidEntry>)

    data class Resolved(
        val name: String,
        val path: String,
        val scope: Scope,
        val meta: WorkflowScriptFrontmatter.Meta,
        val script: String,
        /** 文件原文，逐字节。草稿拷贝拿的是它，避免重新序列化让排版漂移。 */
        val source: String,
        val bodyLineOffset: Int,
    )

    enum class FailureReason { INVALID_NAME, NOT_FOUND, PARSE_ERROR, READ_ERROR }

    sealed interface ResolveResult {
        data class Ok(val workflow: Resolved) : ResolveResult
        data class Failure(
            val reason: FailureReason,
            val detail: String,
            val path: String? = null,
        ) : ResolveResult
    }

    data class SaveResult(val path: String, val scope: Scope, val overwritten: Boolean)

    /** 保存到某一档时另一档是否已有同名定义。 */
    enum class Shadowing { HIDES_GLOBAL, HIDDEN_BY_PROJECT }

    fun roots(): List<Root> = listOf(
        Root(Scope.PROJECT, join(cwd(), PROJECT_DIR)),
        Root(Scope.GLOBAL, join(homeDir, GLOBAL_DIR)),
    )

    fun root(scope: Scope): Root = roots().first { it.scope == scope }

    fun isValidName(name: String): Boolean =
        name.length in 1..MAX_NAME_CHARS && NAME_PATTERN.matches(name)

    fun fileName(name: String): String = "$name$FILE_EXTENSION"

    fun path(root: Root, name: String): String = join(root.dir, fileName(name))

    fun invalidNameDetail(): String =
        "workflow names may only contain letters, digits, '.', '-' and '_', and must be 1-$MAX_NAME_CHARS characters"

    fun list(scope: Scope? = null): ListResult {
        val entries = mutableListOf<Entry>()
        val invalid = mutableListOf<InvalidEntry>()
        val claimed = mutableSetOf<String>()

        val scanRoots = if (scope == null) roots() else listOf(root(scope))
        for (root in scanRoots) {
            if (!files.isDirectory(root.dir)) continue
            val fileNames = try {
                files.list(root.dir)
            } catch (e: Exception) {
                invalid += InvalidEntry(root.dir, e.message ?: "unreadable directory")
                continue
            }
            val candidates = fileNames
                .filterNot { it.endsWith("/") }
                .filter { it.endsWith(FILE_EXTENSION) }
                .sorted()

            for (fileName in candidates) {
                val name = fileName.dropLast(FILE_EXTENSION.length)
                val filePath = join(root.dir, fileName)
                if (!isValidName(name)) {
                    invalid += InvalidEntry(filePath, "file name is not a usable workflow name")
                    continue
                }
                if (name in claimed) continue

                val source = try {
                    files.readText(filePath)
                } catch (e: Exception) {
                    invalid += InvalidEntry(filePath, e.message ?: "unreadable file")
                    continue
                }
                when (val parsed = WorkflowScriptFrontmatter.parse(source)) {
                    is WorkflowScriptFrontmatter.Result.Fail -> {
                        invalid += InvalidEntry(filePath, "${parsed.reason.name.lowercase()}: ${parsed.detail}")
                        continue
                    }
                    is WorkflowScriptFrontmatter.Result.Ok -> {
                        claimed += name
                        entries += Entry(
                            name = name,
                            description = parsed.meta.description,
                            whenToUse = parsed.meta.whenToUse,
                            args = parsed.meta.args,
                            scope = root.scope,
                            path = filePath,
                        )
                    }
                }
            }
        }
        return ListResult(entries, invalid)
    }

    fun resolve(name: String, scope: Scope? = null): ResolveResult {
        if (!isValidName(name)) {
            return ResolveResult.Failure(FailureReason.INVALID_NAME, invalidNameDetail())
        }
        val candidates = if (scope == null) roots() else listOf(root(scope))
        for (candidate in candidates) {
            val filePath = path(candidate, name)
            if (!files.exists(filePath)) continue
            if (files.isDirectory(filePath)) {
                return ResolveResult.Failure(FailureReason.READ_ERROR, "path is a directory", filePath)
            }
            val source = runCatching { files.readText(filePath) }.getOrElse {
                return ResolveResult.Failure(FailureReason.READ_ERROR, it.message ?: "unreadable file", filePath)
            }
            return when (val parsed = WorkflowScriptFrontmatter.parse(source)) {
                is WorkflowScriptFrontmatter.Result.Fail ->
                    ResolveResult.Failure(FailureReason.PARSE_ERROR, parsed.detail, filePath)
                is WorkflowScriptFrontmatter.Result.Ok -> ResolveResult.Ok(
                    Resolved(
                        name = name,
                        path = filePath,
                        scope = candidate.scope,
                        meta = parsed.meta,
                        script = parsed.script,
                        source = source,
                        bodyLineOffset = parsed.bodyLineOffset,
                    ),
                )
            }
        }
        return ResolveResult.Failure(FailureReason.NOT_FOUND, "no saved workflow named `$name`")
    }

    fun save(
        name: String,
        meta: WorkflowScriptFrontmatter.Meta,
        script: String,
        scope: Scope = Scope.PROJECT,
    ): SaveResult {
        require(isValidName(name)) { invalidNameDetail() }
        val target = root(scope)
        val filePath = path(target, name)
        val overwritten = files.exists(filePath)
        files.writeText(filePath, WorkflowScriptFrontmatter.serialize(meta, script))
        return SaveResult(filePath, target.scope, overwritten)
    }

    fun exists(name: String, scope: Scope = Scope.PROJECT): Boolean =
        isValidName(name) && files.exists(path(root(scope), name))

    fun shadowing(name: String, scope: Scope): Shadowing? {
        if (!isValidName(name)) return null
        val other = if (scope == Scope.PROJECT) Scope.GLOBAL else Scope.PROJECT
        if (!exists(name, other)) return null
        return if (scope == Scope.PROJECT) Shadowing.HIDES_GLOBAL else Shadowing.HIDDEN_BY_PROJECT
    }

    private fun join(base: String, child: String): String = base.trimEnd('/') + "/" + child

    companion object {
        const val FILE_EXTENSION = ".dwf.ts"
        const val PROJECT_DIR = ".zcode/workflows"
        const val GLOBAL_DIR = ".zcode/workflows"
        const val MAX_NAME_CHARS = 64
        /** proot guest 的 HOME。全局档落在 guest 家目录下。 */
        const val DEFAULT_GUEST_HOME = "/root"
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_.-]+$")
    }
}

/** store 需要的文件能力。窄接口是为了让 store 能脱离 proot 做单测。 */
interface WorkflowFileSystem {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun readText(path: String): String
    fun writeText(path: String, content: String)
    fun list(dir: String): List<String>
}

/** 把 proot guest 的文件系统接成 store 的依赖。 */
class GuestWorkflowFileSystem(private val fs: GuestFs) : WorkflowFileSystem {
    override fun exists(path: String): Boolean = fs.exists(path)
    override fun isDirectory(path: String): Boolean = runCatching { fs.resolve(path).isDirectory }.getOrDefault(false)
    override fun readText(path: String): String = fs.readText(path)
    override fun writeText(path: String, content: String) = fs.writeText(path, content)
    override fun list(dir: String): List<String> = fs.list(dir)
}
