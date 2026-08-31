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
 *
 * [newModelModalities] and [splitInputModalities] are inverses: the dialog
 * holds a selection set while the stored model holds a list, and the inline
 * editor converts back and forth on every keystroke. If they ever drift, the
 * modality chips silently flip themselves, so the round-trip is asserted here.
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
    fun theModalityOrderMatchesZcodeCanonicalList() {
        assertEquals(listOf("text", "image", "video", "audio", "pdf"), MODALITY_ORDER)
    }

    @Test
    fun normalizationDropsUnknownEntriesReordersAndDeduplicates() {
        assertEquals(listOf("text"), normalizeInputModalities(listOf("text")))
        assertEquals(
            listOf("text", "image", "video"),
            normalizeInputModalities(listOf("video", "image", "text")),
        )
        assertEquals(
            listOf("text", "image"),
            normalizeInputModalities(listOf("image", "image", "text")),
        )
        assertEquals(emptyList<String>(), normalizeInputModalities(emptyList()))
        assertEquals(listOf("text", "pdf"), normalizeInputModalities(listOf("docx", "pdf", "text")))
    }

    @Test
    fun normalizationDoesNotSecretlyAddText() {
        // ZCode's o5 leaves the list alone; a missing text is a validation error,
        // not something to paper over during normalization.
        assertEquals(listOf("image"), normalizeInputModalities(listOf("image")))
        assertEquals(listOf("audio"), normalizeInputModalities(listOf("audio")))
        assertEquals(emptyList<String>(), normalizeInputModalities(listOf("docx")))
    }

    @Test
    fun textIsAlwaysTheFirstStoredModality() {
        assertEquals(listOf("text"), newModelModalities(setOf("text")))
        assertEquals(listOf("text", "image"), newModelModalities(setOf("image", "text")))
        assertEquals(
            listOf("text", "image", "video", "audio", "pdf"),
            newModelModalities(setOf("pdf", "audio", "video", "image", "text")),
        )
    }

    @Test
    fun everySubsetOfOptionalModalitiesSurvivesTheRoundTrip() {
        val optional = MODALITY_ORDER - "text"
        for (mask in 0 until (1 shl optional.size)) {
            val picked = optional.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet() + "text"
            val stored = newModelModalities(picked)
            assertEquals("mask=$mask", picked, splitInputModalities(stored))
            assertEquals("mask=$mask", "text", stored.first())
        }
    }

    @Test
    fun splittingAbsorbsUnknownValuesDuplicatesAndMissingText() {
        assertEquals(setOf("text"), splitInputModalities(emptyList()))
        assertEquals(setOf("text"), splitInputModalities(listOf("docx")))
        assertEquals(setOf("text", "image"), splitInputModalities(listOf("image")))
        assertEquals(setOf("text", "image", "video"), splitInputModalities(listOf("video", "image", "image")))
        assertEquals(setOf("text", "audio"), splitInputModalities(listOf("audio", "text")))
    }

    @Test
    fun pickingImageIsWhatTurnsOnVisionForTheSavedModel() {
        val blind = ModelDefinition(
            contextWindow = 128_000,
            inputModalities = newModelModalities(setOf("text")),
        )
        val seeing = ModelDefinition(
            contextWindow = 128_000,
            inputModalities = newModelModalities(setOf("text", "image")),
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

    @Test
    fun aFullyPopulatedDraftPassesValidation() {
        assertNull(
            validateModelMetadata(
                ModelMetadataDraft(
                    id = "glm-4.6",
                    contextWindowText = "128000",
                    maxOutputTokensText = "8192",
                    inputModalities = listOf("text", "image"),
                )
            )
        )
    }

    @Test
    fun theCascadeReportsTheFirstProblemInZcodeOrder() {
        assertEquals(
            ModelMetadataProblem.ID,
            validateModelMetadata(ModelMetadataDraft("", "0", "abc", listOf("image"))),
        )
        // "0" means "unspecified" in AndMX, so a real bad value is needed here.
        assertEquals(
            ModelMetadataProblem.CONTEXT_WINDOW,
            validateModelMetadata(ModelMetadataDraft("m", "-1", "abc", listOf("image"))),
        )
        assertEquals(
            ModelMetadataProblem.MAX_OUTPUT_TOKENS,
            validateModelMetadata(ModelMetadataDraft("m", "128000", "abc", listOf("image"))),
        )
        assertEquals(
            ModelMetadataProblem.INPUT_MODALITIES,
            validateModelMetadata(ModelMetadataDraft("m", "128000", "8192", listOf("image"))),
        )
    }

    @Test
    fun anInputListWithoutTextIsRejectedWhateverElseItHolds() {
        for (modalities in listOf(emptyList(), listOf("image"), listOf("image", "video"))) {
            assertEquals(
                ModelMetadataProblem.INPUT_MODALITIES,
                validateModelMetadata(ModelMetadataDraft("m", "128000", "8192", modalities)),
            )
        }
    }

    @Test
    fun anUnspecifiedTokenCountIsNotAnError() {
        // AndMX uses blank / 0 for "unset"; ZCode always has a value, so this
        // is the one deliberate divergence — a real number must still be valid.
        assertTrue(isValidTokenCount(""))
        assertTrue(isValidTokenCount("   "))
        assertTrue(isValidTokenCount("0"))
        assertTrue(isValidTokenCount("128000"))
        assertTrue(isValidTokenCount("1"))
        assertTrue(isValidTokenCount("3.0"))
        assertTrue(isValidTokenCount("1e3"))
    }

    @Test
    fun anythingThatIsNotAPositiveIntegerIsRejected() {
        for (raw in listOf("-1", "abc", "3.5", "12k", "1e400", "2147483648", "  -0.5 ")) {
            assertFalse("raw=$raw", isValidTokenCount(raw))
        }
    }

    @Test
    fun zeroAndBlankCollapseToAnEmptyTokenText() {
        assertEquals("", metadataTokenText(0))
        assertEquals("", metadataTokenText(-5))
        assertEquals("128000", metadataTokenText(128_000))
    }

    @Test
    fun everyProblemCarriesTheZcodeWording() {
        val expected = mapOf(
            ModelMetadataProblem.ID to "模型 ID 不能为空",
            ModelMetadataProblem.CONTEXT_WINDOW to "上下文窗口必须是正整数",
            ModelMetadataProblem.MAX_OUTPUT_TOKENS to "最大输出 Token 必须是正整数",
            ModelMetadataProblem.INPUT_MODALITIES to "输入类型必须包含文本",
        )
        for (problem in ModelMetadataProblem.values()) {
            assertEquals(problem.name, expected[problem], modelMetadataProblemText(problem))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }
}
