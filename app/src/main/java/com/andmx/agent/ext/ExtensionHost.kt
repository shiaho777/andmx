package com.andmx.agent.ext

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import com.andmx.agent.plugins.PluginSystem
import com.andmx.workspace.GuestPaths
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class ExtensionState { STARTING, ACTIVE, DEGRADED, DISPOSED }

data class ExtensionStatus(
    val pluginId: String,
    val state: ExtensionState,
    val detail: String = "",
)

class ExtensionInstance internal constructor(
    val pluginId: String,
    internal val webView: WebView?,
    internal val bridge: ExtensionBridge,
    private val onDestroy: () -> Unit,
) {
    @Volatile var state: ExtensionState = ExtensionState.STARTING
        internal set

    @Volatile var degradedReason: String? = null
        internal set

    val status: ExtensionStatus
        get() = ExtensionStatus(pluginId, state, degradedReason.orEmpty())

    internal fun destroy() {
        state = ExtensionState.DISPOSED
        onDestroy()
        val view = webView ?: return
        view.post { runCatching { view.destroy() } }
    }
}

object ExtensionHost {
    const val STARTUP_BUDGET_MS = 15_000L
    const val MAX_ACTIVE_INSTANCES = 4
    const val ENTRY_ASSET = "assets/index.html"

    private const val TAG = "ExtensionHost"
    private const val PROBE_JS = "typeof window.__andmxExt"
    private const val PROBE_INTERVAL_MS = 100L

    @Volatile private var appContext: Context? = null
    @Volatile private var scope: CoroutineScope? = null

    internal var startupBudgetMs: Long = STARTUP_BUDGET_MS
    internal var probeOverride: (suspend (WebView) -> Boolean)? = null

    private val lock = Mutex()
    private val instances = LinkedHashMap<String, ExtensionInstance>()

