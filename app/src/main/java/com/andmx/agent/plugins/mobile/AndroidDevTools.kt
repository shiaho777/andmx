package com.andmx.agent.plugins.mobile

import android.content.Context
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.workspace.WorkspaceAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

class AndroidDevToolset(private val context: Context) {
    private val access = WorkspaceAccess(context)

    fun tools(): List<Tool> = listOf(
        AndroidPreflightTool(context, access),
        AndroidDiscoverProjectTool(context, access),
        AndroidCreateAppTool(context, access),
        AndroidBuildAppTool(context, access),
        AndroidBuildAndRunTool(context, access),
        AndroidListDevicesTool(context),
        AndroidListAvdsTool(context),
        AndroidStartEmulatorTool(context),
        AndroidStopEmulatorTool(context),
        AndroidCreateAvdTool(context),
        AndroidInstallAppTool(context, access),
        AndroidLaunchAppTool(context),
        AndroidTerminateAppTool(context),
        AndroidOpenUrlTool(context),
        AndroidScreenshotTool(context, access),
        AndroidLogsTool(context),
        AndroidUiStatusTool(context),
        AndroidUiDescribeTool(context),
        AndroidUiResolveTool(context),
        AndroidUiTapTool(context),
        AndroidUiSwipeTool(context),
        AndroidUiTypeTextTool(context),
        AndroidUiKeyeventTool(context),
    )
}

private suspend fun sh(context: Context, cmd: String, cwd: String? = null, timeout: Long = 180): ToolResult {
    val res = MobileHostShell.run(context, cmd, cwd = cwd, timeoutSec = timeout)
    val out = (res.stdout + res.stderr).trim().ifBlank { "(no output)" }
    return ToolResult(out.take(24_000), isError = res.exitCode != 0 && res.error == null && !out.contains("List of devices"))
}

private fun serialFlag(args: JsonObject): String {
    val s = args.str("serial")?.trim().orEmpty()
    return if (s.isBlank()) "" else "-s ${shellQuote(s)}"
}

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

private fun onDevice(context: Context) = OnDeviceAndroidBackend(context)
private fun preferOnDevice(context: Context, args: JsonObject): Boolean =
    onDevice(context).preferLocal(args.str("serial"))

