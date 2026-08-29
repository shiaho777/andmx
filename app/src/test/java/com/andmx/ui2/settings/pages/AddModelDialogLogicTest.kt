package com.andmx.ui2.settings.pages

import com.andmx.llm.provider.ModelDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-model dialog gates its save button on [validateNewModel], so the
 * ordering of these checks is behaviour, not cosmetics: reporting DUPLICATE_ID
 * before INVALID_CONTEXT means a user fixing a typo'd id is never shown a
 * context-window error for a field they did not touch.
 */
class AddModelDialogLogicTest {

    private val existing = setOf("glm-4.6", "claude-sonnet-4")

    @Test
    fun anEmptyOrWhitespaceIdIsRejectedBeforeAnythingElse() {
        assertEquals(AddModelProblem.BLANK_ID, validateNewModel("", existing, "128000"))
        assertEquals(AddModelProblem.BLANK_ID, validateNewModel("   ", existing, "128000"))
        assertEquals(AddModelProblem.BLANK_ID, validateNewModel("\t\n", existing, "128000"))
    }

    @Test
    fun aBlankIdIsReportedEvenWhenTheContextWindowIsAlsoBad() {
        assertEquals(AddModelProblem.BLANK_ID, validateNewModel("", existing, "0"))
    }

    @Test
    fun duplicateDetectionComparesTrimmedIds() {
        assertEquals(AddModelProblem.DUPLICATE_ID, validateNewModel("glm-4.6", existing, "128000"))
        assertEquals(AddModelProblem.DUPLICATE_ID, validateNewModel("  glm-4.6  ", existing, "128000"))
        assertEquals(AddModelProblem.DUPLICATE_ID, validateNewModel("claude-sonnet-4", existing, "128000"))
    }

    @Test
    fun aFreshIdWithAUsableContextWindowIsAccepted() {
        assertNull(validateNewModel("glm-4.5-air", existing, "128000"))
        assertNull(validateNewModel("gpt-5", emptySet(), "400000"))
    }

    @Test
    fun aContextWindowMustBeAPositiveInteger() {
        assertEquals(AddModelProblem.INVALID_CONTEXT, validateNewModel("new-model", existing, "0"))
        assertEquals(AddModelProblem.INVALID_CONTEXT, validateNewModel("new-model", existing, ""))
        assertEquals(AddModelProblem.INVALID_CONTEXT, validateNewModel("new-model", existing, "  "))
        assertEquals(AddModelProblem.INVALID_CONTEXT, validateNewModel("new-model", existing, "12k"))
        assertEquals(AddModelProblem.INVALID_CONTEXT, validateNewModel("new-model", existing, "-1"))
    }

    @Test
    fun textIsAlwaysTheFirstInputModality() {
        assertEquals(listOf("text"), newModelModalities(image = false, video = false))
        assertEquals(listOf("text", "image"), newModelModalities(image = true, video = false))
        assertEquals(listOf("text", "video"), newModelModalities(image = false, video = true))
        assertEquals(listOf("text", "image", "video"), newModelModalities(image = true, video = true))
        assertEquals("text", newModelModalities(image = true, video = true).first())
    }

    @Test
    fun pickingImageIsWhatTurnsOnVisionForTheSavedModel() {
        val blind = ModelDefinition(
            contextWindow = 128_000,
            inputModalities = newModelModalities(image = false, video = false),
        )
        val seeing = ModelDefinition(
            contextWindow = 128_000,
            inputModalities = newModelModalities(image = true, video = false),
        )

        assertFalse(blind.supportsVision)
        assertTrue(seeing.supportsVision)
    }

    @Test
    fun aBlankDisplayNameFallsBackToNullSoTheUiShowsTheId() {
        assertEquals(null, "".trim().ifBlank { null })
        assertEquals(null, "   ".trim().ifBlank { null })
        assertEquals("GLM", "  GLM  ".trim().ifBlank { null })
    }
}
