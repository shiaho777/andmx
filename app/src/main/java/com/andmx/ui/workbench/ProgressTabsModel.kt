package com.andmx.ui.workbench

internal enum class ProgressSection { CHECKLIST, STEPS, OUTPUT, VERIFY, SOURCES, LOG }

internal data class ProgressTabSpec(
    val section: ProgressSection,
    val label: String,
    val badge: Int,
)

internal fun progressTabSpecs(
    checklistBadge: Int,
    outputBadge: Int,
    verificationBadge: Int,
    sourceBadge: Int,
    logBadge: Int,
): List<ProgressTabSpec> = listOf(
    ProgressTabSpec(ProgressSection.CHECKLIST, "清单", checklistBadge),
    ProgressTabSpec(ProgressSection.STEPS, "进度", 0),
    ProgressTabSpec(ProgressSection.OUTPUT, "输出", outputBadge),
    ProgressTabSpec(ProgressSection.VERIFY, "验证", verificationBadge),
    ProgressTabSpec(ProgressSection.SOURCES, "来源", sourceBadge),
    ProgressTabSpec(ProgressSection.LOG, "日志", logBadge),
)

internal fun progressSourceBadge(
    toolEvents: Int,
    visualAcceptanceWaiting: Int,
    referenceBoardOpen: Int = 0,
    designSystemOpen: Int = 0,
    screenshotExtractionWaiting: Int = 0,
    interactionFlowOpen: Int = 0,
    selfModelOpen: Int = 0,
): Int = toolEvents + visualAcceptanceWaiting + referenceBoardOpen + designSystemOpen + screenshotExtractionWaiting + interactionFlowOpen + selfModelOpen
