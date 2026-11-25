package com.andmx.ui2.chat

import com.andmx.data.ConversationTransfer
import com.andmx.data.MessageEntity
import com.andmx.ui2.markdown.InteractiveCardSpec
import com.andmx.workspace.LocalWorktree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalParityTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun localPreviewStaysInsideItsDirectory() {
        val root = files.newFolder("project")
        val html = java.io.File(root, "index.html").apply { writeText("<h1>Preview</h1>") }
        val outside = files.newFile("outside.js")
        assertEquals(html.canonicalFile, localPreviewFile(root, "index.html"))
        assertNull(localPreviewFile(root, "../outside.js"))
        assertNull(localPreviewFile(root, ".git/config"))
        java.nio.file.Files.createSymbolicLink(java.io.File(root, "redirect.js").toPath(), outside.toPath())
        assertNull(localPreviewFile(root, "redirect.js"))
    }

    @Test
    fun browserAcceptsOnlyWebUrls() {
        assertNotNull(browserUrl("http://127.0.0.1:3000/dev"))
        assertNotNull(browserUrl("https://example.org"))
        assertNull(browserUrl("javascript:alert(1)"))
        assertNull(browserUrl("file:///data/local/tmp/index.html"))
        assertNull(browserUrl("https://name:pass@example.org"))
    }

    @Test
    fun rejectsExecutableFieldsAndDuplicateIds() {
        fun card(fields: String) = """{"title":"Options","fields":[$fields]}"""
        val text = """{"id":"name","label":"Name","type":"text"}"""
        val action = """{"id":"run","label":"Run","type":"javascript"}"""
        assertNotNull(InteractiveCardSpec.parse(card(text)))
        assertNull(InteractiveCardSpec.parse(card("$text,$text")))
        assertNull(InteractiveCardSpec.parse(card(action)))
    }

    @Test
    fun portableHistoryIncludesOnlyConversationText() {
        val rows = listOf(
            MessageEntity(conversationId = 1, role = "user", content = "hello"),
            MessageEntity(conversationId = 1, role = "tool", content = "internal tool output"),
            MessageEntity(conversationId = 1, role = "assistant", content = "hi"),
        )
        val transfer = ConversationTransfer.read(ConversationTransfer.serialize("Task", rows).byteInputStream())
        assertEquals("Task", transfer.title)
        assertEquals(listOf("hello", "hi"), transfer.messages.map { it.text })
    }

    @Test
    fun worktreeBindingStaysWithItsCoroutine() = runTest {
        val first = LocalWorktree("/project-one", "/root/.andmx/worktrees/a")
        val second = LocalWorktree("/project-two", "/root/.andmx/worktrees/b")
        val a = async(first.context()) { withContext(Dispatchers.Default) { LocalWorktree.current() } }
        val b = async(second.context()) { withContext(Dispatchers.Default) { LocalWorktree.current() } }
        assertEquals(first, a.await())
        assertEquals(second, b.await())
        assertNull(LocalWorktree.current())
    }
}
