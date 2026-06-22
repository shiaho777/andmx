package com.andmx.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffParserTest {

    @Test
    fun parsesSingleFileDiff() {
        val diff = """
            diff --git a/app.py b/app.py
            index e69de29..4b825dc 100644
            --- a/app.py
            +++ b/app.py
            @@ -1,3 +1,3 @@
             import os
            -print("old")
            +print("new")
             done
        """.trimIndent()
        val files = GitDiffParser.parse(diff)
        assertEquals(1, files.size)
        assertEquals("app.py", files[0].path)
        val stats = DiffEngine.stats(files[0].lines)
        assertEquals(1, stats.added)
        assertEquals(1, stats.removed)
    }

    @Test
    fun parsesMultipleFiles() {
        val diff = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -a
            +A
            diff --git a/b.txt b/b.txt
            --- a/b.txt
            +++ b/b.txt
            @@ -0,0 +1 @@
            +new line
        """.trimIndent()
        val files = GitDiffParser.parse(diff)
        assertEquals(2, files.size)
        assertEquals(listOf("a.txt", "b.txt"), files.map { it.path })
        assertTrue(files[1].lines.any { it.kind == DiffLine.Kind.ADD && it.text == "new line" })
    }
}
