package com.andmx.agent

import com.andmx.agent.zcode.DYNAMIC_WORKFLOW_TOOLS_ENABLED

object WorkflowPrompt {

    fun expand(args: String, toolsEnabled: Boolean = DYNAMIC_WORKFLOW_TOOLS_ENABLED): String {
        val request = args.ifBlank { "（用户未附描述：先询问这次工作流要完成什么）" }
        val body = if (toolsEnabled) enabledBody() else hiddenBody()
        return "$body\n\n$request"
    }

    fun blankArgsNotice(toolsEnabled: Boolean = DYNAMIC_WORKFLOW_TOOLS_ENABLED): String =
        if (toolsEnabled) {
            "用法：`/workflow <要完成的任务>`。先加载 dynamic-workflows。CreateWorkflow 只接受 script、saved 或 path 之一，不会类型检查，也不会启动运行。"
        } else {
            "用法：`/workflow <要完成的任务>`。动态工作流工具未注册，不会类型检查，也不会启动运行。"
        }

    private fun hiddenBody(): String = """
        Dynamic workflow tools are not registered in this session. Do not call a workflow tool, and do not load a skill in order to launch one. Nothing will be typechecked and nothing will be started. Handle the request with the Agent tool or with the tools that are actually listed.
    """.trimIndent()

    private fun enabledBody(): String = """
        Load the dynamic-workflows skill with the Skill tool before you call a workflow tool that submits a script. The skill is the argument contract, not a runner. CreateWorkflow takes exactly one of `script`, `saved` (an object with `name`), or `path`. Do not pass `spec`, `task`, or a bare `name` as the source. AmendWorkflow takes `run_id` and at most one of `script` or `path`. SaveWorkflow takes `name`, `description`, `scope`, and exactly one of `script` or `script_path`. EvalWorkflowSnippet takes exactly one of `code` or `path`. A call does not typecheck a script and does not start a run. If a tool name is not in the tool list, do not call it.
    """.trimIndent()
}
