package com.andmx.agent.hooks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HookTrustStoreTest {

    @Before
    fun setUp() {
        HookTrustStore.install(ApplicationProvider.getApplicationContext<Context>())
        HookTrustStore.clearAll()
    }

    @Test
    fun trustIsPinnedToExactFileContents() {
        val original = """[{"event":"Stop","command":"echo old"}]"""
        val edited = """[{"event":"Stop","command":"echo new"}]"""
        HookTrustStore.trust("/work/repo", HookTrustStore.fingerprint(original))

        assertTrue(HookTrustStore.isTrusted("/work/repo", HookTrustStore.fingerprint(original)))
        assertFalse(HookTrustStore.isTrusted("/work/repo", HookTrustStore.fingerprint(edited)))
    }

    @Test
    fun workspacesDoNotShareTrust() {
        val fingerprint = HookTrustStore.fingerprint("[]")
        HookTrustStore.trust("/work/one", fingerprint)

        assertTrue(HookTrustStore.isTrusted("/work/one", fingerprint))
        assertFalse(HookTrustStore.isTrusted("/work/two", fingerprint))
        assertNull(HookTrustStore.trustedHash("/work/two"))
    }

    @Test
    fun revokeDropsTheGrantAndBumpsRevision() {
        val fingerprint = HookTrustStore.fingerprint("[]")
        HookTrustStore.trust("/work/repo", fingerprint)
        val before = HookTrustStore.revision.value

        HookTrustStore.revoke("/work/repo")

        assertFalse(HookTrustStore.isTrusted("/work/repo", fingerprint))
        assertTrue(HookTrustStore.revision.value > before)
    }

    @Test
    fun blankInputsNeverBecomeTrusted() {
        HookTrustStore.trust("", "abc")
        HookTrustStore.trust("/work/repo", "")

        assertNull(HookTrustStore.trustedHash("/work/repo"))
        assertEquals(false, HookTrustStore.isTrusted("/work/repo", ""))
    }
}