private class AndroidPreflightTool(
    private val context: Context,
    private val access: WorkspaceAccess,
) : Tool {
    override val name = "android_preflight"
    override val description =
        "Check whether the Android SDK, adb, emulator, AVDs, Java, Gradle project state, and ADB/UI Automator automation are available."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val cfg = MobilePluginConfig.allAndroid(context)
        val sdk = MobilePluginConfig.resolvedSdk(context)
        val adb = MobileHostShell.which("adb")
        val emulator = MobileHostShell.which("emulator")
        val java = MobileHostShell.which("java")
        val gradle = MobileHostShell.which("gradle")
        val avdmanager = MobileHostShell.which("avdmanager")
        val sdkmanager = MobileHostShell.which("sdkmanager")
        val devices = if (adb != null) {
            MobileHostShell.run(context, "adb devices -l", timeoutSec = 20).stdout.trim()
        } else "(adb not found)"
        val avds = if (emulator != null) {
            MobileHostShell.run(context, "emulator -list-avds 2>/dev/null || true", timeoutSec = 20).stdout.trim()
        } else "(emulator not found)"
        val cwd = access.guestCwd()
        val hasGradlew = access.exists("$cwd/gradlew") || access.exists("$cwd/gradlew.bat")
        val hasSettings = access.exists("$cwd/settings.gradle") || access.exists("$cwd/settings.gradle.kts")
        val hasLocal = access.exists("$cwd/local.properties")
        val readyDevice = devices.lines().any { line ->
            val parts = line.trim().split(Regex("\\s+"))
            parts.size >= 2 && parts[1] == "device" && !parts[0].startsWith("List")
        }
        val canScaffold = true
        val canDiscover = true
        val canBuild = hasGradlew || gradle != null
        val onDev = MobileHostShell.isAndroidRuntime()
        val canDeviceOps = (adb != null && readyDevice) || onDev
        val canUi = (adb != null && readyDevice) || onDev
        val canScreenshot = adb != null || (onDev && com.andmx.computeruse.MediaProjectionManagerHolder.isAuthorized) || onDev
        val canEmulator = emulator != null
        val text = buildString {
            appendLine("# AndMX Android Preflight")
            appendLine("host: ${MobileHostShell.hostLabel()}")
            appendLine("runtime_android: ${MobileHostShell.isAndroidRuntime()}")
            if (MobileHostShell.isAndroidRuntime()) {
                appendLine()
                appendLine("## on-device backend")
                append(onDevice(context).preflightExtras())
            }
            appendLine("sdk: ${sdk ?: "not found"}")
            appendLine("adb: ${adb ?: "not found"}")
            appendLine("emulator: ${emulator ?: "not found"}")
            appendLine("avdmanager: ${avdmanager ?: "not found"}")
            appendLine("sdkmanager: ${sdkmanager ?: "not found"}")
            appendLine("java: ${java ?: "not found"}")
            appendLine("gradle: ${gradle ?: "not found"}")
            appendLine("cwd: $cwd")
            appendLine("gradlew: ${if (hasGradlew) "yes" else "no"}")
            appendLine("settings.gradle: ${if (hasSettings) "yes" else "no"}")
            appendLine("local.properties: ${if (hasLocal) "yes" else "no"}")
            appendLine("ready_device: ${if (readyDevice) "yes" else "no"}")
            appendLine()
            appendLine("## capability")
            appendLine("scaffold_create_app: ${if (canScaffold) "yes" else "no"}")
            appendLine("discover_project: ${if (canDiscover) "yes" else "no"}")
            appendLine("build_app: ${if (canBuild) "conditional" else "no (need gradlew/java/sdk)"}")
            appendLine("device_install_launch_ui: ${when {
                canDeviceOps && onDev -> "yes (on_device)"
                canDeviceOps -> "yes (adb)"
                else -> "no (need adb device or on-device backend)"
            }}")
            appendLine("ui_automation: ${if (canUi) "yes" else "no"}")
            appendLine("screenshot: ${if (canScreenshot) "yes" else "no (adb or media projection)"}")
            appendLine("start_emulator: ${if (canEmulator) "yes" else "no (desktop SDK emulator only)"}")
            appendLine()
            appendLine("## notes")
            appendLine("- Tools are real native implementations (not empty stubs).")
            appendLine("- Build/run/emulator need a host with Android SDK + JDK + adb/emulator on PATH (desktop or Termux-style setup).")
            appendLine("- On Android phone host: install/launch/open_url/screenshot/UI use on-device backend (PackageManager + Accessibility + MediaProjection).")
            appendLine("- Nested emulator tools still require desktop Android SDK emulator binaries.")
            if (MobileHostShell.isAndroidRuntime() && adb == null) {
                appendLine("- adb not found: device tools fall back to on_device backend with serial=local.")
            }
            appendLine()
            appendLine("## plugin config")
            cfg.forEach { (k, v) -> appendLine("$k: ${v.ifBlank { "(default/empty)" }}") }
            appendLine()
            appendLine("## adb devices")
            appendLine(devices.ifBlank { "(none)" })
            appendLine()
            appendLine("## avds")
            appendLine(avds.ifBlank { "(none)" })
            appendLine()
            appendLine("Follow skills/andmx-android-dev/INSTALL_ENVIRONMENT.md when setup is incomplete.")
            if (readyDevice) {
                appendLine("A USB/device target is ready; missing AVD does not block targeted tools when serial is provided.")
            }
        }
        return ToolResult(text)
    }
}

private class AndroidDiscoverProjectTool(
    private val context: Context,
    private val access: WorkspaceAccess,
) : Tool {
    override val name = "android_discover_project"
    override val description = "Find the Gradle root, modules, variants, app ID, manifest, and APK outputs in the current project."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("dir") { put("type", "string") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val root = access.resolvePath(args.str("dir") ?: access.guestCwd())
        val cmd = """
            set -e
            ROOT=${shellQuote(root)}
            cd "${'$'}ROOT" 2>/dev/null || exit 1
            echo "root=${'$'}ROOT"
            for f in settings.gradle settings.gradle.kts; do [ -f "${'$'}f" ] && echo "settings=${'$'}f"; done
            for f in build.gradle build.gradle.kts; do [ -f "${'$'}f" ] && echo "root_build=${'$'}f"; done
            for f in app/build.gradle app/build.gradle.kts; do [ -f "${'$'}f" ] && echo "app_build=${'$'}f"; done
            for f in app/src/main/AndroidManifest.xml; do [ -f "${'$'}f" ] && echo "manifest=${'$'}f"; done
            if [ -f local.properties ]; then echo "local_properties=yes"; else echo "local_properties=no"; fi
            if [ -f gradle.properties ]; then echo "gradle_properties=yes"; else echo "gradle_properties=no"; fi
            if [ -x ./gradlew ] || [ -f ./gradlew ]; then echo "wrapper=yes"; else echo "wrapper=no"; fi
            rg -n "applicationId|namespace" app/build.gradle* 2>/dev/null | head -n 20 || true
            find . -path '*/build/outputs/apk/*/*.apk' 2>/dev/null | head -n 20
            ls -1 2>/dev/null | head -n 40
        """.trimIndent()
        return sh(context, cmd, cwd = root, timeout = 60)
    }
}

