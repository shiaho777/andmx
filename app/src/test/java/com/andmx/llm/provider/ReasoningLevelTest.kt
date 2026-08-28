package com.andmx.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningLevelTest {

    @Test
    fun effortLevelCarriesBothKinds() {
        val max = ReasoningLevels.EFFORT_MAX
        assertEquals("max", max.id)
        val paths = max.rules.associate { it.kind to it.path }
        assertEquals("output_config.effort", paths[ProviderKind.ANTHROPIC])
        assertEquals("reasoning_effort", paths[ProviderKind.OPENAI])
    }

    @Test
    fun resolveLevelIdFallsBackToDefaultForUnknownValue() {
        val config = ReasoningConfig(
            levels = listOf(ReasoningLevels.EFFORT_MAX, ReasoningLevels.EFFORT_HIGH, ReasoningLevels.EFFORT_LOW),
            defaultLevel = "max",
        )
        assertEquals("high", config.resolveLevelId("high"))
        assertEquals("max", config.resolveLevelId("ultra"))
        assertEquals("max", config.resolveLevelId(null))
        assertEquals(ReasoningConfig.OFF_SENTINEL, config.resolveLevelId("off"))
    }

    @Test
    fun resolveLevelIdNullWithoutLevels() {
        assertNull(ReasoningConfig.OPENAI_EFFORT.resolveLevelId(null))
    }

    @Test
    fun thinkingBudgetLevelIsAnthropicOnly() {
        val level = ReasoningLevels.THINKING_MIN_BUDGET
        assertEquals(1, level.rules.size)
        assertEquals(ProviderKind.ANTHROPIC, level.rules[0].kind)
        assertEquals(1024, level.rules[0].budgetTokens)
    }
}
