package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressTabsModelTest {

    @Test
    fun progressTabsIncludeVerificationBetweenOutputAndSources() {
        val tabs = progressTabSpecs(
            checklistBadge = 1,
            outputBadge = 2,
            verificationBadge = 3,
            sourceBadge = 4,
            logBadge = 5,
        )

        assertEquals(
            listOf(
                ProgressSection.CHECKLIST,
                ProgressSection.STEPS,
                ProgressSection.OUTPUT,
                ProgressSection.VERIFY,
                ProgressSection.SOURCES,
                ProgressSection.LOG,
            ),
            tabs.map { it.section },
        )
        assertEquals(listOf("清单", "进度", "输出", "验证", "来源", "日志"), tabs.map { it.label })
        assertEquals(listOf(1, 0, 2, 3, 4, 5), tabs.map { it.badge })
    }

    @Test
    fun sourceBadgeIncludesVisualAcceptanceWaitingItems() {
        assertEquals(7, progressSourceBadge(toolEvents = 4, visualAcceptanceWaiting = 3))
        assertEquals(0, progressSourceBadge(toolEvents = 0, visualAcceptanceWaiting = 0))
    }

    @Test
    fun sourceBadgeIncludesDesignSystemOpenItems() {
        assertEquals(
            17,
            progressSourceBadge(
                toolEvents = 4,
                visualAcceptanceWaiting = 3,
                referenceBoardOpen = 2,
                designSystemOpen = 2,
                screenshotExtractionWaiting = 2,
                interactionFlowOpen = 3,
                selfModelOpen = 1,
            ),
        )
    }
}