private class AndroidCreateAppTool(
    private val context: Context,
    private val access: WorkspaceAccess,
) : Tool {
    override val name = "android_create_app"
    override val description = "Create a minimal Kotlin and Jetpack Compose Android app in the current workspace."
    override val risk = ToolRisk.WRITE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "App / project name.")
            }
            putJsonObject("packageName") {
                put("type", "string")
                put("description", "Android applicationId / namespace. Defaults to com.example.<slug>.")
            }
            putJsonObject("dir") {
                put("type", "string")
                put("description", "Relative directory under the workspace. Defaults to <name>.")
            }
            putJsonObject("minSdk") { put("type", "integer") }
            putJsonObject("compileSdk") { put("type", "integer") }
            putJsonObject("overwrite") {
                put("type", "boolean")
                put("description", "Replace existing generated files. Defaults to false.")
            }
        }
        putJsonArray("required") { add("name") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.str("name")?.trim().orEmpty()
        if (name.isBlank()) return ToolResult("name required", isError = true)
        val clean = name.replace(Regex("[^A-Za-z0-9_\\-]"), "").ifBlank { "AndmxApp" }
        val slug = clean.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "app" }
        val pkg = args.str("packageName")?.trim().orEmpty().ifBlank { "com.example.$slug" }
        val overwrite = args.bool("overwrite") == true
        val minSdk = args.int("minSdk") ?: 23
        val apiDefault = MobilePluginConfig.androidApiLevel(context).toIntOrNull() ?: 35
        val compileSdk = args.int("compileSdk") ?: apiDefault
        val relDir = args.str("dir")?.trim().orEmpty().ifBlank { clean }
        val base = access.resolvePath(relDir)
        val files = AndroidComposeTemplate.files(
            appName = clean,
            packageName = pkg,
            minSdk = minSdk,
            compileSdk = compileSdk,
            sdkDir = MobilePluginConfig.resolvedSdk(context),
        )
        if (!overwrite) {
            val hits = files.keys.filter { access.exists("$base/$it") }
            if (hits.isNotEmpty()) {
                return ToolResult(
                    "Refusing to overwrite existing Android app files: ${hits.joinToString(", ")}. Pass overwrite=true after user confirmation.",
                    isError = true,
                )
            }
        }
        for ((rel, body) in files) {
            val path = "$base/$rel"
            val parent = path.substringBeforeLast('/')
            runCatching { access.executeShell("mkdir -p ${shellQuote(parent)}") }
            runCatching { access.writeText(path, body) }.getOrElse {
                return ToolResult("failed to write $path: ${it.message}", isError = true)
            }
        }
        return ToolResult(
            buildString {
                appendLine("createdBy: template")
                appendLine("root: $base")
                appendLine("module: app")
                appendLine("variant: debug")
                appendLine("applicationId: $pkg")
                appendLine("files:")
                files.keys.forEach { appendLine("  $it") }
                appendLine("note: Build uses ./gradlew when present. If the wrapper is missing, android_build_app can generate it with gradle from PATH.")
            },
        )
    }
}

private class AndroidBuildAppTool(
    private val context: Context,
    private val access: WorkspaceAccess,
) : Tool {
    override val name = "android_build_app"
    override val description = "Build the current or specified Android Gradle project."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") {
                put("type", "string")
                put("description", "Android Gradle project root as a relative path.")
            }
            putJsonObject("dir") {
                put("type", "string")
                put("description", "Alias of projectDir.")
            }
            putJsonObject("module") {
                put("type", "string")
                put("description", "Gradle module. Defaults to the discovered app module.")
            }
            putJsonObject("variant") {
                put("type", "string")
                put("description", "Build variant. Defaults to debug.")
            }
            putJsonObject("applicationId") {
                put("type", "string")
                put("description", "applicationId override when automatic discovery cannot resolve the app ID.")
            }
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("avd") { put("type", "string") }
            putJsonObject("timeoutMs") { put("type", "integer") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val root = access.resolvePath(args.str("projectDir") ?: args.str("dir") ?: access.guestCwd())
        val module = args.str("module")?.trim().orEmpty().ifBlank { "app" }
        val variant = args.str("variant")?.trim().orEmpty().ifBlank { "debug" }
        val task = ":$module:assemble${variant.replaceFirstChar { it.uppercase() }}"
        val timeout = ((args.int("timeoutMs") ?: 600_000) / 1000L).coerceAtLeast(60)
        val cmd = buildString {
            appendLine("set -e")
            appendLine("cd " + shellQuote(root))
            appendLine("if [ -x ./gradlew ]; then GW=./gradlew")
            appendLine("elif [ -f ./gradlew ]; then chmod +x ./gradlew; GW=./gradlew")
            appendLine("elif command -v gradle >/dev/null 2>&1; then")
            appendLine("  gradle wrapper --gradle-version 8.9 || true")
            appendLine("  if [ -f ./gradlew ]; then chmod +x ./gradlew; GW=./gradlew; else GW=gradle; fi")
            appendLine("else echo No gradlew/gradle; exit 127; fi")
            appendLine("\$GW " + shellQuote(task) + " --stacktrace")
            appendLine("find . -path '*/build/outputs/apk/*/*.apk' 2>/dev/null | head -n 20")
        }
        return sh(context, cmd, cwd = root, timeout = timeout)
    }

}

