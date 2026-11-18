package com.andmx.ui2.ext

import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.ext.ExtensionHost
import com.andmx.agent.ext.ExtensionSlotRegistry
import com.andmx.agent.ext.SlotDeclaration
import java.io.File
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
class ExtensionSlotHostViewTest {
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

    private fun seedSlotPlugin(id: String) {
        val dir = File(context.filesDir, "rootfs/root/.andmx/plugins/$id/assets")
        dir.mkdirs()
        File(dir, "index.html").writeText(
            "<html><head><script>window.__andmxExt={postMessage:function(m){}};</script></head><body>$id</body></html>",
        )
        ExtensionHost.probeOverride = { true }
        assertTrue(
            ExtensionSlotRegistry.register(
                SlotDeclaration(id, ExtensionSlotRegistry.NAV_PRIMARY, id, "assets/index.html"),
            ),
        )
    }

    @Test
    fun attachHelperParentsHostWebViewIntoContainer() = runBlocking {
        seedSlotPlugin("ui-slot")
        val instance = ExtensionHost.acquireForSlot("ui-slot", ExtensionSlotRegistry.NAV_PRIMARY)
        assertNotNull(instance)
        val container = attachInstanceToContainer(instance!!, FrameLayout(context))
        assertEquals(1, container.childCount)
        assertTrue(instance.webView === container.getChildAt(0))
        assertTrue(instance.webView!!.parent === container)
        ExtensionHost.detachWebView(instance, container)
        container.removeAllViews()
        assertNull(instance.webView!!.parent)
        assertEquals(0, container.childCount)
    }

    @Test
    fun attachHelperLeavesContainerEmptyForDisposedInstance() = runBlocking {
        seedSlotPlugin("ui-disposed")
        val instance = ExtensionHost.acquireForSlot("ui-disposed", ExtensionSlotRegistry.NAV_PRIMARY)
        assertNotNull(instance)
        ExtensionHost.dispose("ui-disposed")
        val container = attachInstanceToContainer(instance!!, FrameLayout(context))
        assertEquals(0, container.childCount)
    }

    @Test
    fun fallbackPlaceholderWebViewKeepsHardenedSettings() {
        val view = createSlotWebView(context, "<html><body>fallback</body></html>")
        val settings = view.settings
        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.domStorageEnabled)
        assertTrue(settings.blockNetworkLoads)
        assertTrue(settings.blockNetworkImage)
        view.destroy()
    }
}
