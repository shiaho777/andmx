package com.andmx.agent

/** 上游 3.14.3 builtin-workflow-command 的 /workflow 移植：展开为一条用户 prompt 交给 agent 执行。 */
object WorkflowPrompt {

    fun expand(args: String): String {
        val request = args.ifBlank { "（用户未附描述：先询问这次工作流要完成什么）" }
        return """
            Use the `${LoadedSkills.DYNAMIC_WORKFLOWS}` skill to design and launch a dynamic workflow for this request:

            $request

            Call the Skill tool with skill "${LoadedSkills.DYNAMIC_WORKFLOWS}" first — the spec schema and authoring rules live there. Decide the phase pipeline before writing any spec: which phases, which behavior each one uses (agent / scheduled_graph / critic / complete), what each phase hands back. Then write the spec and call the `CreateWorkflow` tool. (Do not use `/expert` — that is a different, older feature — and do not substitute the `Agent` tool.)
        """.trimIndent()
    }
}