private class AndroidBuildAndRunTool(
    private val context: Context,
    private val access: WorkspaceAccess,
) : Tool {
    override val name = "android_build_and_run"
    override val description =
        "Build the Android app, reuse a ready device/emulator or start a GUI emulator on demand, then install and launch the app."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectDir") { put("type", "string") }
            putJsonObject("dir") { put("type", "string") }
            putJsonObject("module") { put("type", "string") }
            putJsonObject("variant") { put("type", "string") }
            putJsonObject("applicationId") { put("type", "string") }
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("avd") { put("type", "string") }
            putJsonObject("launchActivity") { put("type", "string") }
            putJsonObject("timeoutMs") { put("type", "integer") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val root = access.resolvePath(args.str("projectDir") ?: args.str("dir") ?: access.guestCwd())
        val module = args.str("module")?.trim().orEmpty().ifBlank { "app" }
        val variant = args.str("variant")?.trim().orEmpty().ifBlank { "debug" }
        val task = ":$module:assemble${variant.replaceFirstChar { it.uppercase() }}"
        val serial = serialFlag(args)
        val appId = args.str("applicationId")?.trim().orEmpty()
        val activity = args.str("launchActivity")?.trim().orEmpty()
        val avd = args.str("avd")?.trim().orEmpty().ifBlank { MobilePluginConfig.androidDefaultAvd(context) }
        val timeout = ((args.int("timeoutMs") ?: 900_000) / 1000L).coerceAtLeast(60)
        if (preferOnDevice(context, args)) {
            val buildCmd = buildString {
                appendLine("set -e")
                appendLine("cd " + shellQuote(root))
                appendLine("if [ -x ./gradlew ]; then GW=./gradlew")
                appendLine("elif [ -f ./gradlew ]; then chmod +x ./gradlew; GW=./gradlew")
                appendLine("elif command -v gradle >/dev/null 2>&1; then GW=gradle")
                appendLine("else echo No gradlew/gradle; exit 127; fi")
                appendLine("\$GW " + shellQuote(task) + " --stacktrace")
                appendLine("find . -path '*/build/outputs/apk/*/*.apk' 2>/dev/null | head -n 20")
            }
            val buildRes = sh(context, buildCmd, cwd = root, timeout = timeout)
            if (buildRes.isError) return buildRes
            val apkRel = buildRes.output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.endsWith(".apk") && it.contains("build/outputs") }
                ?: return ToolResult(buildRes.output + "\nno apk found after build", isError = true)
            val apkPath = if (apkRel.startsWith("/")) apkRel else "$root/${apkRel.removePrefix("./")}"
            val installRes = onDevice(context).installApk(apkPath, access)
            val launchRes = if (appId.isNotBlank() && !installRes.isError) {
                onDevice(context).launchApp(appId, activity.ifBlank { null })
            } else null
            return ToolResult(
                buildString {
                    appendLine(buildRes.output)
                    appendLine("apk=$apkPath")
                    appendLine(installRes.output)
                    if (launchRes != null) appendLine(launchRes.output)
                    appendLine("backend=on_device")
                }.trim(),
                isError = installRes.isError || (launchRes?.isError == true),
            )
        }
        val cmd = buildString {
            appendLine("set -e")
            appendLine("cd " + shellQuote(root))
            appendLine("if [ -x ./gradlew ]; then GW=./gradlew")
            appendLine("elif [ -f ./gradlew ]; then chmod +x ./gradlew; GW=./gradlew")
            appendLine("elif command -v gradle >/dev/null 2>&1; then GW=gradle")
            appendLine("else echo No gradlew/gradle; exit 127; fi")
            appendLine("\$GW " + shellQuote(task) + " --stacktrace")
            appendLine("APK=\$(find . -path '*/build/outputs/apk/*/*.apk' 2>/dev/null | head -n 1)")
            appendLine("echo apk=\$APK")
            appendLine("[ -n \"\$APK\" ] || { echo no apk; exit 2; }")
            appendLine("SERIAL=\$(adb " + serial + " get-serialno 2>/dev/null || true)")
            appendLine("if [ -z \"\$SERIAL\" ] || [ \"\$SERIAL\" = unknown ]; then")
            appendLine("  if command -v emulator >/dev/null 2>&1; then")
            appendLine("    nohup emulator -avd " + shellQuote(avd) + " -netdelay none -netspeed full >/tmp/andmx-emulator.log 2>&1 &")
            appendLine("    for i in \$(seq 1 60); do")
            appendLine("      BOOTED=\$(adb wait-for-device shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r')")
            appendLine("      [ \"\$BOOTED\" = 1 ] && break")
            appendLine("      sleep 2")
            appendLine("    done")
            appendLine("    SERIAL=\$(adb get-serialno 2>/dev/null || true)")
            appendLine("  fi")
            appendLine("fi")
            appendLine("adb " + serial + " install -r \"\$APK\"")
            appendLine("APP_ID=" + shellQuote(appId))
            appendLine("if [ -z \"\$APP_ID\" ]; then")
            appendLine("  APP_ID=\$(aapt dump badging \"\$APK\" 2>/dev/null | grep -oE \"name='[^']+'\" | head -n1 | cut -d\\' -f2)")
            appendLine("fi")
            appendLine("echo applicationId=\$APP_ID")
            appendLine("if [ -n \"\$APP_ID\" ]; then")
            appendLine("  if [ -n " + shellQuote(activity) + " ]; then")
            appendLine("    adb " + serial + " shell am start -n \"\$APP_ID/" + activity + "\"")
            appendLine("  else")
            appendLine("    adb " + serial + " shell monkey -p \"\$APP_ID\" -c android.intent.category.LAUNCHER 1")
            appendLine("  fi")
            appendLine("fi")
        }
        return sh(context, cmd, cwd = root, timeout = timeout)
    }

}

