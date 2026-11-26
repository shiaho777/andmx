package com.andmx.agent.plugins

import com.andmx.ui2.theme.AndMXType
import com.andmx.ui2.theme.Motion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignTokensAssetTest {

    private fun assetFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("asset not found: $relative from ${File(".").absolutePath}")
    }

    private val tokens: JsonObject by lazy {
        Json.parseToJsonElement(
            assetFile("src/main/assets/andmx-plugins/design/runtime/tokens.json").readText(),
        ).jsonObject
    }

    private fun assertStyle(tokenName: String, style: androidx.compose.ui.text.TextStyle, weight: String) {
        val obj = tokens["type"]!!.jsonObject[tokenName]!!.jsonObject
        assertEquals(style.fontSize.value.toDouble(), obj["fontSizeSp"]!!.jsonPrimitive.double, 0.001)
        assertEquals(style.lineHeight.value.toDouble(), obj["lineHeightSp"]!!.jsonPrimitive.double, 0.001)
        assertEquals(weight, obj["weight"]!!.jsonPrimitive.content)
    }

    @Test
    fun typeTokensMatchAndMXTypeConstants() {
        assertStyle("chatBody", AndMXType.chatBody, "normal")
        assertStyle("chatBodyMuted", AndMXType.chatBodyMuted, "normal")
        assertStyle("cardTitle", AndMXType.cardTitle, "medium")
        assertStyle("cardMeta", AndMXType.cardMeta, "normal")
        assertStyle("micro", AndMXType.micro, "normal")
        assertStyle("code", AndMXType.code, "normal")
        assertStyle("stat", AndMXType.stat, "semibold")
        assertStyle("statLarge", AndMXType.statLarge, "semibold")
        assertStyle("input", AndMXType.input, "normal")
        assertEquals(9, tokens["type"]!!.jsonObject.size)
    }

    @Test
    fun motionTokensMatchMotionDefaults() {
        val motion = Motion()
        val obj = tokens["motion"]!!.jsonObject
        assertEquals(motion.fastMs, obj["fastMs"]!!.jsonPrimitive.content.toInt())
        assertEquals(motion.baseMs, obj["baseMs"]!!.jsonPrimitive.content.toInt())
        assertEquals(motion.enterMs, obj["enterMs"]!!.jsonPrimitive.content.toInt())
        assertEquals(motion.inkMs, obj["inkMs"]!!.jsonPrimitive.content.toInt())
        val ease = obj["easingCubicBezier"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(listOf(0.4, 0.0, 0.2, 1.0), ease)
        assertEquals(0.82, obj["springDampingRatio"]!!.jsonPrimitive.double, 0.001)
        assertEquals(380.0, obj["springStiffness"]!!.jsonPrimitive.double, 0.001)
    }

    @Test
    fun spacingTokensMatchChromeSource() {
        val chrome = assetFile("src/main/java/com/andmx/ui2/theme/Chrome.kt").readText()
        val spacing = tokens["spacing"]!!.jsonObject
        val radius = tokens["radius"]!!.jsonObject
        val gutter = Regex("""gutter\s*=\s*(\d+)\.dp""").find(chrome)!!.groupValues[1].toInt()
        val bubble = Regex("""bubble\s*=\s*RoundedCornerShape\((\d+)\.dp\)""").find(chrome)!!.groupValues[1].toInt()
        val sheet = Regex("""sheet\s*=\s*RoundedCornerShape\(topStart\s*=\s*(\d+)\.dp""").find(chrome)!!.groupValues[1].toInt()
        assertEquals(gutter, spacing["gutterDp"]!!.jsonPrimitive.content.toInt())
        assertEquals(bubble, radius["bubbleDp"]!!.jsonPrimitive.content.toInt())
        assertEquals(sheet, radius["sheetDp"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun spacingScaleIsFourDpGrid() {
        val spacing = tokens["spacing"]!!.jsonObject
        val scale = listOf("xsDp", "smDp", "mdDp", "lgDp", "xlDp")
        assertEquals(listOf(4, 8, 12, 16, 24), scale.map { spacing[it]!!.jsonPrimitive.content.toInt() })
        scale.forEach { assertEquals(0, spacing[it]!!.jsonPrimitive.content.toInt() % 4) }
        assertEquals(spacing["lgDp"]!!.jsonPrimitive.content, spacing["gutterDp"]!!.jsonPrimitive.content)
    }

    @Test
    fun radiusTokensMatchUiSource() {
        val radius = tokens["radius"]!!.jsonObject
        assertEquals(listOf(2, 8, 12), listOf("xsDp", "smDp", "mdDp").map { radius[it]!!.jsonPrimitive.content.toInt() })
        val markdown = assetFile("src/main/java/com/andmx/ui2/markdown/MarkdownView.kt").readText()
        val themeCard = assetFile("src/main/java/com/andmx/ui2/settings/ThemeCard.kt").readText()
        assertTrue(markdown.contains("RoundedCornerShape(${radius["xsDp"]!!.jsonPrimitive.content}.dp)"))
        assertTrue(markdown.contains("RoundedCornerShape(${radius["smDp"]!!.jsonPrimitive.content}.dp)"))
        assertTrue(themeCard.contains("RoundedCornerShape(${radius["mdDp"]!!.jsonPrimitive.content}.dp)"))
    }

    @Test
    fun shadowTokensMatchUiSource() {
        val shadow = tokens["shadow"]!!.jsonObject
        val modelPage = assetFile("src/main/java/com/andmx/ui2/settings/pages/ModelPage.kt").readText()
        val toolbar = assetFile("src/main/java/com/andmx/ui2/chat/SelectionToolbar.kt").readText()
        val drag = Regex("""if\s*\(dragging\)\s*(\d+)\.dp\s*else\s*(\d+)\.dp""").find(modelPage)!!
        assertEquals(drag.groupValues[2].toInt(), shadow["restDp"]!!.jsonPrimitive.content.toInt())
        assertEquals(drag.groupValues[1].toInt(), shadow["dragDp"]!!.jsonPrimitive.content.toInt())
        val raised = Regex("""shadowElevation\s*=\s*(\d+)\.dp""").find(toolbar)!!
        assertEquals(raised.groupValues[1].toInt(), shadow["raisedDp"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun paletteTokensMatchThemeSource() {
        val theme = assetFile("src/main/java/com/andmx/ui2/theme/Theme.kt").readText()
        val colors = tokens["colors"]!!.jsonObject
        fun assertColor(tokenHex: String, kotlinName: String) {
            val match = Regex("""val\s+$kotlinName\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""").find(theme)
                ?: error("color not found in Theme.kt: $kotlinName")
            assertEquals(tokenHex.removePrefix("#").uppercase(), match.groupValues[1].takeLast(6).uppercase())
        }
        assertColor(colors["brand"]!!.jsonPrimitive.content, "WorkspaceInk")
        assertColor(colors["brandSoft"]!!.jsonPrimitive.content, "WorkspaceSelection")
        assertColor(colors["accent"]!!.jsonPrimitive.content, "WorkspaceAccent")
        assertColor(colors["success"]!!.jsonPrimitive.content, "ZCodeSuccess")
        assertColor(colors["warning"]!!.jsonPrimitive.content, "ZCodeWarning")
        assertColor(colors["destructive"]!!.jsonPrimitive.content, "ZCodeDestructive")
        assertColor(colors["light"]!!.jsonObject["background"]!!.jsonPrimitive.content, "WorkspaceCanvasLight")
        assertColor(colors["dark"]!!.jsonObject["background"]!!.jsonPrimitive.content, "WorkspaceCanvasDark")
        assertColor(colors["dark"]!!.jsonObject["card"]!!.jsonPrimitive.content, "WorkspaceCardDark")
    }

    @Test
    fun manifestDeclaresSkillsToolsAndSessionHook() {
        val manifest = Json.parseToJsonElement(
            assetFile("src/main/assets/andmx-plugins/design/.andmx-plugin/plugin.json").readText(),
        ).jsonObject
        assertEquals("design", manifest["name"]!!.jsonPrimitive.content)
        val skills = manifest["skills"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("skills/design-guide/SKILL.md"), skills)
        val tools = manifest["tools"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(tools.contains("runtime/design_palette.sh"))
        assertTrue(tools.contains("runtime/design_tokens.sh"))
        assertTrue(tools.contains("runtime/design_lint.sh"))
        val hook = manifest["hooks"]!!.jsonArray[0].jsonObject
        assertEquals("SessionStart", hook["event"]!!.jsonPrimitive.content)
        assertTrue(hook["command"]!!.jsonPrimitive.content.contains("session_summary.sh"))
        assertEquals("design:design-rules", hook["name"]!!.jsonPrimitive.content)
        val base = "src/main/assets/andmx-plugins/design"
        assertTrue(assetFile("$base/skills/design-guide/SKILL.md").readText().startsWith("---"))
        assertTrue(assetFile("$base/runtime/design_palette.sh").isFile)
        assertTrue(assetFile("$base/runtime/design_tokens.sh").isFile)
        assertTrue(assetFile("$base/runtime/session_summary.sh").isFile)
        assertTrue(assetFile("$base/runtime/design_lint.sh").isFile)
        val designDoc = assetFile("$base/DESIGN.md").readText()
        assertTrue(designDoc.contains("#0D0D0D"))
        assertTrue(designDoc.contains("design_lint"))
        assertTrue(designDoc.contains("cubic-bezier(0.4, 0, 0.2, 1)"))
    }
}