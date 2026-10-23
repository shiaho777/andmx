package com.andmx.computeruse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred

/**
 * The screen-operation backend for computer-use. Android's only no-root way to
 * touch other apps is an AccessibilityService that [dispatchGesture]s on top of
 * whatever window is foregrounded, and walks the accessibility node tree to
 * set text. We keep it pure-visual: it does not expose the node tree to the
 * model — only clicks/swipes/types/keys, mirroring the computer_20250124
 * action set.
 *
 * The system binds this service when the user enables it in Settings →
 * Accessibility. [instance] is set in [onServiceConnected] so the rest of the
 * app can reach it.
 */
class AndmxAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "AndmxA11y"

        /** Non-null once the system has connected this service. */
        @Volatile var instance: AndmxAccessibilityService? = null
            private set

        /** True when the service is bound and ready to dispatch gestures. */
        val isConnected: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected — gesture dispatch ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op: pure visual */ }
    override fun onInterrupt() { /* no-op */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    /**
     * Dispatch a tap at (x, y). Resolves true if the gesture was dispatched
     * successfully. The [durationMs] controls how long the finger is "down".
     */
    suspend fun tap(x: Float, y: Float, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    /**
     * Dispatch a swipe from (x1,y1) to (x2,y2). [durationMs] sets the swipe
     * speed (longer = slower, drag-like; shorter = flick).
     */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 250L): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    fun typeText(text: String): Boolean {
        val node = editableNode() ?: return false
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            node.recycle()
        }
        if (text.isEmpty() || editableText()?.contains(text) == true) return true
        val again = editableNode() ?: return false
        try {
            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("andmx", text))
            again.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            again.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } finally {
            again.recycle()
        }
        return text.isEmpty() || editableText()?.contains(text) == true
    }

    private fun editableText(): String? = editableNode()?.let { node ->
        try {
            node.text?.toString()
        } finally {
            node.recycle()
        }
    }

    private fun editableNode(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (focused.isEditable) return focused
            focused.recycle()
        }
        val root = rootInActiveWindow ?: return null
        val found = findEditable(root)
        root.recycle()
        return found
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findEditable(child)
            child.recycle()
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Send a limited key set via the global action API. Full keyboard combos
     * require root/IME; we support the navigation set (back/home/recents).
     */
    fun foregroundPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    fun sendKey(key: String): Boolean = when (key.lowercase()) {
        "back", "返回" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home", "主页" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents", "recent", "最近" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "notifications_clear", "清除通知" ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) else false
        "split_screen", "分屏" ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) else false
        "lock_screen", "锁屏" ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        else -> false
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        val result = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { result.complete(true) }
            override fun onCancelled(g: GestureDescription?) { result.complete(false) }
        }, null)
        return result.await()
    }

    data class UiNodeHit(
        val text: String,
        val desc: String,
        val id: String,
        val cls: String,
        val bounds: String,
        val clickable: Boolean,
        val cx: Int,
        val cy: Int,
    )

    fun dumpUiTree(maxNodes: Int = 220): String {
        val root = rootInActiveWindow ?: return "(no accessibility root)"
        val out = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            count++
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val indent = "  ".repeat(depth.coerceAtMost(12))
            val text = node.text?.toString().orEmpty().replace('\n', ' ').take(80)
            val desc = node.contentDescription?.toString().orEmpty().replace('\n', ' ').take(80)
            val id = node.viewIdResourceName.orEmpty()
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            out.append(indent)
                .append(cls.ifBlank { "?" })
                .append(" bounds=").append(rect.toShortString())
            if (text.isNotBlank()) out.append(" text=\"").append(text).append("\"")
            if (desc.isNotBlank()) out.append(" desc=\"").append(desc).append("\"")
            if (id.isNotBlank()) out.append(" id=").append(id)
            if (node.isClickable) out.append(" clickable")
            if (node.isEditable) out.append(" editable")
            out.append('\n')
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        try {
            walk(root, 0)
        } finally {
            root.recycle()
        }
        if (count >= maxNodes) out.append("...(truncated at $maxNodes nodes)\n")
        return out.toString().ifBlank { "(empty tree)" }
    }

    fun resolveUi(query: String, maxHits: Int = 20): List<UiNodeHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        val hits = mutableListOf<UiNodeHit>()
        fun walk(node: AccessibilityNodeInfo) {
            if (hits.size >= maxHits) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            val cls = node.className?.toString().orEmpty()
            val hay = listOf(text, desc, id, cls).joinToString("\n")
            if (hay.contains(q, ignoreCase = true)) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                hits += UiNodeHit(
                    text = text.take(80),
                    desc = desc.take(80),
                    id = id,
                    cls = cls.substringAfterLast('.'),
                    bounds = rect.toShortString(),
                    clickable = node.isClickable,
                    cx = rect.centerX(),
                    cy = rect.centerY(),
                )
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
            }
        }
        try {
            walk(root)
        } finally {
            root.recycle()
        }
        return hits
    }

}