private class AndroidListDevicesTool(private val context: Context) : Tool {
    override val name = "android_list_devices"
    override val description = "List devices and emulators visible to adb."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (MobileHostShell.isAndroidRuntime()) {
            return ToolResult(onDevice(context).listDevicesText())
        }
        return sh(context, "adb devices -l")
    }
}

private class AndroidListAvdsTool(private val context: Context) : Tool {
    override val name = "android_list_avds"
    override val description = "List Android Virtual Devices visible to the emulator command."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (MobileHostShell.isAndroidRuntime() && MobileHostShell.which("emulator") == null) {
            return ToolResult(
                "No nested AVD on this Android host. Use serial=local (this phone).\n" +
                    onDevice(context).localDeviceLine(),
            )
        }
        return sh(context, "emulator -list-avds 2>/dev/null || true")
    }
}

private class AndroidStartEmulatorTool(private val context: Context) : Tool {
    override val name = "android_start_emulator"
    override val description =
        "Start a new GUI Android emulator for the specified AVD. To reuse an existing device or emulator, pass its serial to build/install/run tools instead."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("avd") {
                put("type", "string")
                put("description", "Android Virtual Device name to start.")
            }
            putJsonObject("timeoutMs") {
                put("type", "integer")
                put("description", "Startup timeout in milliseconds.")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (MobileHostShell.isAndroidRuntime() && MobileHostShell.which("emulator") == null) {
            return onDevice(context).noEmulatorMessage("android_start_emulator")
        }

        val avd = args.str("avd")?.trim().orEmpty().ifBlank { MobilePluginConfig.androidDefaultAvd(context) }
        val timeout = ((args.int("timeoutMs") ?: 180_000) / 1000L).coerceAtLeast(30)
        val cmd = buildString {
            appendLine("set -e")
            appendLine("command -v emulator >/dev/null 2>&1 || { echo emulator not found; exit 127; }")
            appendLine("nohup emulator -avd " + shellQuote(avd) + " -netdelay none -netspeed full >/tmp/andmx-emulator.log 2>&1 &")
            appendLine("echo started avd=" + avd + " pid=\$!")
            appendLine("for i in \$(seq 1 " + timeout.toString() + "); do")
            appendLine("  if adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r' | grep -q 1; then")
            appendLine("    echo serial=\$(adb get-serialno 2>/dev/null || true)")
            appendLine("    echo booted=true")
            appendLine("    exit 0")
            appendLine("  fi")
            appendLine("  sleep 1")
            appendLine("done")
            appendLine("echo serial=\$(adb get-serialno 2>/dev/null || true)")
            appendLine("echo booted=false")
            appendLine("tail -n 40 /tmp/andmx-emulator.log 2>/dev/null || true")
        }
        return sh(context, cmd, timeout = timeout + 30)
    }

}

