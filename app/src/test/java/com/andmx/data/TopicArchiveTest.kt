package com.andmx.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(id: String, text: String, sender: String = "user") =
        TopicRecord(id = id, text = text, senderType = sender, createdAt = id.hashCode().toLong())

    @Test
    fun appendAndReadRoundtrip() = runTest {
        val archive = TopicArchive(tmp.newFolder("topics"))
        archive.append("s1", record("m1", "修复登录 bug"))
        archive.append("s1", record("m2", "重构支付模块"))
        archive.append("s1", record("m3", "assistant 回复", sender = "assistant"))
        val result = archive.read("s1", query = "", cursor = 0, maxTokens = 6000)
        assertEquals(2, result.records.size)
        assertEquals("m1", result.records[0].id)
        assertNull(result.nextCursor)
    }

    @Test
    fun duplicateIdIgnored() = runTest {
        val archive = TopicArchive(tmp.newFolder("topics"))
        archive.append("s1", record("m1", "第一条"))
        archive.append("s1", record("m1", "重复写入"))
        val result = archive.read("s1", "", 0, 6000)
        assertEquals(1, result.records.size)
        assertEquals("第一条", result.records[0].text)
    }

    @Test
    fun queryFiltersCaseInsensitive() = runTest {
        val archive = TopicArchive(tmp.newFolder("topics"))
        archive.append("s1", record("m1", "Fix Login Bug"))
        archive.append("s1", record("m2", "支付重构"))
        val result = archive.read("s1", "login", 0, 6000)
        assertEquals(1, result.records.size)
        assertEquals("m1", result.records[0].id)
    }

    @Test
    fun paginationCursorAdvances() = runTest {
        val archive = TopicArchive(tmp.newFolder("topics"))
        repeat(25) { i -> archive.append("s1", record("m$i", "消息 $i")) }
        val page1 = archive.read("s1", "", 0, 12000)
        assertEquals(20, page1.records.size)
        assertEquals(20, page1.nextCursor)
        assertTrue(page1.truncated)
        val page2 = archive.read("s1", "", page1.nextCursor!!, 12000)
        assertEquals(5, page2.records.size)
        assertNull(page2.nextCursor)
    }

    @Test
    fun budgetClipsLongRecords() = runTest {
        val archive = TopicArchive(tmp.newFolder("topics"))
        archive.append("s1", record("m1", "长".repeat(50_000)))
        val result = archive.read("s1", "", 0, 256)
        assertEquals(1, result.records.size)
        assertTrue(result.records[0].clipped)
        assertTrue(result.records[0].text.length < 50_000)
        assertTrue(result.truncated)
    }

    @Test
    fun missingSessionReturnsEmpty() = runTest {
        val archive = TopicArchive(tmp.newFolder("topics"))
        val result = archive.read("nope", "", 0, 6000)
        assertTrue(result.records.isEmpty())
        assertEquals(0, result.total)
    }

    @Test
    fun deleteRemovesArchive() = runTest {
        val archive = TopicArchive(tmp.newFolder("topics"))
        archive.append("s1", record("m1", "内容"))
        archive.delete("s1")
        assertTrue(archive.read("s1", "", 0, 6000).records.isEmpty())
    }
}