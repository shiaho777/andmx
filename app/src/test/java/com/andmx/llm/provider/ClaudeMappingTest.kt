package com.andmx.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZCode's `providerMappings.claude` is a fixed four-slot record:
 * `Xxn = f.object({haiku, sonnet, opus, reasoning})` in the engine, with the
 * enclosing `claude` block optional. Every slot is a plain string, and blank
 * means "not set" (`mappingNotSet` = 未设置).
 *
 * The slot ids are a closed set, so [claudeSlotValue] / [claudeMappingWithSlot]
 * are asserted to round-trip over all four — a slot that silently fails to
 * persist is exactly the kind of drift these tests exist to catch.
 */
class ClaudeMappingTest {

    @Test
    fun theSlotOrderMatchesTheZcodeEngineSchema() {
        assertEquals(listOf("haiku", "sonnet", "opus", "reasoning"), ClaudeModelMapping.SLOT_ORDER)
    }

    @Test
    fun everySlotCarriesTheZcodeWording() {
        val expected = mapOf(
            "haiku" to "Haiku（轻量任务）",
            "sonnet" to "Sonnet（常规任务）",
            "opus" to "Opus（复杂任务）",
            "reasoning" to "Reasoning（推理任务）",
        )
        for (slot in ClaudeModelMapping.SLOT_ORDER) {
            assertEquals(slot, expected[slot], claudeSlotLabel(slot))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test
    fun anUnconfiguredMappingReadsAsBlankForEverySlot() {
        for (slot in ClaudeModelMapping.SLOT_ORDER) {
            assertEquals("", claudeSlotValue(null, slot))
            assertEquals("", claudeSlotValue(ClaudeModelMapping(), slot))
        }
    }

    @Test
    fun settingASlotLeavesTheOtherThreeAlone() {
        for (slot in ClaudeModelMapping.SLOT_ORDER) {
            val mapping = claudeMappingWithSlot(null, slot, "my-model")
            assertEquals("slot=$slot", "my-model", claudeSlotValue(mapping, slot))
            for (other in ClaudeModelMapping.SLOT_ORDER - slot) {
                assertEquals("slot=$slot other=$other", "", claudeSlotValue(mapping, other))
            }
        }
    }

    @Test
    fun allFourSlotsSurviveBeingSetInSequence() {
        var mapping: ClaudeModelMapping? = null
        val picked = ClaudeModelMapping.SLOT_ORDER.associateWith { "model-$it" }
        for (slot in ClaudeModelMapping.SLOT_ORDER) {
            mapping = claudeMappingWithSlot(mapping, slot, picked.getValue(slot))
        }
        for (slot in ClaudeModelMapping.SLOT_ORDER) {
            assertEquals(slot, picked.getValue(slot), claudeSlotValue(mapping, slot))
        }
    }

    @Test
    fun writingABlankIdClearsTheSlot() {
        var mapping: ClaudeModelMapping? = claudeMappingWithSlot(null, "opus", "claude-opus-4")
        assertEquals("claude-opus-4", claudeSlotValue(mapping, "opus"))
        mapping = claudeMappingWithSlot(mapping, "opus", "   ")
        assertEquals("", claudeSlotValue(mapping, "opus"))
    }

    @Test
    fun valuesAreTrimmedOnTheWayIn() {
        val mapping = claudeMappingWithSlot(null, "sonnet", "  claude-sonnet-4  ")
        assertEquals("claude-sonnet-4", claudeSlotValue(mapping, "sonnet"))
    }

    @Test
    fun anUnknownSlotIsIgnoredRatherThanCorruptingTheRecord() {
        val mapping = claudeMappingWithSlot(null, "sonnet", "kept")
        val after = claudeMappingWithSlot(mapping, "gemini", "rogue")
        assertEquals(mapping, after)
        assertEquals("", claudeSlotValue(after, "gemini"))
        assertEquals("kept", claudeSlotValue(after, "sonnet"))
    }

    @Test
    fun aMappingCountsAsSetOnlyWhenAtLeastOneSlotIsFilled() {
        assertFalse(isClaudeMappingSet(null))
        assertFalse(isClaudeMappingSet(ClaudeModelMapping()))
        assertFalse(isClaudeMappingSet(ClaudeModelMapping(haiku = "   ", opus = "")))
        for (slot in ClaudeModelMapping.SLOT_ORDER) {
            assertTrue(slot, isClaudeMappingSet(claudeMappingWithSlot(null, slot, "m")))
        }
    }

    @Test
    fun anAllBlankMappingIsNormalizedAwayToMatchZcodeOptionalBlock() {
        assertNull(normalizeClaudeMapping(null))
        assertNull(normalizeClaudeMapping(ClaudeModelMapping()))
        assertNull(normalizeClaudeMapping(ClaudeModelMapping(haiku = "", opus = "  ")))
        val real = ClaudeModelMapping(haiku = "m")
        assertEquals(real, normalizeClaudeMapping(real))
    }

    @Test
    fun clearingTheLastFilledSlotDropsTheWholeMapping() {
        var mapping: ClaudeModelMapping? = claudeMappingWithSlot(null, "haiku", "m")
        assertTrue(isClaudeMappingSet(mapping))
        mapping = claudeMappingWithSlot(mapping, "haiku", "")
        assertNull(normalizeClaudeMapping(mapping))
    }
}
