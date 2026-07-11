package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTabsModelTest {

    private val emptyCtx = ReferenceActivationContext(
        policyRows = 0, policyBoundaryRows = 0,
        interactionFlowTotal = 0, selfModelTotal = 0,
        evidenceItems = 0, changedFiles = 0, verifications = 0,
        parityTotal = 0, handoffRecommended = false,
        referenceBoardTotal = 0, blueprintTotal = 0,
        visualAcceptanceTotal = 0, designSystemTotal = 0,
        screenshotExtractionTotal = 0, screenshotTraceTotal = 0,
    )

    @Test
    fun progressTabsAreCurrentOutputReferences() {
        val tabs = progressTabSpecs(
            currentBadge = 1,
            outputBadge = 2,
            referencesBadge = 3,
        )

        assertEquals(
            listOf(ProgressSection.CURRENT, ProgressSection.OUTPUT, ProgressSection.REFERENCES),
            tabs.map { it.section },
        )
        assertEquals(listOf("当前", "产物", "参考"), tabs.map { it.label })
        assertEquals(listOf(1, 2, 3), tabs.map { it.badge })
    }

    @Test
    fun progressTabsBadgeZeroIsAllowed() {
        val tabs = progressTabSpecs(currentBadge = 0, outputBadge = 0, referencesBadge = 0)
        assertEquals(listOf(0, 0, 0), tabs.map { it.badge })
    }

    @Test
    fun referenceGroupsCoverAllCardsWithoutOverlap() {
        val grouped = ReferenceGroup.entries.flatMap { it.cards }
        // 每张卡恰好出现一次（无重叠、无遗漏）
        assertEquals(ReferenceCard.entries.size, grouped.size)
        assertEquals(ReferenceCard.entries.size, grouped.toSet().size)
        assertEquals(ReferenceCard.entries.toSet(), grouped.toSet())
    }

    @Test
    fun referenceGroupCardAssignmentsAreStable() {
        // 分组归属契约：防止误调整卡片归属导致折叠默认状态错位
        assertEquals(
            listOf(ReferenceCard.TOOL_POLICY, ReferenceCard.INTERACTION_FLOW, ReferenceCard.SELF_MODEL),
            ReferenceGroup.EXECUTION_AND_APPROVAL.cards,
        )
        assertEquals(
            listOf(
                ReferenceCard.EVIDENCE_LEDGER,
                ReferenceCard.DELIVERY_REPORT,
                ReferenceCard.CODEX_PARITY,
                ReferenceCard.HANDOFF_ADVICE,
            ),
            ReferenceGroup.EVIDENCE_AND_DELIVERY.cards,
        )
        assertEquals(
            listOf(
                ReferenceCard.UI_REFERENCE_BOARD,
                ReferenceCard.UI_REPLICA_BLUEPRINT,
                ReferenceCard.VISUAL_ACCEPTANCE,
                ReferenceCard.DESIGN_SYSTEM,
                ReferenceCard.SCREENSHOT_EXTRACTION,
                ReferenceCard.SCREENSHOT_TRACE,
            ),
            ReferenceGroup.UI_REPLICATION_AND_DESIGN.cards,
        )
    }

    @Test
    fun referenceGroupTitlesAreNotEmpty() {
        ReferenceGroup.entries.forEach { group ->
            assertTrue("分组 ${group.name} 标题不应为空", group.title.isNotBlank())
        }
    }

    @Test
    fun noCardIsActiveOnEmptyContext() {
        ReferenceCard.entries.forEach { card ->
            assertFalse("$card 应当在空上下文下不激活", card.isActive(emptyCtx))
        }
    }

    @Test
    fun toolPolicyCardActivatesOnPolicyRows() {
        assertTrue(ReferenceCard.TOOL_POLICY.isActive(emptyCtx.copy(policyRows = 1)))
        assertTrue(ReferenceCard.TOOL_POLICY.isActive(emptyCtx.copy(policyBoundaryRows = 1)))
    }

    @Test
    fun interactionFlowCardActivatesOnTotal() {
        assertTrue(ReferenceCard.INTERACTION_FLOW.isActive(emptyCtx.copy(interactionFlowTotal = 1)))
    }

    @Test
    fun selfModelCardActivatesOnTotal() {
        assertTrue(ReferenceCard.SELF_MODEL.isActive(emptyCtx.copy(selfModelTotal = 1)))
    }

    @Test
    fun evidenceLedgerCardActivatesOnItems() {
        assertTrue(ReferenceCard.EVIDENCE_LEDGER.isActive(emptyCtx.copy(evidenceItems = 1)))
    }

    @Test
    fun deliveryReportCardActivatesOnAnyArtifact() {
        // 变更、验证、证据任一非零即激活
        assertTrue(ReferenceCard.DELIVERY_REPORT.isActive(emptyCtx.copy(changedFiles = 1)))
        assertTrue(ReferenceCard.DELIVERY_REPORT.isActive(emptyCtx.copy(verifications = 1)))
        assertTrue(ReferenceCard.DELIVERY_REPORT.isActive(emptyCtx.copy(evidenceItems = 1)))
    }

    @Test
    fun codexParityCardActivatesOnTotal() {
        assertTrue(ReferenceCard.CODEX_PARITY.isActive(emptyCtx.copy(parityTotal = 1)))
    }

    @Test
    fun handoffAdviceCardActivatesOnlyOnRecommendedOrRequired() {
        // OK / WATCH 不激活
        assertFalse(ReferenceCard.HANDOFF_ADVICE.isActive(emptyCtx.copy(handoffRecommended = false)))
        // RECOMMENDED / REQUIRED 激活
        assertTrue(ReferenceCard.HANDOFF_ADVICE.isActive(emptyCtx.copy(handoffRecommended = true)))
    }

    @Test
    fun uiReferenceBoardCardActivatesOnTotal() {
        assertTrue(ReferenceCard.UI_REFERENCE_BOARD.isActive(emptyCtx.copy(referenceBoardTotal = 1)))
    }

    @Test
    fun uiReplicaBlueprintCardActivatesOnAnyComponent() {
        assertTrue(ReferenceCard.UI_REPLICA_BLUEPRINT.isActive(emptyCtx.copy(blueprintTotal = 1)))
    }

    @Test
    fun visualAcceptanceCardActivatesOnTotal() {
        assertTrue(ReferenceCard.VISUAL_ACCEPTANCE.isActive(emptyCtx.copy(visualAcceptanceTotal = 1)))
    }

    @Test
    fun designSystemCardActivatesOnTotal() {
        assertTrue(ReferenceCard.DESIGN_SYSTEM.isActive(emptyCtx.copy(designSystemTotal = 1)))
    }

    @Test
    fun screenshotExtractionCardActivatesOnTotal() {
        assertTrue(ReferenceCard.SCREENSHOT_EXTRACTION.isActive(emptyCtx.copy(screenshotExtractionTotal = 1)))
    }

    @Test
    fun screenshotTraceCardActivatesOnTotal() {
        assertTrue(ReferenceCard.SCREENSHOT_TRACE.isActive(emptyCtx.copy(screenshotTraceTotal = 1)))
    }

    @Test
    fun groupActiveCountSumsActivatedCards() {
        val ctx = emptyCtx.copy(policyRows = 2, policyBoundaryRows = 1)
        // 执行与审批组：TOOL_POLICY 激活，其余两张不激活 → 1
        assertEquals(1, ReferenceGroup.EXECUTION_AND_APPROVAL.activeCount(ctx))
        // 证据与交付组：全不激活 → 0
        assertEquals(0, ReferenceGroup.EVIDENCE_AND_DELIVERY.activeCount(ctx))
    }

    @Test
    fun executionGroupFullyActivated() {
        val ctx = emptyCtx.copy(
            policyRows = 1,
            interactionFlowTotal = 1,
            selfModelTotal = 1,
        )
        assertEquals(3, ReferenceGroup.EXECUTION_AND_APPROVAL.activeCount(ctx))
    }

    @Test
    fun evidenceGroupFullyActivated() {
        val ctx = emptyCtx.copy(
            evidenceItems = 1,
            changedFiles = 1,
            parityTotal = 1,
            handoffRecommended = true,
        )
        assertEquals(4, ReferenceGroup.EVIDENCE_AND_DELIVERY.activeCount(ctx))
    }

    @Test
    fun uiReplicationGroupFullyActivated() {
        val ctx = emptyCtx.copy(
            referenceBoardTotal = 1,
            blueprintTotal = 1,
            visualAcceptanceTotal = 1,
            designSystemTotal = 1,
            screenshotExtractionTotal = 1,
            screenshotTraceTotal = 1,
        )
        assertEquals(6, ReferenceGroup.UI_REPLICATION_AND_DESIGN.activeCount(ctx))
    }

    @Test
    fun allGroupsFullyActivatedYieldsThirteen() {
        val ctx = ReferenceActivationContext(
            policyRows = 1, policyBoundaryRows = 1,
            interactionFlowTotal = 1, selfModelTotal = 1,
            evidenceItems = 1, changedFiles = 1, verifications = 1,
            parityTotal = 1, handoffRecommended = true,
            referenceBoardTotal = 1, blueprintTotal = 1,
            visualAcceptanceTotal = 1, designSystemTotal = 1,
            screenshotExtractionTotal = 1, screenshotTraceTotal = 1,
        )
        // 全部激活时，三组合计 = 13（与卡总数一致）
        val total = ReferenceGroup.entries.sumOf { it.activeCount(ctx) }
        assertEquals(ReferenceCard.entries.size, total)
    }
}
