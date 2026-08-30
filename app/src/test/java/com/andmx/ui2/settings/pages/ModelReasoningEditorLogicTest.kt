package com.andmx.ui2.settings.pages

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningStyle
import com.andmx.llm.wire.AnthropicMessagesAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-model reasoning editor holds a [ReasoningDraft] (choice + levels +
 * default + budget text) while the stored model holds a [ReasoningConfig].
 * [buildReasoningConfig] and [splitReasoningConfig] convert between them on
 * every chip tap and every keystroke.
 *
 * If they drift, the visible symptom is nasty: the user opens a model that was
 * saved with "high", looks at it, changes nothing, and the config silently
 * rewrites itself. So the round-trip is asserted exhaustively over every level
 * combination rather than on a single happy path.
 *
 * A second hazard is data loss on preset models: [orderEffortLevels] must keep
 * levels it does not recognise (e.g. "xhigh") instead of filtering them out,
 * otherwise editing any catalog model would quietly narrow its capability.
 */
class ModelReasoningEditorLogicTest {

    private fun draft(
        choice: ReasoningChoice,
        levels: List<String> = emptyList(),
        defaultLevel: String = "",
        budgetText: String = DEFAULT_THINKING_BUDGET_TEXT,
    ) = ReasoningDraft(choice, levels, defaultLevel, budgetText)

    @Test
    fun choosingNoneDropsTheConfigInsteadOfStoringAnEmptyShell() {
        assertNull(buildReasoningConfig(draft(ReasoningChoice.NONE, listOf("low"), "low")))
        assertNull(buildReasoningConfig(draft(ReasoningChoice.NONE)))
    }

    @Test
    fun effortLevelsAreStoredInLadderOrderWhateverTheUserClicked() {
        val config = buildReasoningConfig(
            draft(ReasoningChoice.EFFORT, listOf("high", "minimal", "low"), "low"),
        )!!

        assertEquals(ReasoningStyle.EFFORT, config.style)
        assertEquals(listOf("minimal", "low", "high"), config.effortLevels)
    }

    @Test
    fun levelsOutsideTheLadderAreKeptRatherThanFilteredAway() {
        val config = buildReasoningConfig(
            draft(ReasoningChoice.EFFORT, listOf("xhigh", "low", "minimal"), "low"),
        )!!

        assertEquals(listOf("minimal", "low", "xhigh"), config.effortLevels)
    }

    @Test
    fun theDefaultEffortFallsBackToTheLowestSelectedLevel() {
        val config = buildReasoningConfig(
            draft(ReasoningChoice.EFFORT, listOf("low", "medium"), "high"),
        )!!

        assertEquals("low", config.defaultEffort)
    }

    @Test
    fun theDefaultEffortIsKeptWhenItIsAmongTheSelectedLevels() {
        val config = buildReasoningConfig(
            draft(ReasoningChoice.EFFORT, listOf("low", "high"), "high"),
        )!!

        assertEquals("high", config.defaultEffort)
    }

    @Test
    fun selectingNoLevelAtAllLeavesTheDefaultEmpty() {
        val config = buildReasoningConfig(draft(ReasoningChoice.EFFORT, emptyList(), "high"))!!

        assertEquals(emptyList<String>(), config.effortLevels)
        assertEquals("", config.defaultEffort)
    }

    @Test
    fun aThinkingBudgetBelowTheAnthropicFloorIsClampedUp() {
        val floor = AnthropicMessagesAdapter.MIN_THINKING_BUDGET

        assertEquals(1024, floor)
        assertEquals(floor, buildReasoningConfig(draft(ReasoningChoice.THINKING, budgetText = "512"))!!.defaultBudgetTokens)
        assertEquals(floor, buildReasoningConfig(draft(ReasoningChoice.THINKING, budgetText = "1023"))!!.defaultBudgetTokens)
    }

