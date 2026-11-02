package com.andmx.agent.workflow

import com.andmx.agent.zcode.CREATE_WORKFLOW_ARGS_WITHOUT_PATH_ERROR
import com.andmx.agent.zcode.CREATE_WORKFLOW_SOURCE_ERROR
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Paths

/**
 * `CreateWorkflow` 的入参校验与归一化。对齐上游 `create-workflow-source.ts`。
 *
 * 这个类是两个来源合流成一份脚本的地方，也是全流程唯一一次读盘。合流点必须保持单一：放回
 * handler 里，它会逐渐被 handler 的分支缠住，下一个人自然就会再开一条「保存的 run 特殊处理」的
 * 岔路，而那条岔路的第一个受害者是确认窗——它会开始展示与将要执行的东西不同的字节。
 */
class CreateWorkflowSource(
    private val store: SavedWorkflowStore?,
    private val readText: suspend (String) -> String?,
    private val cwd: () -> String,
    /** 宿主模型目录。`null` 表示这个宿主不能选模型（上游 `modelCatalogPort` 缺席）。 */
    private val catalog: SubagentModelCatalog? = null,
) {

    /** 上游 `resolveModelReference` 的两档结果。 */
    sealed interface Canonicalization {
        data class Ok(val canonical: String) : Canonicalization
        data class Failed(val message: String) : Canonicalization
    }

    fun interface SubagentModelCatalog {
        fun canonicalize(requested: String): Canonicalization
    }

    /** 归一化之后 `script` 一定在场，下游对三条来源是同一段代码。 */
    data class ResolvedInput(
        val name: String?,
        /** 逐字的脚本本体。 */
        val script: String,
        /** 来源是保存定义时的来龙去脉；内联与 `path` 来源为 null。 */
        val saved: SavedSource?,
        /** 来源是磁盘文件时的绝对路径；内联为 null。 */
        val path: String?,
        val args: Map<String, JsonElement>,
        /** 正文行 → 文件行的偏移。诊断按文件行报出来时加它。 */
        val scriptLineOffset: Int,
        val maxConcurrency: Int?,
        val subagentModel: String?,
    ) {
        val isInline: Boolean get() = saved == null && path == null
    }

    data class SavedSource(
        val name: String,
        val args: Map<String, JsonElement>,
        val path: String,
        val scope: SavedWorkflowStore.Scope,
        /** 工作副本落点。草稿写不下去时缺席（尽力而为），模型面随之退回旧文案。 */
        val draft: String? = null,
    )

    sealed interface Result {
        data class Ok(val input: ResolvedInput) : Result
        data class Failure(val message: String) : Result
    }

    /** 读出来的脚本文件。上游 `WorkflowScriptFile`。 */
    private data class ScriptFile(
        val path: String,
        val described: String,
        val source: String,
        val script: String,
        val meta: WorkflowScriptFrontmatter.Meta?,
        val bodyLineOffset: Int,
    )

    /**
     * 来源三选一（外加「`args` 只跟 `path` 走」），只对**模型发出的**入参成立。
     *
     * 这条不能并进 schema：归一化之后 `script` 与 `saved` / `path` 同时在场是合法执行态，而 schema
     * 会在 handler 的 parse 与 hook 改写后的二次校验上把它炸掉——且只在非内联路径上炸。
     *
     * 返回 `null` 表示通过。
     */
    fun validateSource(args: JsonObject): String? {
        val sources = listOf("script", "saved", "path").count { isPresent(args, it) }
        if (sources != 1) return CREATE_WORKFLOW_SOURCE_ERROR
        // `args` 与 `path` 同进同退：`saved` 的实参走 `saved.args`，内联脚本没有声明可校验。
        // 静默忽略会让调用方以为参数生效了，而脚本读到的 `args` 是空的。
        if (isPresent(args, "args") && !isPresent(args, "path")) {
            return CREATE_WORKFLOW_ARGS_WITHOUT_PATH_ERROR
        }
        return null
    }

    /**
     * 并发上界的归一：向下取整、至少 1，**没有上限**。默认并发 D 是起点不是天花板——用户要 40 个
     * 就是 40 个；真正替 provider 把关的是 429 反馈，而不是机器核数。
     */
    fun normalizeMaxConcurrency(value: Int): Int = maxOf(1, value)

    /**
     * 把入参归一化成执行事实。
     *
     * 内联来源是**恒等函数**（一次盘操作都不做）；`saved` 来源解析文件、校验实参、回填默认值；
     * `path` 来源读那个文件（带元数据块就剥掉块并校验 `args`）。
     *
     * 归一化后 `script` 一定在场。`saved` / `path` 此后只是**来龙去脉**：run 标签的兜底与实参的
     * 持久化读它们，执行一个字节都不读它们。
     */
    suspend fun resolve(args: JsonObject): Result {
        val requestedConcurrency = (args["max_concurrency"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.intOrNull
        val requestedModel = (args["subagent_model"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        // 模型解析排在读盘之前：三条来源同一段代码，而一次解不出来的调用不该先去扫一遍磁盘。
        val catalogPort = catalog
        val subagentModel = when {
            requestedModel == null -> null
            catalogPort == null -> return Result.Failure(SUBAGENT_MODEL_UNAVAILABLE)
            else -> when (val resolved = catalogPort.canonicalize(requestedModel)) {
                is Canonicalization.Ok -> resolved.canonical
                is Canonicalization.Failed ->
                    // 解不出来即整次调用失败：什么都没启动、确认窗也不开。
                    return Result.Failure(resolved.message)
            }
        }
        val maxConcurrency = requestedConcurrency?.let(::normalizeMaxConcurrency)

        val path = stringArg(args, "path")
        if (path != null) {
            return resolvePathSource(
                args = args,
                inputPath = path,
                maxConcurrency = maxConcurrency,
                subagentModel = subagentModel,
            )
        }

        if (!isPresent(args, "saved")) {
            // 内联：恒等。内联路径必须一次盘都不碰——那是「零回归」的可测形式。
            return Result.Ok(
                ResolvedInput(
                    name = stringArg(args, "name"),
                    script = rawStringArg(args, "script").orEmpty(),
                    saved = null,
                    path = null,
                    args = emptyMap(),
                    scriptLineOffset = 0,
                    maxConcurrency = maxConcurrency,
                    subagentModel = subagentModel,
                ),
            )
        }

        val savedSpec = args["saved"] as? JsonObject
            ?: return Result.Failure("`saved` must be an object with at least a `name`.")
        val savedName = stringArg(savedSpec, "name")
            ?: return Result.Failure("`saved.name` is required.")
        val scopeText = stringArg(savedSpec, "scope")
        val scope = if (scopeText == null) {
            null
        } else {
            SavedWorkflowStore.Scope.from(scopeText)
                ?: return Result.Failure("`saved.scope` must be \"project\" or \"global\", not \"$scopeText\".")
        }
        val savedArgs = savedSpec["args"] as? JsonObject

        val activeStore = store
            ?: return Result.Failure("This session has no saved-workflow archive; pass an inline `script` instead.")

        val found = when (val resolution = activeStore.resolve(savedName, scope)) {
            is SavedWorkflowStore.ResolveResult.Ok -> resolution.workflow
            is SavedWorkflowStore.ResolveResult.Failure ->
                return Result.Failure(describeResolveFailure(savedName, resolution, scope))
        }

        val validated = WorkflowArgs.validate(found.meta.args, savedArgs)
        if (validated is ArgsValidation.Failed) {
            return Result.Failure(
                buildString {
                    append("The arguments for saved workflow '$savedName' are not valid:")
                    validated.errors.forEach { append("\n- ").append(it) }
                    append("\n\n")
                    append(describeArgsDeclaration("'${found.name}'", found.meta.args))
                },
            )
        }
        val savedArgsValue = (validated as ArgsValidation.Ok).args

        return Result.Ok(
            ResolvedInput(
                // 未指定展示名时取保存的名字：跨会话枚举出来的 run 因此仍认得出是哪个工作流。
                name = stringArg(args, "name") ?: found.name,
                script = found.script,
                saved = SavedSource(
                    name = found.name,
                    args = savedArgsValue,
                    path = found.path,
                    scope = found.scope,
                    // 草稿写不下去时字段整个缺席（尽力而为），模型面随之退回旧文案。
                    draft = null,
                ),
                path = null,
                // saved 来源的实参住在 `saved.args` 里；顶层 `args` 只属于 `path` 来源。
                args = emptyMap(),
                scriptLineOffset = found.bodyLineOffset,
                maxConcurrency = maxConcurrency,
                subagentModel = subagentModel,
            ),
        )
    }

    /**
     * `path` 来源的归一化。
     *
     * 不写草稿：文件已经是工作副本了，再抄一份只会让模型下一次不知道该改哪一个。
     *
     * 带元数据块的文件按保存定义解析，`args` 按块里的声明校验（默认值一并补齐）；没有块的文件
     * 整个是脚本，此时给了 `args` 就是错——没有任何声明能校验它们。
     */
    private suspend fun resolvePathSource(
        args: JsonObject,
        inputPath: String,
        maxConcurrency: Int?,
        subagentModel: String?,
    ): Result {
        val workingDir = cwd()
        val absolute = resolveScriptFilePath(workingDir, inputPath)
        val described = WorkflowScriptNotes.describePath(absolute, workingDir)

        val source = readText(absolute) ?: return Result.Failure(
            "The workflow script file $described could not be read. " +
                "Pass `path` for a file that exists, or submit the script inline.",
        )

        val file = if (!startsWithSentinel(source)) {
            ScriptFile(absolute, described, source, source, null, 0)
        } else {
            when (val parsed = WorkflowScriptFrontmatter.parse(source)) {
                is WorkflowScriptFrontmatter.Result.Ok -> ScriptFile(
                    path = absolute,
                    described = described,
                    source = source,
                    script = parsed.script,
                    meta = parsed.meta,
                    bodyLineOffset = parsed.bodyLineOffset,
                )
                is WorkflowScriptFrontmatter.Result.Fail -> return Result.Failure(
                    "The workflow script file $described starts with a " +
                        "`${WorkflowScriptFrontmatter.SENTINEL}` metadata block that could not be read " +
                        "(${parsed.reason}): ${parsed.detail}. " +
                        "Fix the block in that file, or remove it and pass the script alone.",
                )
            }
        }

        if (file.meta == null && isPresent(args, "args")) {
            return Result.Failure(
                "The workflow script file ${file.described} declares no arguments " +
                    "(it has no `${WorkflowScriptFrontmatter.SENTINEL}` metadata block), so it takes none. " +
                    "Drop `args`, or add a block declaring them.",
            )
        }

        val validated = WorkflowArgs.validate(file.meta?.args, args["args"] as? JsonObject)
        if (validated is ArgsValidation.Failed) {
            return Result.Failure(
                buildString {
                    append("The arguments for the workflow script file ${file.described} are not valid:")
                    validated.errors.forEach { append("\n- ").append(it) }
                    append("\n\n")
                    append(describeArgsDeclaration(file.described, file.meta?.args))
                },
            )
        }
        val declaredArgs = (validated as ArgsValidation.Ok).args

        return Result.Ok(
            ResolvedInput(
                name = stringArg(args, "name"),
                script = file.script,
                saved = null,
                path = file.path,
                // 声明为空时实参恒为 `{}`；不造空壳键，与内联 run 的「没有实参」保持同一种形状。
                args = declaredArgs,
                scriptLineOffset = file.bodyLineOffset,
                maxConcurrency = maxConcurrency,
                subagentModel = subagentModel,
            ),
        )
    }

    /**
     * 解析失败的说明。找不到时**列出实际可用的名字**（带作用域标签）：模型猜错一个名字后最有用的
     * 下一步信息就是正确的那一批，否则它只会再猜一次。
     */
    private fun describeResolveFailure(
        name: String,
        failure: SavedWorkflowStore.ResolveResult.Failure,
        scope: SavedWorkflowStore.Scope?,
    ): String = when (failure.reason) {
        SavedWorkflowStore.FailureReason.INVALID_NAME ->
            "'$name' is not a usable workflow name: ${failure.detail}"

        SavedWorkflowStore.FailureReason.PARSE_ERROR -> buildString {
            append("The saved workflow '$name' at ${failure.path.orEmpty()} could not be read: ${failure.detail}")
            append("\n\n")
            append(
                "Its metadata block is malformed — most likely hand-edited. " +
                    "Fix the file, or save the workflow again.",
            )
        }

        SavedWorkflowStore.FailureReason.READ_ERROR ->
            "The saved workflow '$name' at ${failure.path.orEmpty()} could not be read: ${failure.detail}"

        SavedWorkflowStore.FailureReason.NOT_FOUND -> {
            val headline = if (scope == null) {
                "No saved workflow named '$name' in this project or globally."
            } else {
                "No ${scope.wire} workflow named '$name'."
            }
            "$headline\n\n${describeAvailableWorkflows()}"
        }
    }

    /**
     * 两个档案里实际可用的名字，各带作用域标签。定向扫每一根（不做遮蔽），好让被项目档遮蔽的
     * 全局定义也出现在清单里——模型据此才知道要拿它得指定 `scope: "global"`。
     */
    private fun describeAvailableWorkflows(): String {
        val activeStore = store ?: return NO_SAVED_WORKFLOWS
        val project = activeStore.list(SavedWorkflowStore.Scope.PROJECT).entries
        val global = activeStore.list(SavedWorkflowStore.Scope.GLOBAL).entries
        val tagged = project.map { "${it.name} (project)" } + global.map { "${it.name} (global)" }
        if (tagged.isEmpty()) return NO_SAVED_WORKFLOWS
        return "Available saved workflows: ${tagged.joinToString(", ")}. " +
            "Use ListSavedWorkflows for their descriptions."
    }

    /** 参数声明的紧凑复述，附在参数校验失败之后，好让模型一次改对而不是再猜一轮。 */
    private fun describeArgsDeclaration(
        label: String,
        args: Map<String, WorkflowScriptFrontmatter.ArgSpec>?,
    ): String {
        val declared = (args ?: emptyMap()).entries
        if (declared.isEmpty()) return "$label declares no arguments."
        return buildString {
            append("$label declares:")
            declared.forEach { (key, spec) ->
                val notes = buildList {
                    add(spec.type)
                    add(if (spec.required == true) "required" else "optional")
                    spec.default?.let { add("default $it") }
                }
                append("\n- ").append(key).append(" (").append(notes.joinToString(", ")).append(")")
                spec.description?.let { append(": ").append(it) }
            }
        }
    }

    private fun resolveScriptFilePath(workingDir: String, inputPath: String): String {
        val given = runCatching { Paths.get(inputPath) }.getOrNull()
        if (given != null) {
            return if (given.isAbsolute) {
                given.normalize().toString()
            } else {
                runCatching { Paths.get(workingDir).resolve(given).normalize().toString() }
                    .getOrDefault("$workingDir/$inputPath")
            }
        }
        return if (inputPath.startsWith("/")) inputPath else "$workingDir/$inputPath"
    }

    /**
     * 首个非空行是不是起始标记。判据与 [WorkflowScriptFrontmatter.parse] 自己的入口逐字一致——
     * 两处对「这是不是一个保存定义」给出不同答案，就会出现「工具说没有块、解析器说块坏了」的死角。
     */
    private fun startsWithSentinel(source: String): Boolean {
        for (line in source.split('\n')) {
            if (line.isBlank()) continue
            return line.trim() == WorkflowScriptFrontmatter.SENTINEL
        }
        return false
    }

    private fun isPresent(args: JsonObject, key: String): Boolean {
        val value = args[key] ?: return false
        if (value is JsonNull) return false
        if (value is JsonPrimitive && value.contentOrNull.isNullOrBlank()) return false
        return true
    }

    private fun stringArg(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }

    /** 不 trim：脚本正文要逐字节保留，尾部换行是有意义的。 */
    private fun rawStringArg(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotEmpty() }

    companion object {
        /** 宿主没有模型目录时的拒绝文案（`AmendWorkflow` 共用）。 */
        const val SUBAGENT_MODEL_UNAVAILABLE =
            "This host cannot choose a subagent model; omit subagent_model."

        const val NO_SAVED_WORKFLOWS =
            "There are no saved workflows yet, in this project or globally. " +
                "Use SaveWorkflow to create one, or pass an inline `script` instead."
    }
}
