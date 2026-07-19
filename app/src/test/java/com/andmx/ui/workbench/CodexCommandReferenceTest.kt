package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCommandReferenceTest {

    @Test
    fun commandReferenceSummarizesShortcutsSlashCommandsAndDeepLinks() {
        val reference = buildCodexCommandReference()

        assertEquals("Codex 命令与快捷入口地图", reference.title)
        assertTrue(reference.itemCount > 20)
        assertTrue(reference.shortcutCount >= 6)
        assertTrue(reference.slashCount >= 12)
        assertTrue(reference.deepLinkCount >= 5)
        assertEquals("/commands", reference.primaryCommand)
    }

    @Test
    fun commandReferenceTextIncludesCodexAppNavigationModel() {
        val text = codexCommandReferenceText(buildCodexCommandReference())

        assertTrue(text.contains("## Codex 命令地图"))
        assertTrue(text.contains("### 全局快捷"))
        assertTrue(text.contains("Cmd/Ctrl+K"))
        assertTrue(text.contains("Cmd/Ctrl+G"))
        assertTrue(text.contains("Cmd/Ctrl+F"))
        assertTrue(text.contains("### 命令发现"))
        assertTrue(text.contains("命令: `/goal`"))
        assertTrue(text.contains("命令: `/improve`"))
        assertTrue(text.contains("命令: `/appshots`"))
        assertTrue(text.contains("命令: `${'$'}skill`"))
        assertTrue(text.contains("### 工作台动作"))
        assertTrue(text.contains("### 深链模型"))
        assertTrue(text.contains("codex://threads/new"))
        assertTrue(text.contains("codex://settings"))
        assertTrue(text.contains("codex://skills"))
        assertTrue(text.contains("### 安全备注"))
        assertTrue(text.contains("不能自动化 Codex 自身"))
    }
}
