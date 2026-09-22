package com.andmx.agent

/** 上游 plan-file-continuity 对齐：批准的 plan 落盘 .andmx/plans/，压缩/恢复后注回。 */
object PlanFiles {

    const val PLAN_DIR = ".andmx/plans"
    const val MAX_PLAN_CHARS = 50_000

    fun planRelativePath(conversationId: Long): String =
        "$PLAN_DIR/plan-${sanitize(conversationId.toString())}.md"

    fun formatPlanFileReference(planContent: String, planFilePath: String): String =
        """
        A plan file exists from plan mode at: $planFilePath

        Plan contents:

        $planContent

        If this plan is relevant to the current work and not already complete, continue working on it.
        """.trimIndent()

    private fun sanitize(id: String): String =
        id.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
}
