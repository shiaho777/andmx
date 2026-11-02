package com.andmx.agent.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookConfigParserTest {

    @Test
    fun parsesArrayWithProjectSourceAndCwd() {
        val raw = """
            [
              {
                "event": "PreToolUse",
                "command": "./scripts/check.sh",
                "matcher": "Bash|Write",
                "name": "guard",
                "timeout_ms": 2500,
                "async": true
              }
            ]
        """.trimIndent()

        val hooks = HookConfigParser.parse(raw, HookSystem.HookSource.PROJECT, cwd = "/root/project")

        val hook = hooks.single()
        assertEquals(HookSystem.HookEvent.PRE_TOOL_USE, hook.event)
        assertEquals(HookSystem.HookSource.PROJECT, hook.source)
        assertEquals("/root/project", hook.cwd)
        assertEquals(2_500L, hook.timeoutMs)
        assertEquals(HookSystem.HookMode.ASYNC, hook.mode)
        assertEquals("guard", hook.name)
        assertEquals("Bash|Write", hook.matcher)
    }

    @Test
    fun acceptsHooksObjectAndSecondsTimeout() {
        val raw = """
            {"hooks":[{"event":"stop","command":"echo done","timeout":3}]}
        """.trimIndent()

        val hook = HookConfigParser.parse(raw, HookSystem.HookSource.USER).single()

        assertEquals(HookSystem.HookEvent.STOP, hook.event)
        assertEquals(3_000L, hook.timeoutMs)
        assertEquals(HookSystem.HookMode.SYNC, hook.mode)
    }

    @Test
    fun dropsEntriesWithoutCommandOrKnownEvent() {
        val raw = """
            [
              {"event":"PreToolUse"},
              {"event":"notARealEvent","command":"echo x"},
              {"event":"Stop","command":"echo ok"}
            ]
        """.trimIndent()

        val hooks = HookConfigParser.parse(raw, HookSystem.HookSource.USER)

        assertEquals(1, hooks.size)
        assertEquals(HookSystem.HookEvent.STOP, hooks.single().event)
    }

    @Test
    fun malformedPayloadIsEmptyInsteadOfThrowing() {
        assertTrue(HookConfigParser.parse("{not json", HookSystem.HookSource.USER).isEmpty())
        assertTrue(HookConfigParser.parse("", HookSystem.HookSource.USER).isEmpty())
        assertTrue(HookConfigParser.parse("{}", HookSystem.HookSource.USER).isEmpty())
    }

    @Test
    fun timeoutIsClampedToAReasonableRange() {
        val raw = """
            [
              {"event":"Stop","command":"a","timeout":"0"},
              {"event":"Stop","command":"b","timeout_ms":"999999999"}
            ]
        """.trimIndent()

        val hooks = HookConfigParser.parse(raw, HookSystem.HookSource.USER)

        assertEquals(1_000L, hooks[0].timeoutMs)
        assertEquals(600_000L, hooks[1].timeoutMs)
    }

    @Test
    fun fingerprintChangesWithContent() {
        val a = HookTrustStore.fingerprint("[]")
        val b = HookTrustStore.fingerprint("[{\"event\":\"Stop\"}]")

        assertEquals(64, a.length)
        assertNotEquals(a, b)
    }
}
