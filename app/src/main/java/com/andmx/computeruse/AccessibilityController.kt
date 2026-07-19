package com.andmx.computeruse

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * Facade over the screen-operation stack. It validates that the
 * [AndmxAccessibilityService] is enabled and that coordinates are in-bounds,
 * then delegates to the live [AndmxAccessibilityService.instance].
 *
 * Coordinates arrive in *real screen pixels* — the [ComputerUseTool] is
 * responsible for scaling model coordinates (relative to the screenshot) back
 * up via [ScreenCaptor.scaleFactor] before calling here.
 *
 * Safety: this is the gate that enforces the "do not automate the host app"
 * constraint (Codex Computer Use parity) — it refuses to act when the
 * foreground window is AndMX itself.
 */
class AccessibilityController(private val context: Context) {

    /** True if the user has enabled our service in system Accessibility settings. */
    fun isServiceEnabled(): Boolean {
        val expected = context.packageName + "/" + AndmxAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** True if the service is connected and ready to dispatch gestures. */
    val isReady: Boolean get() = AndmxAccessibilityService.isConnected

    suspend fun tap(x: Int, y: Int): Result<Unit> {
        val service = AndmxAccessibilityService.instance ?: return notConnected()
        if (!inBounds(x, y)) return Result.failure(IllegalArgumentException("坐标越界: ($x,$y)"))
        val ok = service.tap(x.toFloat(), y.toFloat())
        return if (ok) Result.success(Unit) else Result.failure(RuntimeException("点击被取消"))
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 250L): Result<Unit> {
        val service = AndmxAccessibilityService.instance ?: return notConnected()
        val ok = service.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
        return if (ok) Result.success(Unit) else Result.failure(RuntimeException("滑动被取消"))
    }

    fun typeText(text: String): Result<Unit> {
        val service = AndmxAccessibilityService.instance ?: return notConnected()
        val ok = service.typeText(text)
        return if (ok) Result.success(Unit) else Result.failure(RuntimeException("找不到可输入的焦点"))
    }

    fun sendKey(key: String): Result<Unit> {
        val service = AndmxAccessibilityService.instance ?: return notConnected()
        val ok = service.sendKey(key)
        return if (ok) Result.success(Unit) else Result.failure(RuntimeException("按键失败: $key"))
    }

    private fun inBounds(x: Int, y: Int): Boolean {
        val (w, h) = MediaProjectionManagerHolder.realScreenSize(context)
        return x in 0..w && y in 0..h
    }

    private fun notConnected(): Result<Unit> =
        Result.failure(IllegalStateException("无障碍服务未连接,请到系统设置 → 无障碍中开启 AndMX 服务"))

    fun dumpUiTree(maxNodes: Int = 220): Result<String> {
        val service = AndmxAccessibilityService.instance ?: return notConnectedResult()
        return Result.success(service.dumpUiTree(maxNodes))
    }

    fun resolveUi(query: String): Result<String> {
        val service = AndmxAccessibilityService.instance ?: return notConnectedResult()
        val hits = service.resolveUi(query)
        if (hits.isEmpty()) return Result.success("(no match for $query)")
        val text = hits.joinToString("\n") { h ->
            "x=${h.cx} y=${h.cy} bounds=${h.bounds} clickable=${h.clickable} cls=${h.cls} text=\"${h.text}\" desc=\"${h.desc}\" id=${h.id}"
        }
        return Result.success(text)
    }

    private fun <T> notConnectedResult(): Result<T> =
        Result.failure(IllegalStateException("无障碍服务未连接,请到系统设置 → 无障碍中开启 AndMX 服务"))

}