    @Test
    fun aThinkingBudgetThatIsNotUsableFallsBackToTheDefault() {
        val fallback = ReasoningConfig.ANTHROPIC_THINKING.defaultBudgetTokens

        assertEquals(16_000, fallback)
        assertEquals("16000", DEFAULT_THINKING_BUDGET_TEXT)
        assertEquals(fallback, buildReasoningConfig(draft(ReasoningChoice.THINKING, budgetText = ""))!!.defaultBudgetTokens)
        assertEquals(fallback, buildReasoningConfig(draft(ReasoningChoice.THINKING, budgetText = "abc"))!!.defaultBudgetTokens)
    }

    @Test
    fun onlyIntegersAtOrAboveTheFloorAreValidBudgets() {
        assertTrue(validateThinkingBudget("1024"))
        assertTrue(validateThinkingBudget("16000"))
        assertFalse(validateThinkingBudget("1023"))
        assertFalse(validateThinkingBudget("0"))
        assertFalse(validateThinkingBudget(""))
        assertFalse(validateThinkingBudget("abc"))
    }

    @Test
    fun splittingANullConfigYieldsTheNoneChoice() {
        val draft = splitReasoningConfig(null)

        assertEquals(ReasoningChoice.NONE, draft.choice)
        assertEquals(emptyList<String>(), draft.levels)
        assertEquals("", draft.defaultLevel)
        assertEquals("16000", draft.budgetText)
    }

    @Test
    fun splittingAnEffortConfigRestoresLevelsAndDefault() {
        val config = ReasoningConfig(
            style = ReasoningStyle.EFFORT,
            effortLevels = listOf("high", "low"),
            defaultEffort = "low",
        )

        val draft = splitReasoningConfig(config)

        assertEquals(ReasoningChoice.EFFORT, draft.choice)
        assertEquals(listOf("low", "high"), draft.levels)
        assertEquals("low", draft.defaultLevel)
    }

    @Test
    fun splittingAnEffortConfigDropsADefaultThatIsNotAmongItsLevels() {
        val config = ReasoningConfig(
            style = ReasoningStyle.EFFORT,
            effortLevels = listOf("low"),
            defaultEffort = "high",
        )

        assertEquals("", splitReasoningConfig(config).defaultLevel)
    }

    @Test
    fun splittingAThinkingConfigRestoresTheBudgetAsText() {
        val draft = splitReasoningConfig(
            ReasoningConfig(style = ReasoningStyle.THINKING, defaultBudgetTokens = 8192),
        )

        assertEquals(ReasoningChoice.THINKING, draft.choice)
        assertEquals("8192", draft.budgetText)
    }

    @Test
    fun theRoundTripIsStableForEveryLevelCombination() {
        for (mask in 0 until (1 shl EFFORT_LADDER.size)) {
            val subset = EFFORT_LADDER.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            for (default in subset + "bogus" + "") {
                val once = buildReasoningConfig(draft(ReasoningChoice.EFFORT, subset, default))
                val twice = buildReasoningConfig(splitReasoningConfig(once))
                assertEquals("mask=$mask default=$default", once, twice)
            }
        }
    }

    @Test
    fun theRoundTripIsStableForEveryKindOfBudgetInput() {
        for (budget in listOf("1024", "16000", "65536", "512", "1023", "0", "", "abc")) {
            val once = buildReasoningConfig(draft(ReasoningChoice.THINKING, budgetText = budget))
            val twice = buildReasoningConfig(splitReasoningConfig(once))
            assertEquals("budget=$budget", once, twice)
        }
    }

    @Test
    fun declaringEffortIsWhatMakesAReasoningSelectorAppearForTheModel() {
        val inert = ModelDefinition(
            contextWindow = 128_000,
            reasoning = buildReasoningConfig(draft(ReasoningChoice.NONE)),
        )
        val capable = ModelDefinition(
            contextWindow = 128_000,
            reasoning = buildReasoningConfig(
                draft(ReasoningChoice.EFFORT, listOf("low", "high"), "high"),
            ),
        )

        assertFalse(inert.supportsReasoning)
        assertNull(inert.reasoning)
        assertTrue(capable.supportsReasoning)
    }
}
