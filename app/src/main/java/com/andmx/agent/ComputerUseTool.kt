package com.andmx.agent

import android.content.Context
import com.andmx.computeruse.AccessibilityController
import com.andmx.computeruse.ScreenCaptor
import com.andmx.computeruse.ScreenCaptureService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Computer Use tool — pure-visual screen operation, aligned with Anthropic's
 * `computer_20250124` / Codex.app's Computer Use action set.
 *
 * The agent drives a screenshot → action → screenshot loop:
 *  1. `screenshot` captures the screen and returns it as an image data-url the
 *     model can *see* (coordinates in the returned image are what the model uses).
 *  2. `click`/`type`/`scroll`/`swipe`/`key` act on the screen. Coordinates arrive
 *     relative to the screenshot, so they're scaled back up to real screen
 *     pixels via [ScreenCaptor.scaleFactor] before being dispatched.
 *  3. After each non-screenshot action, a fresh screenshot is captured and
 *     returned so the model can verify the effect — closing the loop.
 *
 * Requires two Android grants the user must enable explicitly:
 *  - MediaProjection (for screenshot) — see [ScreenCaptor]
 *  - AccessibilityService (for tap/swipe/type) — see [AccessibilityController]
 *
 * Safety (Codex Computer Use parity): risk = EXECUTE, so every call flows
 * through the approval gate; AndMX never auto-runs screen actions silently.
 */
class ComputerUseTool(context: Context) : Tool {
    override val name = "computer"
    override val description =
        "操作设备屏幕(Computer Use)。纯视觉:先 screenshot 看清屏幕,再用坐标执行 click/type/scroll/swipe/key," +
            "每次操作后自动返回新截图供你确认效果。坐标基于你上次看到的截图分辨率。" +
            "需要用户已授权屏幕录制和无障碍权限。"
    override val risk = ToolRisk.EXECUTE

