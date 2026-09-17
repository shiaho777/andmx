package com.andmx.ui2.settings.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelMetadataValidationTest {
    @Test
    fun storedUnspecifiedCountsRoundTripThroughEditorValidation() {
        val draft = ModelMetadataDraft(
            id = "model",
            contextWindowText = metadataTokenText(0),
            maxOutputTokensText = metadataTokenText(0),
            inputModalities = newModelModalities(setOf("image")),
        )
        assertEquals("", draft.contextWindowText)
        assertEquals(listOf("text", "image"), draft.inputModalities)
        assertNull(validateModelMetadata(draft))
    }

    @Test
    fun eachValidationFailureKeepsItsUserFacingMessage() {
        val valid = ModelMetadataDraft("model", "128000", "8192", listOf("text"))
        val cases = listOf(
            valid.copy(id = " ") to "模型 ID 不能为空",
            valid.copy(contextWindowText = "-1") to "上下文窗口必须是正整数",
            valid.copy(maxOutputTokensText = "NaN") to "最大输出 Token 必须是正整数",
            valid.copy(inputModalities = listOf("image")) to "输入类型必须包含文本",
        )
        cases.forEach { (draft, expected) ->
            val problem = requireNotNull(validateModelMetadata(draft))
            assertEquals(expected, modelMetadataProblemText(problem))
        }
    }
}
