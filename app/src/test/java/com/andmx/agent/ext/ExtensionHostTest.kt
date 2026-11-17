package com.andmx.agent.ext

import android.app.Application
import android.content.Context
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
}
