package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolArgsTest {
    @Test
    fun readsJsonStringValue() {
        val args = """{"path":"/root/app/src/Main.kt","content":"hello"}"""

        assertEquals("/root/app/src/Main.kt", ToolArgs.filePath("read_file", args))
    }

    @Test
    fun fallbackParserHandlesEscapedQuotes() {
        val args = """"path":"/root/quoted-\"name\".kt""""

        assertEquals("/root/quoted-\"name\".kt", ToolArgs.value(args, "path"))
    }

    @Test
    fun webSearchBuildsDuckDuckGoUrl() {
        val args = """{"query":"codex android ui"}"""

        assertEquals("https://duckduckgo.com/?q=codex+android+ui", ToolArgs.webUrl("web_search", args))
    }
}
