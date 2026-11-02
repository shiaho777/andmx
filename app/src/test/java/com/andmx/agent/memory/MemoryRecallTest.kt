package com.andmx.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallTest {

    private fun memory(
        content: String,
        category: MemorySystem.MemoryCategory = MemorySystem.MemoryCategory.USER_PREFERENCE,
        timestamp: String = "2026-01-01T00:00:00Z",
    ) = MemorySystem.ParsedMemory(
        category = category,
        timestamp = timestamp,
        sessionId = "1",
        content = content,
    )

    @Test
    fun ranksErrorMemoriesForErrorQueries() {
        val memories = listOf(
            memory("用户偏好: 使用 pnpm", MemorySystem.MemoryCategory.USER_PREFERENCE),
            memory(
                "故障: bash: gradle: command not found，改用 ./gradlew",
                MemorySystem.MemoryCategory.FAILURE_SHIELD,
            ),
            memory("仓库入口: /root/project/app/build.gradle", MemorySystem.MemoryCategory.REPO_MAP),
        )

        val hits = MemoryRecall.rank("gradle 报错怎么修复", memories)

        assertEquals(MemorySystem.MemoryCategory.FAILURE_SHIELD, hits.first().category)
        assertTrue(hits.first().content.contains("gradle"))
    }

    @Test
    fun supportsChineseBigramsWithoutSpaces() {
        val memories = listOf(
            memory("仓库入口: 配置在 .zcode/settings.json", MemorySystem.MemoryCategory.REPO_MAP),
            memory("用户偏好: 回答尽量简短", MemorySystem.MemoryCategory.USER_PREFERENCE),
        )

        val hits = MemoryRecall.rank("配置文件在哪里", memories)

        assertEquals(1, hits.size)
        assertEquals(MemorySystem.MemoryCategory.REPO_MAP, hits.single().category)
    }

    @Test
    fun unrelatedQueryReturnsNothing() {
        val memories = listOf(memory("用户偏好: 使用 pnpm"))

        assertTrue(MemoryRecall.rank("docker kubernetes", memories).isEmpty())
        assertTrue(MemoryRecall.rank("", memories).isEmpty())
    }

    @Test
    fun limitCapsTheResultSet() {
        val memories = (1..10).map { memory("部署步骤 $it") }

        assertEquals(3, MemoryRecall.rank("部署", memories, limit = 3).size)
    }

    @Test
    fun cjkStopCharsAloneDoNotMatchEverything() {
        val memories = listOf(memory("用户偏好: 使用 pnpm"))

        assertTrue(MemoryRecall.rank("的 了 是", memories).isEmpty())
    }
}