private class AndroidStopEmulatorTool(private val context: Context) : Tool {
    override val name = "android_stop_emulator"
    override val description = "Stop the selected Android emulator by serial number."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
        }
        putJsonArray("required") { add("serial") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (MobileHostShell.isAndroidRuntime() && MobileHostShell.which("emulator") == null) {
            return onDevice(context).noEmulatorMessage("android_stop_emulator")
        }

        val serial = args.str("serial")?.trim().orEmpty()
        if (serial.isBlank()) return ToolResult("serial required", isError = true)
        return sh(context, "adb -s ${shellQuote(serial)} emu kill || adb -s ${shellQuote(serial)} shell reboot -p")
    }
}

private class AndroidCreateAvdTool(private val context: Context) : Tool {
    override val name = "android_create_avd"
    override val description = "Create an Android Virtual Device with avdmanager. Confirm SDK package and license configuration with the user first."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "string") }
            putJsonObject("packageId") {
                put("type", "string")
                put("description", "System image package id, e.g. system-images;android-35;default;arm64-v8a")
            }
            putJsonObject("apiLevel") { put("type", "string") }
            putJsonObject("device") { put("type", "string") }
            putJsonObject("abi") { put("type", "string") }
            putJsonObject("variant") { put("type", "string") }
            putJsonObject("force") { put("type", "boolean") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (MobileHostShell.isAndroidRuntime() && MobileHostShell.which("avdmanager") == null) {
            return onDevice(context).noEmulatorMessage("android_create_avd")
        }

        val name = args.str("name")?.trim().orEmpty().ifBlank { MobilePluginConfig.androidDefaultAvd(context) }
        val api = args.str("apiLevel")?.trim().orEmpty().ifBlank { MobilePluginConfig.androidApiLevel(context) }
        val abi = args.str("abi")?.trim().orEmpty().ifBlank { MobilePluginConfig.resolvedAbi(context) }
        val variant = args.str("variant")?.trim().orEmpty().ifBlank { MobilePluginConfig.androidSystemImageVariant(context) }
        val device = args.str("device")?.trim().orEmpty().ifBlank { "pixel_6" }
        val pkg = args.str("packageId")?.trim().orEmpty().ifBlank { "system-images;android-$api;$variant;$abi" }
        val force = if (args.bool("force") == true) "--force" else ""
        val cmd = buildString {
            appendLine("set -e")
            appendLine("echo package=" + pkg)
            appendLine("command -v avdmanager >/dev/null 2>&1 || { echo avdmanager not found; exit 127; }")
            appendLine("echo no | avdmanager create avd -n " + shellQuote(name) + " -k " + shellQuote(pkg) + " -d " + shellQuote(device) + " " + force)
            appendLine("emulator -list-avds")
        }
        return sh(context, cmd, timeout = 300)
    }

}

private class AndroidInstallAppTool(
    private val context: Context,
    private val access: WorkspaceAccess,
) : Tool {
    override val name = "android_install_app"
    override val description = "Install an APK on the selected Android device or emulator."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("apkPath") { put("type", "string") }
        }
        putJsonArray("required") { add("apkPath") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val apk = args.str("apkPath") ?: return ToolResult("apkPath required", isError = true)
        if (preferOnDevice(context, args)) {
            return onDevice(context).installApk(apk, access)
        }
        val path = access.resolvePath(apk)
        return sh(context, "adb ${serialFlag(args)} install -r ${shellQuote(path)}")
    }
}

private class AndroidLaunchAppTool(private val context: Context) : Tool {
    override val name = "android_launch_app"
    override val description = "Launch an installed Android app by application ID."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("applicationId") { put("type", "string") }
            putJsonObject("activity") { put("type", "string") }
        }
        putJsonArray("required") { add("applicationId") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("applicationId")?.trim().orEmpty()
        val act = args.str("activity")?.trim().orEmpty()
        if (preferOnDevice(context, args)) {
            return onDevice(context).launchApp(id, act.ifBlank { null })
        }
        val cmd = if (act.isNotBlank()) {
            "adb ${serialFlag(args)} shell am start -n ${shellQuote(act)}"
        } else {
            "adb ${serialFlag(args)} shell monkey -p ${shellQuote(id)} -c android.intent.category.LAUNCHER 1"
        }
        return sh(context, cmd)
    }
}

private class AndroidTerminateAppTool(private val context: Context) : Tool {
    override val name = "android_terminate_app"
    override val description = "Force-stop an installed Android app by application ID."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("applicationId") { put("type", "string") }
        }
        putJsonArray("required") { add("applicationId") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("applicationId")?.trim().orEmpty()
        if (preferOnDevice(context, args)) {
            return onDevice(context).terminateApp(id)
        }
        return sh(context, "adb ${serialFlag(args)} shell am force-stop ${shellQuote(id)}")
    }
}

