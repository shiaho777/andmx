package com.andmx.ui.conversation

internal data class ResumePromptSnapshot(
    val project: String = "",
    val goal: String = "",
    val status: String = "",
)

internal fun extractHandoffResumePrompt(markdown: String): String? {
    val marker = "### 恢复提示"
    val markerIndex = markdown.indexOf(marker)
    if (markerIndex < 0) return null
    val afterMarker = markdown.substring(markerIndex + marker.length)
    val fenceStart = afterMarker.indexOf("```")
    if (fenceStart < 0) return null
    val afterFence = afterMarker.substring(fenceStart + 3)
    val firstNewline = afterFence.indexOf('\n')
    val bodyStart = if (firstNewline >= 0) firstNewline + 1 else 0
    val body = afterFence.substring(bodyStart)
    val fenceEnd = body.indexOf("```")
    if (fenceEnd < 0) return null
    return body.substring(0, fenceEnd).trim().takeIf { it.isNotBlank() }
}

internal fun handoffResumeActionLabels(markdown: String): List<String> =
    if (extractHandoffResumePrompt(markdown) == null) emptyList()
    else listOf("当前线程继续", "新线程继续", "复制恢复提示")

internal fun parseResumePrompt(prompt: String): ResumePromptSnapshot {
    var project = ""
    var goal = ""
    var status = ""
    prompt.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("项目:") -> project = line.substringAfter(":").trim()
            line.startsWith("目标:") -> goal = line.substringAfter(":").trim().takeUnlessPlaceholder()
            line.startsWith("状态:") -> status = line.substringAfter(":").trim()
        }
    }
    return ResumePromptSnapshot(project = project, goal = goal, status = status)
}

internal fun resumePromptTitle(prompt: String): String {
    val snapshot = parseResumePrompt(prompt)
    return "(恢复) " + snapshot.goal.ifBlank { prompt.lineSequence().firstOrNull()?.trim().orEmpty() }
        .ifBlank { "AndMX 线程" }
        .take(36)
}

internal fun resumeGoalOverrideForCurrentThread(prompt: String, currentGoal: String): String? {
    val snapshotGoal = parseResumePrompt(prompt).goal
    return snapshotGoal.ifBlank { currentGoal.trim() }.takeIf { it.isNotBlank() }
}

private fun String.takeUnlessPlaceholder(): String =
    takeUnless { it.isBlank() || it == "(未设置)" }
        .orEmpty()
