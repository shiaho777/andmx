package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRefsTest {

    @Test
    fun extractsPaths() {
        val refs = FileRefs.parse("look at @/root/app.py and @src/main.kt please")
        assertEquals(listOf("/root/app.py", "src/main.kt"), refs)
    }

    @Test
    fun ignoresEmails() {
        // an email's @ is preceded by a word char, so it should not be treated as a ref
        assertTrue(FileRefs.parse("mail me at a@b.com").isEmpty())
    }

    @Test
    fun augmentInlinesFileContent() {
        val out = FileRefs.augment("check @/x.txt") { path ->
            if (path == "/x.txt") "hello\nworld" else null
        }
        assertTrue(out.contains("check @/x.txt"))
        assertTrue(out.contains("引用文件 @/x.txt"))
        assertTrue(out.contains("hello\nworld"))
    }

    @Test
    fun augmentNotesMissingFile() {
        val out = FileRefs.augment("see @/nope") { null }
        assertTrue(out.contains("读取失败"))
    }
}