private class AndroidOpenUrlTool(private val context: Context) : Tool {
    override val name = "android_open_url"
    override val description = "Open a URL on the selected Android device or emulator."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("url") { put("type", "string") }
        }
        putJsonArray("required") { add("url") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (preferOnDevice(context, args)) {
            return onDevice(context).openUrl(url)
        }
        return sh(context, "adb ${serialFlag(args)} shell am start -a android.intent.action.VIEW -d ${shellQuote(url)}")
    }
}

private class AndroidScreenshotTool(
    private val context: Context,
    private val access: WorkspaceAccess,
) : Tool {
    override val name = "android_screenshot"
    override val description =
        "Capture a PNG screenshot from the selected Android device or emulator and return it as image content."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") {
                put("type", "string")
                put("description", "Android device or emulator serial number.")
            }
            putJsonObject("avd") { put("type", "string") }
            putJsonObject("path") {
                put("type", "string")
                put("description", "Optional workspace path for the PNG file.")
            }
            putJsonObject("timeoutMs") { put("type", "integer") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (preferOnDevice(context, args)) {
            return onDevice(context).screenshot(access, args.str("path"))
        }
        val out = access.resolvePath(
            args.str("path") ?: (access.guestCwd() + "/.andmx/screenshots/android-" + System.currentTimeMillis() + ".png"),
        )
        val parent = out.substringBeforeLast('/')
        val cmd = buildString {
            appendLine("mkdir -p " + shellQuote(parent))
            appendLine("adb " + serialFlag(args) + " exec-out screencap -p > " + shellQuote(out))
            appendLine("ls -la " + shellQuote(out))
            appendLine("echo path=" + out)
        }
        val res = sh(context, cmd)
        if (res.isError) return res
        val bytes = runCatching {
            MobileHostShell.run(
                context,
                "base64 < " + shellQuote(out) + " 2>/dev/null | tr -d '\\n'",
                timeoutSec = 60,
            ).stdout.trim()
        }.getOrDefault("")
        val b64 = if (bytes.isNotBlank() && !bytes.contains(" ")) bytes else ""
        val imageUrls = if (b64.isNotBlank()) listOf("data:image/png;base64,$b64") else null
        return ToolResult(
            output = res.output + if (imageUrls != null) "\nimage=attached" else "\nimage=unavailable",
            isError = res.isError,
            imageUrls = imageUrls,
        )
    }

}

private class AndroidLogsTool(private val context: Context) : Tool {
    override val name = "android_logs"
    override val description = "Read recent logcat output, optionally filtered by application ID."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("avd") { put("type", "string") }
            putJsonObject("applicationId") { put("type", "string") }
            putJsonObject("lines") { put("type", "integer") }
            putJsonObject("limit") { put("type", "integer") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (preferOnDevice(context, args)) {
            val lines = args.int("lines") ?: args.int("count") ?: 200
            return onDevice(context).logs(lines, args.str("filter") ?: args.str("tag"))
        }
        val lines = args.int("lines") ?: args.int("limit") ?: 200
        val app = args.str("applicationId")?.trim().orEmpty()
        val filter = if (app.isBlank()) "" else " | grep -F ${shellQuote(app)} || true"
        return sh(context, "adb ${serialFlag(args)} logcat -d -t $lines$filter")
    }
}

private class AndroidUiStatusTool(private val context: Context) : Tool {
    override val name = "android_ui_status"
    override val description = "Report whether the Android UI automation backend is available."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject { put("type", "object"); putJsonObject("properties") { } }
    override suspend fun execute(args: JsonObject): ToolResult {
        if (preferOnDevice(context, args)) {
            return onDevice(context).uiStatus()
        }
        val cmd = """
            echo "backend=uiautomator"
            if command -v adb >/dev/null 2>&1; then
              adb devices -l | head -n 20
              adb shell uiautomator dump /sdcard/andmx-ui.xml >/dev/null 2>&1 && echo "uiautomator=ok" || echo "uiautomator=unavailable"
            else
              echo "adb=missing"
            fi
        """.trimIndent()
        return sh(context, cmd)
    }
}

private class AndroidUiDescribeTool(private val context: Context) : Tool {
    override val name = "android_ui_describe"
    override val description = "Return a compact UI element tree for the current Android screen."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (preferOnDevice(context, args)) {
            return onDevice(context).uiDescribe()
        }
        val cmd = """
            set -e
            adb ${serialFlag(args)} shell uiautomator dump /sdcard/andmx-ui.xml >/dev/null
            adb ${serialFlag(args)} shell cat /sdcard/andmx-ui.xml | head -c 20000
        """.trimIndent()
        return sh(context, cmd)
    }
}

