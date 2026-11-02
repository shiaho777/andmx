package com.andmx.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class IndexedGrepFormatTest {

    @Test
    fun contentHitsAreConvertedToDisplayPaths() {
        val result = formatIndexedGrep(
            hits = listOf("app/Main.kt:7:needle here", "app/Util.kt:2:needle too"),
            mode = "content",
            showLineNumbers = true,
            guestRoot = "/root/project",
            cwd = "/root/project",
            headLimit = 10,
            offset = 0,
        )

        assertTrue(result.output.contains("app/Main.kt:7:needle here"))
        assertTrue(result.output.contains("app/Util.kt:2:needle too"))
        assertTrue(result.output.contains("pagination = limit: 10"))
    }

    @Test
    fun filesAndCountModesKeepTheirShapes() {
        val files = formatIndexedGrep(
            hits = listOf("a.kt", "b.kt"),
            mode = "files_with_matches",
            showLineNumbers = false,
            guestRoot = "/root/project",
            cwd = "/root/project",
            headLimit = null,
            offset = 0,
        )
        assertTrue(files.output.contains("a.kt"))
        assertTrue(files.output.contains("b.kt"))

        val counts = formatIndexedGrep(
            hits = listOf("a.kt:3", "b.kt:5"),
            mode = "count",
            showLineNumbers = false,
            guestRoot = "/root/project",
            cwd = "/root/project",
            headLimit = null,
            offset = 0,
        )
        assertTrue(counts.output.contains("a.kt:3"))
        assertTrue(counts.output.contains("8 total occurrences"))
    }
}
