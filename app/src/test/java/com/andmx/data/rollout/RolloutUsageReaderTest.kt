package com.andmx.data.rollout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RolloutUsageReaderTest {

    private fun rollout(content: String): File {
        val file = Files.createTempFile("rollout", ".jsonl").toFile()
        file.writeText(content.trimIndent())
        file.deleteOnExit()
        return file
    }

    @Test
    fun readsRealUsageAndTracksTheCurrentModel() {
        val file = rollout(
            """
            {"timestamp":"2026-01-01T10:00:00Z","type":"turn_context","payload":{"turnId":"t1","model":"gpt-5"}}
            {"timestamp":"2026-01-01T10:00:01Z","type":"event_msg","payload":{"type":"token_usage","inputTokens":100,"outputTokens":50,"totalTokens":150}}
            {"timestamp":"2026-01-01T11:00:00Z","type":"turn_context","payload":{"turnId":"t2","model":"claude-x"}}
            {"timestamp":"2026-01-01T11:00:01Z","type":"event_msg","payload":{"type":"token_usage","inputTokens":20,"outputTokens":5}}
            """,
        )

        val events = RolloutUsageReader.read(file)

        assertEquals(2, events.size)
        assertEquals("gpt-5", events[0].model)
        assertEquals(150L, events[0].tokens)
        assertEquals("claude-x", events[1].model)
        assertEquals(25L, events[1].tokens)
    }

    @Test
    fun ignoresTasksWithoutTokenUsageAndBrokenLines() {
        val file = rollout(
            """
            not json at all
            {"timestamp":"2026-01-01T10:00:00Z","type":"turn_context","payload":{"model":"gpt-5"}}
            {"timestamp":"2026-01-01T10:00:01Z","type":"event_msg","payload":{"type":"task_completed"}}
            {"timestamp":"2026-01-01T10:00:02Z","type":"event_msg","payload":{"type":"token_usage","inputTokens":0,"outputTokens":0}}
            """,
        )

        assertTrue(RolloutUsageReader.read(file).isEmpty())
    }

    @Test
    fun missingFileIsEmpty() {
        assertTrue(RolloutUsageReader.read(File("/nonexistent/rollout.jsonl")).isEmpty())
    }
}
