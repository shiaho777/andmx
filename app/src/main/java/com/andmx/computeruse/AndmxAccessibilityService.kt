package com.andmx.computeruse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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

    /** Type text into the currently-focused editable node. Falls back to no-op. */
    fun typeText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return ok
    }

    /**
     * Send a limited key set via the global action API. Full keyboard combos
     * require root/IME; we support the navigation set (back/home/recents).
     */
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
}
