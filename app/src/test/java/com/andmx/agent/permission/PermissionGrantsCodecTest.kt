package com.andmx.agent.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGrantsCodecTest {

    @Test
    fun roundTripsAllowAndDenyGrants() {
        val grants = listOf(
            PermissionService.SessionGrant("Bash", "rm -rf build", allowed = false),
            PermissionService.SessionGrant("Edit", null, allowed = true),
        )

        assertEquals(grants, PermissionGrantsCodec.decode(PermissionGrantsCodec.encode(grants)))
    }

    @Test
    fun blankAndCorruptPayloadsFailClosed() {
        assertTrue(PermissionGrantsCodec.decode("").isEmpty())
        assertTrue(PermissionGrantsCodec.decode("{not-json").isEmpty())
    }
}
