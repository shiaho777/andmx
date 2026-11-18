package com.andmx.agent.ext

import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExtensionHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ExtensionHost.resetForTest()
        ExtensionSlotRegistry.clear()
        ExtensionHost.install(context)
    }

    @After
    fun tearDown() = runBlocking {
        ExtensionHost.disposeAll()
        ExtensionHost.resetForTest()
        ExtensionSlotRegistry.clear()
    }

    private fun seedPlugin(id: String, withBridge: Boolean) {
        val dir = File(context.filesDir, "rootfs/root/.andmx/plugins/$id/assets")
        dir.mkdirs()
        val script = if (withBridge) {
            "<script>window.__andmxExt={postMessage:function(m){}};</script>"
        } else {
            "<script>var idle=1;</script>"
        }
        File(dir, "index.html").writeText("<html><head>$script</head><body>$id</body></html>")
    }

    @Test
    fun installRegistersContextWithoutStartingWebView() = runBlocking {
        assertTrue(ExtensionHost.isInstalled())
        assertEquals(0, ExtensionHost.activeCount())
    }

    @Test
    fun acquireIsLazyAndReusesInstance() = runBlocking {
        seedPlugin("reuse", withBridge = true)
        ExtensionHost.probeOverride = { true }
        val first = ExtensionHost.acquire("reuse")
        val second = ExtensionHost.acquire("reuse")
        assertTrue(first === second)
        assertEquals(1, ExtensionHost.activeCount())
        assertEquals(ExtensionState.ACTIVE, first.state)
    }

    @Test
    fun startupTimeoutDegradesWithoutCrashing() = runBlocking {
        seedPlugin("stalled", withBridge = false)
        ExtensionHost.startupBudgetMs = 150
        ExtensionHost.probeOverride = {
            while (true) {
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        val instance = ExtensionHost.acquire("stalled")
        assertEquals(ExtensionState.DEGRADED, instance.state)
        assertNotNull(instance.degradedReason)
        assertTrue(instance.degradedReason!!.contains("timed out"))
        assertEquals(1, ExtensionHost.activeCount())
    }

    @Test
    fun missingEntryAssetDegradesHonestly() = runBlocking {
        ExtensionHost.probeOverride = { true }
        val instance = ExtensionHost.acquire("no-assets")
        assertEquals(ExtensionState.DEGRADED, instance.state)
        assertTrue(instance.degradedReason!!.contains("entry asset"))
    }

    @Test
    fun lruEvictsOldestBeyondCap() = runBlocking {
        ExtensionHost.probeOverride = { true }
        val ids = listOf("p1", "p2", "p3", "p4", "p5")
        ids.forEach { seedPlugin(it, withBridge = true) }
        ids.forEach { ExtensionHost.acquire(it) }
        assertEquals(4, ExtensionHost.activeCount())
        assertNull(ExtensionHost.statusOf("p1"))
        ids.drop(1).forEach { assertNotNull(ExtensionHost.statusOf(it)) }
        ExtensionHost.acquire("p2")
        ExtensionHost.acquire("p6".also { seedPlugin(it, withBridge = true) })
        assertEquals(4, ExtensionHost.activeCount())
        assertNull(ExtensionHost.statusOf("p3"))
        assertNotNull(ExtensionHost.statusOf("p2"))
    }

    @Test
    fun disposeRemovesInstanceAndSlotDeclarations() = runBlocking {
        seedPlugin("disp", withBridge = true)
        ExtensionHost.probeOverride = { true }
        val instance = ExtensionHost.acquire("disp")
        ExtensionSlotRegistry.register(SlotDeclaration("disp", ExtensionSlotRegistry.NAV_PRIMARY, "t", "assets/x.html"))
        ExtensionHost.dispose("disp")
        assertEquals(0, ExtensionHost.activeCount())
        assertEquals(ExtensionState.DISPOSED, instance.state)
        assertTrue(ExtensionSlotRegistry.forPlugin("disp").isEmpty())
    }

    @Test
    fun disposeAllClearsEveryInstance() = runBlocking {
        ExtensionHost.probeOverride = { true }
        listOf("a", "b").forEach { seedPlugin(it, withBridge = true) }
        ExtensionHost.acquire("a")
        ExtensionHost.acquire("b")
        ExtensionHost.disposeAll()
        assertEquals(0, ExtensionHost.activeCount())
        assertFalse(ExtensionSlotRegistry.declarations.value.any { it.pluginId == "a" })
    }

    @Test
    fun entryHtmlIsReadFromPluginDirectoryWithTraversalGuard() {
        seedPlugin("reader", withBridge = true)
        val html = ExtensionHost.loadEntryHtml(context, "reader")
        assertNotNull(html)
        assertTrue(html!!.contains("reader"))
        assertNull(ExtensionHost.loadAssetHtml(context, "reader", "../../escape.html"))
        assertNull(ExtensionHost.loadAssetHtml(context, "reader", "/etc/passwd"))
        assertNull(ExtensionHost.loadAssetHtml(context, "reader", "file:///etc/passwd"))
    }

    @Test
    fun acquireForSlotReturnsNullWhenNotInstalled() = runBlocking {
        ExtensionHost.resetForTest()
        assertNull(ExtensionHost.acquireForSlot("slot-plugin", ExtensionSlotRegistry.NAV_PRIMARY))
    }

    @Test
    fun acquireForSlotReturnsNullForUnknownSlotOrUnregisteredPlugin() = runBlocking {
        seedPlugin("slot-plugin", withBridge = true)
        ExtensionHost.probeOverride = { true }
        assertNull(ExtensionHost.acquireForSlot("slot-plugin", "unknown.slot"))
        assertNull(ExtensionHost.acquireForSlot("slot-plugin", ExtensionSlotRegistry.NAV_PRIMARY))
        assertEquals(0, ExtensionHost.activeCount())
    }

    @Test
    fun acquireForSlotReturnsNullWhenDegraded() = runBlocking {
        ExtensionHost.probeOverride = { true }
        assertTrue(
            ExtensionSlotRegistry.register(
                SlotDeclaration("missing", ExtensionSlotRegistry.NAV_PRIMARY, "t", "assets/index.html"),
            ),
        )
        assertNull(ExtensionHost.acquireForSlot("missing", ExtensionSlotRegistry.NAV_PRIMARY))
        assertEquals(ExtensionState.DEGRADED, ExtensionHost.statusOf("missing")!!.state)
    }

    @Test
    fun acquireForSlotReturnsActiveInstanceAndReusesIt() = runBlocking {
        seedPlugin("slot-plugin", withBridge = true)
        ExtensionHost.probeOverride = { true }
        assertTrue(
            ExtensionSlotRegistry.register(
                SlotDeclaration("slot-plugin", ExtensionSlotRegistry.NAV_PRIMARY, "t", "assets/index.html"),
            ),
        )
        val first = ExtensionHost.acquireForSlot("slot-plugin", ExtensionSlotRegistry.NAV_PRIMARY)
        val second = ExtensionHost.acquireForSlot("slot-plugin", ExtensionSlotRegistry.NAV_PRIMARY)
        assertNotNull(first)
        assertTrue(first === second)
        assertEquals(ExtensionState.ACTIVE, first!!.state)
        assertNotNull(first.webView)
        assertEquals(1, ExtensionHost.activeCount())
    }

    @Test
    fun slotWebViewKeepsSandboxBoundaries() = runBlocking {
        seedPlugin("secure-slot", withBridge = true)
        ExtensionHost.probeOverride = { true }
        assertTrue(
            ExtensionSlotRegistry.register(
                SlotDeclaration("secure-slot", ExtensionSlotRegistry.NAV_PRIMARY, "t", "assets/index.html"),
            ),
        )
        val instance = ExtensionHost.acquireForSlot("secure-slot", ExtensionSlotRegistry.NAV_PRIMARY)
        assertNotNull(instance)
        val settings = instance!!.webView!!.settings
        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.allowFileAccessFromFileURLs)
        assertFalse(settings.allowUniversalAccessFromFileURLs)
        assertTrue(settings.blockNetworkLoads)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.databaseEnabled)
        assertTrue(ExtensionSandbox.shouldBlock("https://evil.example/x", emptySet()))
        assertTrue(ExtensionSandbox.shouldBlock("file:///etc/passwd", emptySet()))
        assertTrue(ExtensionSandbox.CSP.contains("default-src 'none'"))
        assertTrue(ExtensionSandbox.CSP.contains("connect-src 'none'"))
    }

    @Test
    fun attachMovesWebViewBetweenContainersWithoutDuplicateParent() = runBlocking {
        seedPlugin("attach-plugin", withBridge = true)
        ExtensionHost.probeOverride = { true }
        val instance = ExtensionHost.acquire("attach-plugin")
        val first = FrameLayout(context)
        val second = FrameLayout(context)
        assertTrue(ExtensionHost.attachWebView(instance, first))
        assertTrue(instance.webView!!.parent === first)
        assertTrue(ExtensionHost.attachWebView(instance, first))
        assertEquals(1, first.childCount)
        assertTrue(ExtensionHost.attachWebView(instance, second))
        assertTrue(instance.webView!!.parent === second)
        assertEquals(0, first.childCount)
        ExtensionHost.detachWebView(instance, second)
        assertNull(instance.webView!!.parent)
        ExtensionHost.detachWebView(instance, second)
        assertNull(instance.webView!!.parent)
    }

    @Test
    fun detachRespectsForeignContainerAndDisposedInstanceRejectsAttach() = runBlocking {
        seedPlugin("detach-plugin", withBridge = true)
        ExtensionHost.probeOverride = { true }
        val instance = ExtensionHost.acquire("detach-plugin")
        val owner = FrameLayout(context)
        val other = FrameLayout(context)
        assertTrue(ExtensionHost.attachWebView(instance, owner))
        ExtensionHost.detachWebView(instance, other)
        assertTrue(instance.webView!!.parent === owner)
        ExtensionHost.detachWebView(instance)
        assertNull(instance.webView!!.parent)
        ExtensionHost.dispose("detach-plugin")
        assertFalse(ExtensionHost.attachWebView(instance, owner))
        assertEquals(0, owner.childCount)
    }
}
