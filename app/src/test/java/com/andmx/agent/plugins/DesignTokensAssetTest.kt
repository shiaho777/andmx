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
        assertEquals(listOf(0.22, 0.61, 0.36, 1.0), ease)
        assertEquals(0.82, obj["springDampingRatio"]!!.jsonPrimitive.double, 0.001)
        assertEquals(380.0, obj["springStiffness"]!!.jsonPrimitive.double, 0.001)
    }

    @Test
    fun spacingTokensMatchChromeSource() {
        val chrome = assetFile("src/main/java/com/andmx/ui2/theme/Chrome.kt").readText()
        val spacing = tokens["spacing"]!!.jsonObject
        val gutter = Regex("""gutter\s*=\s*(\d+)\.dp""").find(chrome)!!.groupValues[1].toInt()
        val bubble = Regex("""bubble\s*=\s*RoundedCornerShape\((\d+)\.dp\)""").find(chrome)!!.groupValues[1].toInt()
        assertEquals(gutter, spacing["gutterDp"]!!.jsonPrimitive.content.toInt())
        assertEquals(bubble, spacing["bubbleRadiusDp"]!!.jsonPrimitive.content.toInt())
        assertEquals(bubble, spacing["sheetRadiusDp"]!!.jsonPrimitive.content.toInt())
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
        assertColor(colors["brand"]!!.jsonPrimitive.content, "ZCodeBrand")
        assertColor(colors["brandSoft"]!!.jsonPrimitive.content, "ZCodeBrandSoft")
        assertColor(colors["success"]!!.jsonPrimitive.content, "ZCodeSuccess")
        assertColor(colors["warning"]!!.jsonPrimitive.content, "ZCodeWarning")
        assertColor(colors["destructive"]!!.jsonPrimitive.content, "ZCodeDestructive")
        assertColor(colors["light"]!!.jsonObject["background"]!!.jsonPrimitive.content, "ZCodeBgLight")
        assertColor(colors["dark"]!!.jsonObject["background"]!!.jsonPrimitive.content, "ZCodeBgDark")
        assertColor(colors["dark"]!!.jsonObject["card"]!!.jsonPrimitive.content, "ZCodeCardDark")
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
        val hook = manifest["hooks"]!!.jsonArray[0].jsonObject
        assertEquals("SessionStart", hook["event"]!!.jsonPrimitive.content)
        assertTrue(hook["command"]!!.jsonPrimitive.content.contains("session_summary.sh"))
        val base = "src/main/assets/andmx-plugins/design"
        assertTrue(assetFile("$base/skills/design-guide/SKILL.md").readText().startsWith("---"))
        assertTrue(assetFile("$base/runtime/design_palette.sh").isFile)
        assertTrue(assetFile("$base/runtime/design_tokens.sh").isFile)
        assertTrue(assetFile("$base/runtime/session_summary.sh").isFile)
    }
}