package com.andmx.agent.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class DesignLintScriptTest {

    private fun assetFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("asset not found: $relative from ${File(".").absolutePath}")
    }

    private val script = assetFile("src/main/assets/andmx-plugins/design/runtime/design_lint.sh")

    @Before
    fun requireShellAndPython() {
        assumeTrue(File("/bin/sh").canExecute())
        val hasPython = listOf("python3", "python").any { bin ->
            runCatching {
                val p = ProcessBuilder(bin, "--version").redirectErrorStream(true).start()
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
        }
        assumeTrue(hasPython)
    }

    private data class LintRun(val exit: Int, val output: String)

    private fun runLint(stdin: String, args: String? = null): LintRun {
        val cmd = mutableListOf("/bin/sh", script.absolutePath)
        if (args != null) cmd += args
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        check(finished) { "design_lint.sh timed out" }
        return LintRun(process.exitValue(), output)
    }

    private fun report(run: LintRun) =
        Json.parseToJsonElement(run.output.trim().lineSequence().last()).jsonObject

    private fun rules(run: LintRun): List<String> =
        report(run)["issues"]!!.jsonArray.map { it.jsonObject["rule"]!!.jsonPrimitive.content }

    @Test
    fun cleanCssPasses() {
        val css = """
            .card {
              font-size: var(--andmx-font-card-title);
              padding: 16px;
              border-radius: 12px;
              color: var(--andmx-brand);
              background: #FFFFFF;
              transition: opacity 160ms;
            }
        """.trimIndent()
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(css))
        }.toString()
        val run = runLint(payload)
        assertEquals(0, run.exit)
        val r = report(run)
        assertTrue(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, r["issueCount"]!!.jsonPrimitive.int)
        assertEquals("css", r["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun offTokenCssValuesAreFlagged() {
        val css = """
            .bad {
              font-size: 17.5px;
              padding: 7px;
              border-radius: 13px;
              color: #123456;
              transition: all 250ms;
            }
        """.trimIndent()
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(css))
        }.toString()
        val run = runLint(payload)
        assertEquals(0, run.exit)
        val r = report(run)
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        val found = rules(run)
        assertTrue(found.contains("font-size"))
        assertTrue(found.contains("spacing"))
        assertTrue(found.contains("radius"))
        assertTrue(found.contains("color"))
        assertTrue(found.contains("motion"))
    }

    @Test
    fun cleanComposePasses() {
        val compose = """
            Text(
                style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(16.dp)),
                color = Color(0xFF0D0D0D),
            )
        """.trimIndent()
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(compose))
            put("mode", kotlinx.serialization.json.JsonPrimitive("compose"))
        }.toString()
        val run = runLint(payload)
        assertEquals(0, run.exit)
        assertTrue(report(run)["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun offTokenComposeValuesAreFlagged() {
        val compose = """
            Text(
                style = TextStyle(fontSize = 19.sp),
                modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(15.dp)),
                color = Color(0xFF123456),
            )
            tween(durationMillis = 250)
        """.trimIndent()
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(compose))
            put("mode", kotlinx.serialization.json.JsonPrimitive("compose"))
        }.toString()
        val run = runLint(payload)
        val found = rules(run)
        assertTrue(found.contains("font-size"))
        assertTrue(found.contains("spacing"))
        assertTrue(found.contains("radius"))
        assertTrue(found.contains("color"))
        assertTrue(found.contains("motion"))
    }

    @Test
    fun argsJsonOnArgvIsParsed() {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(".x { padding: 9px; }"))
        }.toString()
        val run = runLint("", args = payload)
        assertEquals(0, run.exit)
        assertTrue(rules(run).contains("spacing"))
    }

    @Test
    fun fileModeReadsGuestPath() {
        val tmp = File.createTempFile("design-lint", ".css")
        try {
            tmp.writeText(".x { font-size: 21px; }")
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("file", kotlinx.serialization.json.JsonPrimitive(tmp.absolutePath))
            }.toString()
            val run = runLint(payload)
            assertEquals(0, run.exit)
            assertTrue(rules(run).contains("font-size"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun emptyInputReportsUsage() {
        val run = runLint("")
        assertEquals(2, run.exit)
        assertFalse(report(run)["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun sessionHookInjectsDesignRules() {
        val hook = assetFile("src/main/assets/andmx-plugins/design/runtime/session_summary.sh")
        val process = ProcessBuilder("/bin/sh", hook.absolutePath).redirectErrorStream(true).start()
        process.outputStream.close()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        check(finished) { "session_summary.sh timed out" }
        assertEquals(0, process.exitValue())
        val obj = Json.parseToJsonElement(out.lineSequence().last()).jsonObject
        assertEquals("continue", obj["decision"]!!.jsonPrimitive.content)
        val fragment = obj["prompt_fragment"]!!.jsonPrimitive.content
        assertTrue(fragment.contains("设计规则"))
        assertTrue(fragment.contains("design_lint"))
        assertTrue(fragment.contains("#0D0D0D"))
        assertTrue(fragment.contains("圆角"))
        assertTrue(fragment.contains("阴影"))
    }

    @Test
    fun tokenGeneratorEmitsRadiusAndShadow() {
        val gen = assetFile("src/main/assets/andmx-plugins/design/runtime/design_tokens.sh")
        val cssOut = runScript(gen, "css")
        assertTrue(cssOut.contains("--andmx-radius-md: 12px;"))
        assertTrue(cssOut.contains("--andmx-shadow-raised: 6px;"))
        assertTrue(cssOut.contains("--andmx-xs: 4px;"))
        val composeOut = runScript(gen, "compose")
        assertTrue(composeOut.contains("val radiusBubbleDp = 16.dp"))
        assertTrue(composeOut.contains("val shadowDragDp = 8.dp"))
    }

    private fun runScript(script: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("/bin/sh", script.absolutePath) + args)
            .redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${script.name} timed out" }
        check(process.exitValue() == 0) { "${script.name} failed: $output" }
        return output
    }
}
