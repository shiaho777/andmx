package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.workflow.EscalateTool
import com.andmx.agent.workflow.SubmitResultTool
import java.util.concurrent.atomic.AtomicReference

fun resultTools(
    submitSlot: AtomicReference<String?>? = null,
    escalate: (suspend (question: String, context: String) -> String)? = null,
): List<Tool> = buildList {
    add(SubmitResultTool(submitSlot ?: AtomicReference()))
    if (escalate != null) add(EscalateTool(escalate))
}