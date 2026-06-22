package com.andmx.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTest {

    @Test
    fun encodesRequestLine() {
        val line = JsonRpc.request(1, "initialize")
        assertTrue(line.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(line.contains("\"id\":1"))
        assertTrue(line.contains("\"method\":\"initialize\""))
        assertTrue("must be single line", !line.contains("\n"))
    }

    @Test
    fun parsesResponseWithId() {
        val r = JsonRpc.parseResponse("""{"jsonrpc":"2.0","id":7,"result":{"ok":true}}""")!!
        assertEquals(7, r.id)
        assertNull(r.error)
    }

    @Test
    fun parsesError() {
        val r = JsonRpc.parseResponse("""{"jsonrpc":"2.0","id":3,"error":{"code":-1,"message":"boom"}}""")!!
        assertEquals("boom", r.error)
    }

    @Test
    fun ignoresNotifications() {
        // no id => not a response we correlate
        assertNull(JsonRpc.parseResponse("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun parsesServerConfig() {
        val cfgs = McpServerConfig.parse(
            """
            # a comment
            fs|npx -y @modelcontextprotocol/server-filesystem /root
            bad line without pipe
            git|uvx mcp-server-git
            """.trimIndent(),
        )
        assertEquals(2, cfgs.size)
        assertEquals("fs", cfgs[0].name)
        assertEquals("npx -y @modelcontextprotocol/server-filesystem /root", cfgs[0].command)
        assertEquals("git", cfgs[1].name)
    }
}