private class AndroidUiResolveTool(private val context: Context) : Tool {
    override val name = "android_ui_resolve"
    override val description = "Resolve a text, content description, resource id, or class query to screen coordinates."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("query") { put("type", "string") }
        }
        putJsonArray("required") { add("query") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val q = args.str("query")?.trim().orEmpty()
        if (preferOnDevice(context, args)) {
            return onDevice(context).uiResolve(q)
        }
        val cmd = """
            set -e
            adb ${serialFlag(args)} shell uiautomator dump /sdcard/andmx-ui.xml >/dev/null
            XML=${'$'}(adb ${serialFlag(args)} shell cat /sdcard/andmx-ui.xml)
            echo "${'$'}XML" | tr '>' '>\n' | grep -F ${shellQuote(q)} | head -n 20
        """.trimIndent()
        return sh(context, cmd)
    }
}

private class AndroidUiTapTool(private val context: Context) : Tool {
    override val name = "android_ui_tap"
    override val description = "Tap screen coordinates using Android UI automation."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("x") { put("type", "number") }
            putJsonObject("y") { put("type", "number") }
        }
        putJsonArray("required") { add("x"); add("y") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val x = args.str("x") ?: return ToolResult("x required", isError = true)
        val y = args.str("y") ?: return ToolResult("y required", isError = true)
        if (preferOnDevice(context, args)) {
            val xi = x.toDoubleOrNull()?.toInt() ?: return ToolResult("bad x", isError = true)
            val yi = y.toDoubleOrNull()?.toInt() ?: return ToolResult("bad y", isError = true)
            return onDevice(context).uiTap(xi, yi)
        }
        return sh(context, "adb ${serialFlag(args)} shell input tap $x $y")
    }
}

private class AndroidUiSwipeTool(private val context: Context) : Tool {
    override val name = "android_ui_swipe"
    override val description = "Swipe between screen coordinates using Android UI automation."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("x1") { put("type", "number") }
            putJsonObject("y1") { put("type", "number") }
            putJsonObject("x2") { put("type", "number") }
            putJsonObject("y2") { put("type", "number") }
            putJsonObject("durationMs") { put("type", "integer") }
        }
        putJsonArray("required") { add("x1"); add("y1"); add("x2"); add("y2") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val x1 = args.str("x1"); val y1 = args.str("y1"); val x2 = args.str("x2"); val y2 = args.str("y2")
        val d = args.int("durationMs") ?: 300
        if (preferOnDevice(context, args)) {
            fun n(s: String?) = s?.toDoubleOrNull()?.toInt()
            val a = n(x1) ?: return ToolResult("x1 required", isError = true)
            val b = n(y1) ?: return ToolResult("y1 required", isError = true)
            val c = n(x2) ?: return ToolResult("x2 required", isError = true)
            val e = n(y2) ?: return ToolResult("y2 required", isError = true)
            return onDevice(context).uiSwipe(a, b, c, e, d)
        }
        return sh(context, "adb ${serialFlag(args)} shell input swipe $x1 $y1 $x2 $y2 $d")
    }
}

private class AndroidUiTypeTextTool(private val context: Context) : Tool {
    override val name = "android_ui_type_text"
    override val description = "Enter text into the currently focused Android control."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("text") { put("type", "string") }
        }
        putJsonArray("required") { add("text") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.str("text").orEmpty()
        if (preferOnDevice(context, args)) {
            return onDevice(context).uiType(text)
        }
        val escaped = text.replace(" ", "%s")
        return sh(context, "adb ${serialFlag(args)} shell input text ${shellQuote(escaped)}")
    }
}

private class AndroidUiKeyeventTool(private val context: Context) : Tool {
    override val name = "android_ui_keyevent"
    override val description = "Press BACK, HOME, ENTER, APP_SWITCH, MENU, or SEARCH on the selected Android device."
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("key") { put("type", "string") }
        }
        putJsonArray("required") { add("key") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val keyName = args.str("key").orEmpty()
        if (preferOnDevice(context, args)) {
            return onDevice(context).uiKey(keyName)
        }
        val key = when (keyName.uppercase()) {
            "BACK" -> 4
            "HOME" -> 3
            "ENTER" -> 66
            "APP_SWITCH" -> 187
            "MENU" -> 82
            "SEARCH" -> 84
            else -> return ToolResult("unsupported key", isError = true)
        }
        return sh(context, "adb ${serialFlag(args)} shell input keyevent $key")
    }
}
