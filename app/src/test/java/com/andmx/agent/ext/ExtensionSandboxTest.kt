package com.andmx.agent.ext

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExtensionSandboxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private lateinit var bridge: ExtensionBridge

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("ext_sandbox_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        bridge = ExtensionBridge("sandbox-plugin", ExtensionSecrets(prefs), ExtensionStorage(prefs))
    }

    @Test
    fun cspStringMatchesPolicy() {
        assertEquals(
            "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "connect-src 'none'; img-src data:; frame-ancestors 'none'",
            ExtensionSandbox.CSP,
        )
        assertTrue(ExtensionSandbox.CSP.contains("default-src 'none'"))
        assertTrue(ExtensionSandbox.CSP.contains("connect-src 'none'"))
        assertTrue(ExtensionSandbox.CSP.contains("frame-ancestors 'none'"))
    }

    @Test
    fun cspMetaIsInjectedIntoHead() {
        val html = "<html><head><title>x</title></head><body>hi</body></html>"
        val wrapped = ExtensionSandbox.wrapWithCsp(html)
        val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"${ExtensionSandbox.CSP}\">"
        assertTrue(wrapped.contains(meta))
        assertTrue(wrapped.indexOf(meta) > wrapped.indexOf("<head>"))
        assertTrue(wrapped.indexOf(meta) < wrapped.indexOf("<title>"))
        assertTrue(wrapped.endsWith("</html>"))
    }

    @Test
    fun cspMetaIsPrependedWhenHeadMissing() {
        val wrapped = ExtensionSandbox.wrapWithCsp("<div>no head</div>")
        assertTrue(wrapped.startsWith("<meta http-equiv=\"Content-Security-Policy\""))
        assertTrue(wrapped.endsWith("<div>no head</div>"))
    }

    @Test
    fun allRequestsAreBlockedByDefault() {
        val empty = emptySet<String>()
        assertTrue(ExtensionSandbox.shouldBlock("https://evil.example/payload.js", empty))
        assertTrue(ExtensionSandbox.shouldBlock("http://evil.example/", empty))
        assertTrue(ExtensionSandbox.shouldBlock("file:///etc/passwd", empty))
        assertTrue(ExtensionSandbox.shouldBlock("content://contacts/people", empty))
        assertTrue(ExtensionSandbox.shouldBlock("ws://evil.example/socket", empty))
        assertTrue(ExtensionSandbox.shouldBlock("", empty))
    }

    @Test
    fun whitelistIsReservedButExactMatchOnly() {
        val allowed = setOf("https://trusted.example/api")
        assertFalse(ExtensionSandbox.shouldBlock("https://trusted.example/api", allowed))
        assertTrue(ExtensionSandbox.shouldBlock("https://trusted.example/api/extra", allowed))
        assertTrue(ExtensionSandbox.shouldBlock("https://evil.example/", allowed))
    }

    @Test
    fun emptyResponseHasNoBody() {
        val response = ExtensionSandbox.emptyResponse()
        assertEquals("text/plain", response.mimeType)
        assertEquals("utf-8", response.encoding)
        assertEquals(-1, response.data.read())
    }

    @Test
    fun webViewFactoryAppliesHardenedSettings() {
        val sandbox = ExtensionSandbox(context, scope)
        val view = sandbox.create(bridge)
        assertNotNull(view)
        val settings = view!!.settings
        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.allowFileAccessFromFileURLs)
        assertFalse(settings.allowUniversalAccessFromFileURLs)
        assertTrue(settings.blockNetworkLoads)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.databaseEnabled)
        view.destroy()
    }
}