    fun install(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    appContext = context.applicationContext
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
            }
        }
    }

    fun isInstalled(): Boolean = appContext != null

    suspend fun acquire(pluginId: String): ExtensionInstance {
        val context = requireNotNull(appContext) { "ExtensionHost.install() has not been called" }
        return lock.withLock {
            instances[pluginId]?.let { existing ->
                instances.remove(pluginId)
                instances[pluginId] = existing
                return@withLock existing
            }
            val instance = createInstance(context, pluginId)
            instances[pluginId] = instance
            evictIfNeeded()
            startInstance(context, instance)
            instance
        }
    }

    suspend fun acquireForSlot(pluginId: String, slotId: String): ExtensionInstance? {
        if (!isInstalled()) return null
        if (slotId !in ExtensionSlotRegistry.KNOWN_SLOTS) return null
        if (ExtensionSlotRegistry.forSlot(slotId).none { it.pluginId == pluginId }) return null
        val instance = runCatching { acquire(pluginId) }.getOrNull() ?: return null
        if (instance.webView == null) return null
        if (instance.state == ExtensionState.DEGRADED || instance.state == ExtensionState.DISPOSED) return null
        return instance
    }

    fun attachWebView(instance: ExtensionInstance, container: ViewGroup): Boolean {
        val view = instance.webView ?: return false
        if (instance.state == ExtensionState.DISPOSED) return false
        if (view.parent === container) return true
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return true
    }

    fun detachWebView(instance: ExtensionInstance, container: ViewGroup? = null) {
        val view = instance.webView ?: return
        val parent = view.parent as? ViewGroup ?: return
        if (container == null || parent === container) parent.removeView(view)
    }

    suspend fun statusOf(pluginId: String): ExtensionStatus? =
        lock.withLock { instances[pluginId]?.status }

    suspend fun activeCount(): Int = lock.withLock { instances.size }

    suspend fun dispose(pluginId: String) {
        lock.withLock { removeLocked(pluginId) }
        ExtensionSlotRegistry.unregisterPlugin(pluginId)
    }

    suspend fun disposeAll() {
        val ids = lock.withLock {
            val keys = instances.keys.toList()
            keys.forEach { removeLocked(it) }
            keys
        }
        ids.forEach { ExtensionSlotRegistry.unregisterPlugin(it) }
    }

    private fun removeLocked(pluginId: String) {
        instances.remove(pluginId)?.destroy()
    }

    private fun evictIfNeeded() {
        while (instances.size > MAX_ACTIVE_INSTANCES) {
            val oldest = instances.keys.first()
            Log.i(TAG, "evicting least recently used extension instance: $oldest")
            removeLocked(oldest)
            ExtensionSlotRegistry.unregisterPlugin(oldest)
        }
    }

    private suspend fun createInstance(context: Context, pluginId: String): ExtensionInstance {
        val secrets = ExtensionSecrets.create(context)
        val storage = ExtensionStorage.create(context)
        lateinit var instance: ExtensionInstance
        val bridge = ExtensionBridge(
            pluginId = pluginId,
            secrets = secrets,
            storage = storage,
            onReady = {
                if (instance.state == ExtensionState.STARTING) {
                    instance.state = ExtensionState.ACTIVE
                }
            },
        )
        val sandbox = ExtensionSandbox(context, requireNotNull(scope))
        val view = withContext(Dispatchers.Main.immediate) { sandbox.create(bridge) }
        instance = ExtensionInstance(pluginId, view, bridge) {
            view?.post { runCatching { view.removeJavascriptInterface(ExtensionSandbox.BRIDGE_NAME) } }
        }
        return instance
    }

    private suspend fun startInstance(context: Context, instance: ExtensionInstance) {
        val view = instance.webView
        if (view == null) {
            degrade(instance, "WebView is unavailable on this device")
            return
        }
        val html = loadEntryHtml(context, instance.pluginId)
        if (html == null) {
            degrade(instance, "missing plugin entry asset: $ENTRY_ASSET")
            return
        }
        if (html.toByteArray(Charsets.UTF_8).size > ExtensionSandbox.MAX_HTML_BYTES) {
            degrade(instance, "plugin entry asset exceeds 1MB limit")
            return
        }
        val sandbox = ExtensionSandbox(context, requireNotNull(scope))
        withContext(Dispatchers.Main.immediate) {
            sandbox.loadPluginHtml(view, html)
        }
        val budget = startupBudgetMs
        val probe = probeOverride ?: ::probeUntilAlive
        val outcome = withTimeoutOrNull(budget) { probe(view) }
        when {
            outcome == true -> if (instance.state == ExtensionState.STARTING) {
                instance.state = ExtensionState.ACTIVE
            }
            instance.state != ExtensionState.ACTIVE ->
                degrade(instance, "extension handshake timed out after ${budget}ms")
            else -> Unit
        }
    }

    private fun degrade(instance: ExtensionInstance, reason: String) {
        instance.state = ExtensionState.DEGRADED
        instance.degradedReason = reason
        Log.w(TAG, "extension ${instance.pluginId} degraded: $reason")
    }

    private suspend fun probeUntilAlive(view: WebView): Boolean {
        while (true) {
            val probe = evaluateJs(view, PROBE_JS)
            if (probe == "object" || probe == "function") return true
            delay(PROBE_INTERVAL_MS)
        }
    }

    internal suspend fun evaluateJs(view: WebView, script: String): String? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    view.evaluateJavascript(script) { raw ->
                        if (cont.isActive) cont.resume(unquote(raw))
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    private fun unquote(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrElse { raw }
    }

    fun pluginDirGuest(pluginId: String): String = "${PluginSystem.PLUGINS_DIR}/$pluginId"

    fun loadEntryHtml(context: Context, pluginId: String): String? =
        loadAssetHtml(context, pluginId, ENTRY_ASSET)

    fun loadAssetHtml(context: Context, pluginId: String, asset: String): String? {
        if (!ExtensionSlotRegistry.isSafeAssetPath(asset)) return null
        return runCatching {
            val rootfs = File(context.applicationContext.filesDir, "rootfs")
            val file = GuestPaths.resolve(rootfs, "${pluginDirGuest(pluginId)}/$asset")
            if (!file.isFile || file.length() > ExtensionSandbox.MAX_HTML_BYTES) return null
            file.readText()
        }.getOrNull()
    }

    internal fun resetForTest() {
        scope?.cancel()
        scope = null
        appContext = null
        instances.clear()
        startupBudgetMs = STARTUP_BUDGET_MS
        probeOverride = null
    }
}
