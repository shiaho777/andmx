package com.andmx.ui.workbench

internal enum class ProgressSection { CURRENT, OUTPUT, REFERENCES }

internal data class ProgressTabSpec(
    val section: ProgressSection,
    val label: String,
    val badge: Int,
)

internal fun progressTabSpecs(
    currentBadge: Int,
    outputBadge: Int,
    referencesBadge: Int,
): List<ProgressTabSpec> = listOf(
    ProgressTabSpec(ProgressSection.CURRENT, "当前", currentBadge),
    ProgressTabSpec(ProgressSection.OUTPUT, "产物", outputBadge),
    ProgressTabSpec(ProgressSection.REFERENCES, "参考", referencesBadge),
)

/**
 * REFERENCES tab 分组：12 张摘要卡按语义归并。
 * 组内卡的顺序即为渲染顺序。
 */
internal enum class ReferenceGroup(val title: String, val cards: List<ReferenceCard>) {
    EXECUTION_AND_APPROVAL(
        title = "执行与审批",
        cards = listOf(ReferenceCard.TOOL_POLICY, ReferenceCard.INTERACTION_FLOW, ReferenceCard.SELF_MODEL),
    ),
    EVIDENCE_AND_DELIVERY(
        title = "证据与交付",
        cards = listOf(
            ReferenceCard.EVIDENCE_LEDGER,
            ReferenceCard.DELIVERY_REPORT,
            ReferenceCard.CODEX_PARITY,
            ReferenceCard.HANDOFF_ADVICE,
        ),
    ),
    UI_REPLICATION_AND_DESIGN(
        title = "UI 复刻与设计",
        cards = listOf(
            ReferenceCard.UI_REFERENCE_BOARD,
            ReferenceCard.UI_REPLICA_BLUEPRINT,
            ReferenceCard.VISUAL_ACCEPTANCE,
            ReferenceCard.DESIGN_SYSTEM,
            ReferenceCard.SCREENSHOT_EXTRACTION,
            ReferenceCard.SCREENSHOT_TRACE,
        ),
    ),
}

internal enum class ReferenceCard {
    TOOL_POLICY,
    INTERACTION_FLOW,
    SELF_MODEL,
    EVIDENCE_LEDGER,
    DELIVERY_REPORT,
    CODEX_PARITY,
    HANDOFF_ADVICE,
    UI_REFERENCE_BOARD,
    UI_REPLICA_BLUEPRINT,
    VISUAL_ACCEPTANCE,
    DESIGN_SYSTEM,
    SCREENSHOT_EXTRACTION,
    SCREENSHOT_TRACE,
}

/**
 * 单张卡是否"激活"——有内容值得展开查看。
 * 组内任意一张卡激活即视为整组激活（见 [ReferenceGroup.activeCount]）。
 */
internal fun ReferenceCard.isActive(ctx: ReferenceActivationContext): Boolean = when (this) {
    ReferenceCard.TOOL_POLICY -> ctx.policyRows > 0 || ctx.policyBoundaryRows > 0
    ReferenceCard.INTERACTION_FLOW -> ctx.interactionFlowTotal > 0
    ReferenceCard.SELF_MODEL -> ctx.selfModelTotal > 0
    ReferenceCard.EVIDENCE_LEDGER -> ctx.evidenceItems > 0
    ReferenceCard.DELIVERY_REPORT -> ctx.changedFiles > 0 || ctx.verifications > 0 || ctx.evidenceItems > 0
    ReferenceCard.CODEX_PARITY -> ctx.parityTotal > 0
    ReferenceCard.HANDOFF_ADVICE -> ctx.handoffRecommended
    ReferenceCard.UI_REFERENCE_BOARD -> ctx.referenceBoardTotal > 0
    ReferenceCard.UI_REPLICA_BLUEPRINT -> ctx.blueprintTotal > 0
    ReferenceCard.VISUAL_ACCEPTANCE -> ctx.visualAcceptanceTotal > 0
    ReferenceCard.DESIGN_SYSTEM -> ctx.designSystemTotal > 0
    ReferenceCard.SCREENSHOT_EXTRACTION -> ctx.screenshotExtractionTotal > 0
    ReferenceCard.SCREENSHOT_TRACE -> ctx.screenshotTraceTotal > 0
}

internal data class ReferenceActivationContext(
    val policyRows: Int,
    val policyBoundaryRows: Int,
    val interactionFlowTotal: Int,
    val selfModelTotal: Int,
    val evidenceItems: Int,
    val changedFiles: Int,
    val verifications: Int,
    val parityTotal: Int,
    val handoffRecommended: Boolean,
    val referenceBoardTotal: Int,
    val blueprintTotal: Int,
    val visualAcceptanceTotal: Int,
    val designSystemTotal: Int,
    val screenshotExtractionTotal: Int,
    val screenshotTraceTotal: Int,
)

internal fun ReferenceGroup.activeCount(ctx: ReferenceActivationContext): Int =
    cards.count { it.isActive(ctx) }
