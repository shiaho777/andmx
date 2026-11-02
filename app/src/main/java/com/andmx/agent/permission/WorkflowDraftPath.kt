package com.andmx.agent.permission

import java.nio.file.Paths

const val WORKFLOW_DRAFTS_DIR = ".zcode/workflow-drafts"

private val draftTools = setOf("Edit", "Write")

fun isPreapprovedWorkflowDraftWrite(toolName: String, input: Any?, workingDirectory: String?): Boolean {
    if (toolName !in draftTools) return false
    if (workingDirectory == null) return false
    val filePath = jsonString(input, "file_path") ?: return false
    return isWorkflowDraftPath(filePath, workingDirectory)
}

fun isWorkflowDraftPath(filePath: String, workingDirectory: String): Boolean {
    if (workingDirectory.isEmpty() || filePath.isEmpty()) return false
    val base = Paths.get(workingDirectory).toAbsolutePath().normalize()
    val drafts = base.resolve(WORKFLOW_DRAFTS_DIR).normalize()
    val target = Paths.get(filePath)
    val resolved = (if (target.isAbsolute) target else base.resolve(filePath)).normalize()
    val relative = drafts.relativize(resolved)
    val text = relative.toString()
    return text.isNotEmpty() && !relative.isAbsolute && !text.startsWith("..")
}

fun isAmendWorkflowOwnedPredecessor(predecessor: Any?): Boolean {
    val facts = jsonObject(predecessor) ?: return false
    if (facts.containsKey("introduces_providers")) return false
    return facts["owned_by_this_session"] == true && facts["stop_reason"] != "user"
}

fun isFillWorkflowHoleOwnedRun(hole: Any?): Boolean {
    val facts = jsonObject(hole) ?: return false
    return facts["owned_by_this_session"] == true
}
