package com.andmx.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReferencesTest {

    @Test
    fun extractsMarkdownLinksUrlsAndFilePaths() {
        val refs = messageReferences(
            """
            已更新 [AgentInspectorPane.kt](app/src/main/java/com/andmx/ui/workbench/AgentInspectorPane.kt)
            参考 https://example.com/docs.
            也可以打开 `/root/app/build.gradle.kts` 和 @src/main/App.kt
            """.trimIndent(),
        )

        assertEquals(MessageReferenceKind.FILE, refs[0].kind)
        assertEquals("AgentInspectorPane.kt", refs[0].label)
        assertEquals("/root/app/src/main/java/com/andmx/ui/workbench/AgentInspectorPane.kt", refs[0].target)
        assertEquals(MessageReferenceKind.WEB, refs[1].kind)
        assertEquals("https://example.com/docs", refs[1].target)
        assertEquals("/root/app/build.gradle.kts", refs[2].target)
        assertEquals("/root/src/main/App.kt", refs[3].target)
    }

    @Test
    fun keepsRootRelativePathsAbsoluteInsideGuest() {
        val refs = messageReferences("[APK](root/build/app.apk) and `~/notes/todo.md`")

        assertEquals("/root/build/app.apk", refs[0].target)
        assertEquals("/root/notes/todo.md", refs[1].target)
    }

    @Test
    fun dedupesLimitsAndIgnoresCodeBlocks() {
        val refs = messageReferences(
            """
            `app/a.kt` `app/a.kt` `app/b.kt` `app/c.kt` `app/d.kt` `app/e.kt` `app/f.kt`
            ```
            https://hidden.example.com
            `app/hidden.kt`
            ```
            """.trimIndent(),
            limit = 3,
        )

        assertEquals(listOf("/root/app/a.kt", "/root/app/b.kt", "/root/app/c.kt"), refs.map { it.target })
        assertTrue(refs.none { it.target.contains("hidden") })
    }

    @Test
    fun doesNotTreatSlashCommandsAsFiles() {
        val refs = messageReferences("运行 `/status` 或 `/diag`, 然后打开 `app/src/Main.kt`")

        assertEquals(listOf("/root/app/src/Main.kt"), refs.map { it.target })
    }

    @Test
    fun extractsUiReferenceLinesWithMetadataAndReferenceId() {
        val refs = messageReferences(
            """
            按这个复刻
            🖼 Codex command palette screenshot.png · UI 参考 · 1440x900 · image/png · 421.9 KB · ref:abcd1234
            另见 `app/src/main/java/com/andmx/ui/workbench/CommandPalette.kt`
            """.trimIndent(),
        )

        assertEquals(MessageReferenceKind.UI_REFERENCE, refs[0].kind)
        assertEquals("Codex command palette screenshot.png · UI 参考", refs[0].label)
        assertEquals("ref:abcd1234", refs[0].target)
        assertEquals("1440x900 · image/png · 421.9 KB · ref:abcd1234", refs[0].meta)
        assertEquals("/references", refs[0].command)
        assertEquals(MessageReferenceKind.FILE, refs[1].kind)
        assertTrue(refs[1].target.endsWith("CommandPalette.kt"))
        assertEquals("", refs[1].command)
    }

    @Test
    fun extractsAttachmentReferenceLinesWithoutReferenceId() {
        val refs = messageReferences("📎 notes.md")

        assertEquals(1, refs.size)
        assertEquals(MessageReferenceKind.UI_REFERENCE, refs.first().kind)
        assertEquals("notes.md", refs.first().label)
        assertEquals("notes.md", refs.first().target)
        assertEquals("", refs.first().meta)
    }
}