    private val screenCaptor = ScreenCaptor(context)
    private val controller = AccessibilityController(context)
    private val appContext = context.applicationContext

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "要执行的动作")
                putJsonArray("enum") {
                    add("screenshot"); add("click"); add("type"); add("scroll")
                    add("key"); add("wait"); add("swipe")
                }
            }
            putJsonObject("coordinate") {
                put("type", "array")
                put("description", "[x, y] 坐标,基于你上次看到的截图。用于 click/scroll/swipe 起点等")
                putJsonArray("items") { addJsonObject { put("type", "integer") } }
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "(type) 要输入的文本")
            }
            putJsonObject("key") {
                put("type", "string")
                put("description", "(key) 按键: back/home/recents/lock_screen 等")
            }
            putJsonObject("scroll_direction") {
                put("type", "string")
                put("description", "(scroll) 方向")
                putJsonArray("enum") { add("up"); add("down"); add("left"); add("right") }
            }
            putJsonObject("duration") {
                put("type", "integer")
                put("description", "(wait) 等待毫秒数,默认 1000")
            }
        }
        putJsonArray("required") { add("action") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content?.lowercase()
            ?: return ToolResult("缺少参数 action", isError = true)

        return when (action) {
            "screenshot" -> doScreenshot()
            "click" -> doClick(args)
            "type" -> doType(args)
            "scroll" -> doScroll(args)
            "swipe" -> doSwipe(args)
            "key" -> doKey(args)
            "wait" -> doWait(args)
            else -> ToolResult("未知 action: $action", isError = true)
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun doScreenshot(): ToolResult {
        if (!ensureCaptureStarted()) {
            return unavailable("屏幕录制未授权")
        }
        val url = screenCaptor.capture()
            ?: return ToolResult("截屏失败(可能尚未授权或服务未就绪)", isError = true)
        return ToolResult(
            output = "已截屏 (${screenCaptor.snapshotSize()})。坐标基于此截图分辨率。",
            imageUrls = listOf(url),
        )
    }

    private suspend fun doClick(args: JsonObject): ToolResult {
        val (sx, sy) = readCoordinate(args) ?: return ToolResult("缺少/无效 coordinate 参数", isError = true)
        val (rx, ry) = scaleToScreen(sx, sy)
        val r = controller.tap(rx, ry)
        return withPostScreenshot(r, "click", "[$sx,$sy]→[$rx,$ry]")
    }

    private fun doType(args: JsonObject): ToolResult {
        val text = args["text"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少 text 参数", isError = true)
        val r = controller.typeText(text)
        val base = if (r.isSuccess) "已输入文本: '${text.take(60)}'" else "输入失败: ${r.exceptionOrNull()?.message}"
        if (r.isFailure) return ToolResult(base, isError = true)
        // type doesn't move the screen much, but return a screenshot so the
        // model can verify what was entered.
        val url = if (screenCaptor.isActive) screenCaptor.capture() else null
        return if (url != null) ToolResult(base, imageUrls = listOf(url)) else ToolResult(base)
    }

    private suspend fun doScroll(args: JsonObject): ToolResult {
        val (sx, sy) = readCoordinate(args) ?: intArrayOf(screenCaptor.snapshotWidth() / 2, screenCaptor.snapshotHeight() / 2).let { it[0] to it[1] }
        val dir = args["scroll_direction"]?.jsonPrimitive?.content?.lowercase() ?: "down"
        val (rx, ry) = scaleToScreen(sx, sy)
        val (ex, ey) = scrollEndpoint(rx, ry, dir)
        val r = controller.swipe(rx, ry, ex, ey, durationMs = 300L)
        return withPostScreenshot(r, "scroll", dir)
    }

    private suspend fun doSwipe(args: JsonObject): ToolResult {
        val coord = args["coordinate"]?.jsonArray
        // swipe expects [x1,y1,x2,y2]
        if (coord == null || coord.size < 4) return ToolResult("swipe 需要 coordinate=[x1,y1,x2,y2]", isError = true)
        val (sx1, sy1) = scaleToScreen(coord[0].jsonPrimitive.intOrNull ?: 0, coord[1].jsonPrimitive.intOrNull ?: 0)
        val (sx2, sy2) = scaleToScreen(coord[2].jsonPrimitive.intOrNull ?: 0, coord[3].jsonPrimitive.intOrNull ?: 0)
        val r = controller.swipe(sx1, sy1, sx2, sy2)
        return withPostScreenshot(r, "swipe", "[$sx1,$sy1]→[$sx2,$sy2]")
    }

    private fun doKey(args: JsonObject): ToolResult {
        val key = args["key"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少 key 参数", isError = true)
        val r = controller.sendKey(key)
        return ToolResult(if (r.isSuccess) "已发送按键: $key" else "按键失败: ${r.exceptionOrNull()?.message}", isError = r.isFailure)
    }

    private fun doWait(args: JsonObject): ToolResult {
        val ms = args["duration"]?.jsonPrimitive?.intOrNull ?: 1000
        Thread.sleep(ms.coerceIn(100, 10_000).toLong())
        return ToolResult("已等待 ${ms}ms")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun ensureCaptureStarted(): Boolean {
        return if (screenCaptor.isActive) true
        else {
            ScreenCaptureService.start(appContext)
            screenCaptor.ensureStarted()
        }
    }

    /** Read [x,y] from args.coordinate. */
    private fun readCoordinate(args: JsonObject): Pair<Int, Int>? {
        val arr = args["coordinate"]?.jsonArray ?: return null
        if (arr.size < 2) return null
        val x = arr[0].jsonPrimitive.intOrNull ?: return null
        val y = arr[1].jsonPrimitive.intOrNull ?: return null
        return x to y
    }

    /** Scale a model-coordinate (relative to screenshot) to a real screen coordinate. */
    private fun scaleToScreen(x: Int, y: Int): Pair<Int, Int> {
        val f = screenCaptor.scaleFactor
        return ((x * f).toInt()) to ((y * f).toInt())
    }

    private fun scrollEndpoint(x: Int, y: Int, dir: String): Pair<Int, Int> = when (dir) {
        "up" -> x to (y - 400)
        "down" -> x to (y + 400)
        "left" -> (x - 400) to y
        "right" -> (x + 400) to y
        else -> x to (y + 400)
    }

    /**
     * After a successful screen action, capture a fresh screenshot so the model
     * can verify the effect — this is what closes the computer-use loop. If
     * capture isn't available (no grant), we still report the action result.
     */
    private fun withPostScreenshot(result: Result<Unit>, action: String, detail: String): ToolResult {
        val base = if (result.isSuccess) "已执行 $action ($detail)" else "$action 失败: ${result.exceptionOrNull()?.message}"
        if (!result.isSuccess) return ToolResult(base, isError = true)
        // Best-effort post-screenshot; don't fail the action if capture isn't ready.
        val url = if (screenCaptor.isActive) screenCaptor.capture() else null
        return if (url != null) ToolResult(output = base, imageUrls = listOf(url))
        else ToolResult(base)
    }

    private fun unavailable(reason: String): ToolResult =
        ToolResult("$reason。请用户到设置中授予屏幕录制权限后再试。", isError = true)
}

/** Small accessors so the tool can describe screenshots to the model. */
private fun ScreenCaptor.snapshotSize(): String {
    val (w, h) = snapshotDims()
    return "${w}x${h}"
}
private fun ScreenCaptor.snapshotWidth(): Int = snapshotDims().first
private fun ScreenCaptor.snapshotHeight(): Int = snapshotDims().second
