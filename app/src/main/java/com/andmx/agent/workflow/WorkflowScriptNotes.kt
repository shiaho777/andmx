package com.andmx.agent.workflow

import com.andmx.agent.workflow.script.CompileDiagnostic
import java.nio.file.Paths

/**
 * 脚本文件在模型面的身份。对齐上游 `WorkflowScriptLocation`。
 *
 * `kind` 只影响一个动词：`DRAFT` 是**工具刚写下**的拷贝（「saved at」），`PATH` 是模型自己给的
 * 那个文件（「The script file is」）——对一个本来就存在的文件说「已保存到」，读起来像工具刚动过它。
 */
data class ScriptLocation(
    val kind: Kind,
    /** 模型面的写法（工作区相对或绝对）。 */
    val described: String,
    /** 正文行 → 文件行的偏移；无元数据块即 0。 */
    val lineOffset: Int,
) {
    enum class Kind { DRAFT, PATH }
}

/**
 * 脚本文件在模型面的几句话。对齐上游 `workflow-script-notes.ts`。
 *
 * 工具响应是三个读者之一（另两个是终态通知与 `GetWorkflowRun`），而三个读者要把模型推向同一个
 * 下一步：**去编辑那个文件**。所以这里只放一件事——知道脚本落在哪个文件之后，诊断行与 NOTE
 * 该怎么写。`CreateWorkflow` 与 `AmendWorkflow` 共用它，因为「改完再 `path` 交回来」在两个工具
 * 上是同一句话，分开写就会分叉成两句。
 */
object WorkflowScriptNotes {

    const val DIAGNOSTICS_NOT_EXECUTED =
        "NOTE: The workflow was NOT executed — fix the errors above and resubmit."

    const val EXECUTION_UNAVAILABLE =
        "NOTE: The workflow was NOT executed — workflow execution is not available in this session, " +
            "so the script was only typechecked."

    const val SAVED_FILE_NEEDS_FIXING =
        "NOTE: The workflow was NOT executed — the saved file needs fixing (edit it, or save a corrected version)."

    /**
     * 把脚本文件的绝对路径写成模型面该看到的样子：在会话工作目录之下就相对化，否则原样。
     *
     * 「在工作目录之下」的判据是相对结果既不以 `..` 开头、也不是绝对路径——这一条同时挡住了
     * 目录外的兄弟路径（`../other/x.dwf.ts`，相对写法读起来像在说工作区里有这么个东西）与
     * 跨根的情形。空结果（路径恰好等于工作目录）同样退回绝对路径：空串不是一个能 `Edit` 的名字。
     */
    fun describePath(absolutePath: String, cwd: String?): String {
        if (cwd.isNullOrEmpty()) return absolutePath
        val relative = runCatching { Paths.get(cwd).relativize(Paths.get(absolutePath)).toString() }
            .getOrNull()
            ?: return absolutePath
        if (relative.isEmpty() || relative.startsWith("..") || Paths.get(relative).isAbsolute) {
            return absolutePath
        }
        return relative
    }

    /**
     * 诊断的模型面行。有文件就写成 `{path}:L{line}:C{column} {message}`，行号按**文件**数——
     * 这个数要能直接粘进一次对该文件的 `Edit`。没有文件时退回 `L:C` 形式（正文行）。
     */
    fun formatDiagnosticLines(
        diagnostics: List<CompileDiagnostic>,
        location: ScriptLocation?,
    ): List<String> = if (location == null) {
        diagnostics.map { "L${it.line}:C${it.column} ${it.message}" }
    } else {
        diagnostics.map {
            "${location.described}:L${it.line + location.lineOffset}:C${it.column} ${it.message}"
        }
    }

    /**
     * 编不过、且脚本有文件时的 NOTE（内联与 `path` 两条来源）。
     * 最后半句是这整个特性的目的：**别再把脚本贴一遍**。
     */
    fun scriptFileNote(location: ScriptLocation): String {
        val where = when (location.kind) {
            ScriptLocation.Kind.DRAFT -> "The script is saved at ${location.described}."
            ScriptLocation.Kind.PATH -> "The script file is ${location.described}."
        }
        return "NOTE: The workflow was NOT executed. $where Edit that file in place and resubmit " +
            "with `path: \"${location.described}\"` — do not paste the script inline again."
    }

    /**
     * 编不过、来源是 saved 定义时的 NOTE。多说两件事：这份拷贝是**从哪个定义抄来的**（模型据此
     * 知道改的是拷贝不是定义），以及改定义本身要走 `SaveWorkflow`。
     */
    fun savedDraftNote(savedName: String, savedPath: String, draft: String): String =
        "NOTE: The workflow was NOT executed. A working copy of the saved workflow '$savedName' " +
            "($savedPath) was written to $draft. Edit that copy in place and resubmit with " +
            "`path: \"$draft\"` (pass its `args` again); to change the saved definition itself, " +
            "use SaveWorkflow."

    /** 启动成功后追加的一句：下一次修订从编辑这个文件开始。 */
    fun launchedScriptSentence(location: ScriptLocation): String {
        val where = when (location.kind) {
            ScriptLocation.Kind.DRAFT -> "The script is saved at ${location.described}"
            ScriptLocation.Kind.PATH -> "The script file is ${location.described}"
        }
        return " $where; to revise it later, edit that file and pass `path` to AmendWorkflow."
    }

    /** 修订启动成功后追加的一句（再修订一次仍是同一个动作）。 */
    fun amendedScriptSentence(location: ScriptLocation): String =
        " The revision's script is at ${location.described}; edit it there for a further revision."

    /**
     * 生效的并发上界在结果文案里的一句话。**只在设了上界时出现**：跑在默认并发上的 run 没有
     * 可说的，多一句「至多 N 个」只会让模型以为自己设过什么。
     *
     * 正好等于默认并发时注明「（默认）」：模型要的数与默认恰好相同，说破了它才知道这个 run 与
     * 没设一样，之后不会把它当成一条自己定过的界去复述或「修正」。
     */
    fun concurrencyLimitSentence(limit: Int?, defaultConcurrency: Int?): String {
        if (limit == null) return ""
        val subject = if (limit == 1) "1 subagent runs" else "$limit subagents run"
        val suffix = if (limit == defaultConcurrency) " (the default)" else ""
        return " At most $subject at once$suffix."
    }
}
