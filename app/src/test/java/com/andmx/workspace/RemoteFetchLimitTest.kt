package com.andmx.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFetchLimitTest {

    private val max = WorkspaceAccess.REMOTE_FETCH_MAX_BYTES

    @Test
    fun defaultLimitIsFiftyMegabytes() {
        assertEquals(50 * 1024 * 1024, max)
    }

    @Test
    fun unknownSizeIsAllowedAndCheckedAfterTransfer() {
        assertNull(WorkspaceAccess.checkRemoteFetchSize(null, max))
    }

    @Test
    fun sizeExactlyAtLimitIsAllowed() {
        assertNull(WorkspaceAccess.checkRemoteFetchSize(max.toLong(), max))
    }

    @Test
    fun smallFileIsAllowed() {
        assertNull(WorkspaceAccess.checkRemoteFetchSize(1024L, max))
        assertNull(WorkspaceAccess.checkRemoteFetchSize(0L, max))
    }

    @Test
    fun oneByteOverLimitIsRejectedWithClearMessage() {
        val error = WorkspaceAccess.checkRemoteFetchSize(max.toLong() + 1, max)
        assertNotNull(error)
        assertTrue(error!!.contains("50MB"))
        assertTrue(error.contains((max.toLong() + 1).toString()))
    }

    @Test
    fun customLimitIsHonoured() {
        assertNull(WorkspaceAccess.checkRemoteFetchSize(100L, 100))
        assertNotNull(WorkspaceAccess.checkRemoteFetchSize(101L, 100))
    }
}
