package com.andmx.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionPurgeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(rollout: String, sessionId: String) = ConversationEntity(
        id = 7,
        project = "/root",
        title = "t",
        rolloutPath = rollout,
        sessionId = sessionId,
    )

    @Test
    fun listsRolloutAndTopicFiles() {
        val dir = tmp.newFolder("files")
        val rollout = File(dir, "rollouts/rollout-x.jsonl").absolutePath
        val paths = purgePathsFor(dir, session(rollout, "sess-1"))
        assertEquals(
            listOf(rollout, File(dir, "topics/sess-1.jsonl").absolutePath),
            paths.map { it.absolutePath },
        )
    }

    @Test
    fun blankMetadataYieldsNoPaths() {
        assertTrue(purgePathsFor(tmp.newFolder("files"), session("", "")).isEmpty())
    }

    @Test
    fun sessionIdIsSanitizedLikeTopicArchive() {
        val dir = tmp.newFolder("files")
        val paths = purgePathsFor(dir, session("", "a/b:c*d"))
        assertEquals(listOf(File(dir, "topics/a_b_c_d.jsonl").absolutePath), paths.map { it.absolutePath })
    }

    @Test
    fun rolloutOnlySessionSkipsTopicFile() {
        val dir = tmp.newFolder("files")
        val rollout = File(dir, "rollouts/r.jsonl").absolutePath
        val paths = purgePathsFor(dir, session(rollout, ""))
        assertEquals(listOf(rollout), paths.map { it.absolutePath })
    }

    @Test
    fun purgeMatchesTopicArchiveFileName() {
        val dir = tmp.newFolder("files")
        val topicsDir = File(dir, "topics")
        val archive = TopicArchive(topicsDir)
        kotlinx.coroutines.runBlocking {
            archive.append("s-9", TopicRecord("m1", "hello", "user", 1L))
        }
        val paths = purgePathsFor(dir, session("", "s-9"))
        assertTrue(paths.single().exists())
        paths.single().delete()
        assertTrue(topicsDir.listFiles().orEmpty().isEmpty())
    }
}