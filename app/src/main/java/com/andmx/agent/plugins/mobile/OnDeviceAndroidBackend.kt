package com.andmx.agent.plugins.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.andmx.agent.ToolResult
import com.andmx.computeruse.AccessibilityController
import com.andmx.computeruse.MediaProjectionManagerHolder
import com.andmx.computeruse.ScreenCaptor
import com.andmx.computeruse.ScreenCaptureService
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.workspace.WorkspaceAccess
import java.io.File
import java.util.concurrent.TimeUnit

class OnDeviceAndroidBackend(private val context: Context) {
    private val app = context.applicationContext
    private val a11y = AccessibilityController(app)
    private val captors = ScreenCaptor(app)

    fun preferLocal(serial: String?): Boolean {
        if (!MobileHostShell.isAndroidRuntime()) return false
        val s = serial?.trim().orEmpty()
        if (s.isBlank() || s.lowercase() in LOCAL_SERIALS) return true
        return MobileHostShell.which("adb") == null
    }

    fun localDeviceLine(): String {
        val (w, h) = MediaProjectionManagerHolder.realScreenSize(app)
        return buildString {
            append("local\tdevice")
            append(" product:").append(Build.PRODUCT)
            append(" model:").append(Build.MODEL)
            append(" device:").append(Build.DEVICE)
            append(" transport_id:local")
            append(" screen:").append(w).append('x').append(h)
            append(" serial=local")
        }
    }

    fun listDevicesText(): String {
        val adb = MobileHostShell.which("adb")
        val adbLines = if (adb != null) {
            runCatching {
                val p = ProcessBuilder(adb, "devices", "-l").redirectErrorStream(true).start()
                p.waitFor(12, TimeUnit.SECONDS)
                p.inputStream.bufferedReader().readText().trim()
            }.getOrDefault("")
        } else ""
        return buildString {
            appendLine("List of devices attached")
            appendLine(localDeviceLine())
            appendLine("backend=on_device")
            if (adbLines.isNotBlank()) {
                appendLine()
                appendLine("## adb devices")
                append(adbLines)
            }
        }.trim()
    }

    fun preflightExtras(): String = buildString {
        appendLine("backend: on_device")
        appendLine("local_serial: local")
        appendLine("accessibility_enabled: ${a11y.isServiceEnabled()}")
        appendLine("accessibility_ready: ${a11y.isReady}")
        appendLine("media_projection: ${if (MediaProjectionManagerHolder.isAuthorized) "granted" else "missing"}")
        appendLine("install_unknown_apps: ${canRequestInstall()}")
        appendLine("self_package: ${app.packageName}")
        appendLine("note: device ops use PackageManager / Accessibility / MediaProjection on this phone; serial defaults to local.")
    }

    fun launchApp(applicationId: String, activity: String?): ToolResult {
        val id = applicationId.trim()
        if (id.isBlank()) return ToolResult("applicationId required", isError = true)
        return runCatching {
            val pm = app.packageManager
            val intent = when {
                !activity.isNullOrBlank() -> {
                    val component = if (activity.contains('/')) {
                        val parts = activity.split('/', limit = 2)
                        android.content.ComponentName(parts[0], parts[1])
                    } else if (activity.startsWith('.')) {
                        android.content.ComponentName(id, id + activity)
                    } else {
                        android.content.ComponentName(id, activity)
                    }
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        this.component = component
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                else -> pm.getLaunchIntentForPackage(id)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return ToolResult("no launcher activity for $id", isError = true)
            app.startActivity(intent)
            ToolResult("launched $id backend=on_device")
        }.getOrElse { ToolResult("launch failed: ${it.message}", isError = true) }
    }

    fun terminateApp(applicationId: String): ToolResult {
        val id = applicationId.trim()
        if (id.isBlank()) return ToolResult("applicationId required", isError = true)
        if (id == app.packageName) {
            return ToolResult("refusing to force-stop AndMX itself", isError = true)
        }
        return runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.killBackgroundProcesses(id)
            ToolResult(
                "requested killBackgroundProcesses for $id (backend=on_device). " +
                    "Android apps cannot force-stop arbitrary packages without shell/root; " +
                    "background processes may be cleared only.",
            )
        }.getOrElse { ToolResult("terminate failed: ${it.message}", isError = true) }
    }

    fun openUrl(url: String): ToolResult {
        val u = url.trim()
        if (u.isBlank()) return ToolResult("url required", isError = true)
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ToolResult("opened $u backend=on_device")
        }.getOrElse { ToolResult("open url failed: ${it.message}", isError = true) }
    }

    fun installApk(apkPath: String, access: WorkspaceAccess): ToolResult {
        val file = resolveLocalFile(apkPath, access)
            ?: return ToolResult("apk not found: $apkPath", isError = true)
        if (!file.isFile || file.length() <= 0L) {
            return ToolResult("apk empty or missing: ${file.absolutePath}", isError = true)
        }
        if (!canRequestInstall()) {
            return ToolResult(
                "unknown app install permission missing. Open settings to allow AndMX to install packages, then retry. path=${file.absolutePath}",
                isError = true,
            )
        }
        return runCatching {
            val staged = stageApk(file)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", staged)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(intent)
            ToolResult(
                "opened system package installer for ${staged.name} " +
                    "(${staged.length()} bytes, backend=on_device). Confirm install on device UI.",
            )
        }.getOrElse { ToolResult("install failed: ${it.message}", isError = true) }
    }

    fun screenshot(access: WorkspaceAccess, outHint: String?): ToolResult {
        if (!MediaProjectionManagerHolder.isAuthorized) {
            return ToolResult(
                "MediaProjection not granted. Enable screen capture for AndMX, then retry android_screenshot.",
                isError = true,
            )
        }
        if (!captors.isActive) {
            ScreenCaptureService.start(app)
            if (!captors.ensureStarted()) {
                return ToolResult("failed to start screen capture service", isError = true)
            }
        }
        val url = captors.capture()
            ?: return ToolResult("screenshot capture failed", isError = true)
        val note = buildString {
            append("screenshot backend=on_device media_projection size=${captors.snapshotDims()}")
            if (!outHint.isNullOrBlank()) append(" requested_path=$outHint")
        }
        return ToolResult(note, imageUrls = listOf(url))
    }

    fun logs(lines: Int, filter: String?): ToolResult {
        val n = lines.coerceIn(20, 2000)
        val cmd = buildString {
            append("logcat -d -t $n")
            val f = filter?.trim().orEmpty()
            if (f.isNotBlank()) append(" | grep -F ").append(shellQuote(f)).append(" || true")
        }
        return runCatching {
            val p = ProcessBuilder("sh", "-lc", cmd).redirectErrorStream(true).start()
            val finished = p.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return ToolResult("logcat timeout", isError = true)
            }
            val out = p.inputStream.bufferedReader().readText().trim()
            if (out.isBlank()) {
                ToolResult(
                    "logcat empty (backend=on_device). Without READ_LOGS/shell, apps usually only see limited process logs.",
                )
            } else {
                ToolResult(out.take(24_000))
            }
        }.getOrElse { ToolResult("logcat failed: ${it.message}", isError = true) }
    }

    fun uiStatus(): ToolResult {
        val text = buildString {
            appendLine("backend=on_device")
            appendLine("accessibility_enabled=${a11y.isServiceEnabled()}")
            appendLine("accessibility_ready=${a11y.isReady}")
            appendLine("media_projection=${if (MediaProjectionManagerHolder.isAuthorized) "ok" else "missing"}")
            appendLine("uiautomator=accessibility")
            if (!a11y.isServiceEnabled()) {
                appendLine("hint=open Settings → Accessibility and enable AndMX")
            }
        }
        return ToolResult(text)
    }

    fun uiDescribe(): ToolResult {
        val r = a11y.dumpUiTree()
        return if (r.isSuccess) ToolResult(r.getOrThrow().take(24_000))
        else ToolResult(r.exceptionOrNull()?.message ?: "ui describe failed", isError = true)
    }

    fun uiResolve(query: String): ToolResult {
        val r = a11y.resolveUi(query)
        return if (r.isSuccess) ToolResult(r.getOrThrow().take(24_000))
        else ToolResult(r.exceptionOrNull()?.message ?: "ui resolve failed", isError = true)
    }

    suspend fun uiTap(x: Int, y: Int): ToolResult {
        val r = a11y.tap(x, y)
        return if (r.isSuccess) ToolResult("tapped ($x,$y) backend=on_device")
        else ToolResult(r.exceptionOrNull()?.message ?: "tap failed", isError = true)
    }

    suspend fun uiSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ToolResult {
        val r = a11y.swipe(x1, y1, x2, y2, durationMs.toLong().coerceIn(50L, 5000L))
        return if (r.isSuccess) ToolResult("swiped ($x1,$y1)->($x2,$y2) backend=on_device")
        else ToolResult(r.exceptionOrNull()?.message ?: "swipe failed", isError = true)
    }

    fun uiType(text: String): ToolResult {
        val r = a11y.typeText(text)
        return if (r.isSuccess) ToolResult("typed text backend=on_device")
        else ToolResult(r.exceptionOrNull()?.message ?: "type failed", isError = true)
    }

    fun uiKey(key: String): ToolResult {
        val mapped = when (key.trim().uppercase()) {
            "BACK" -> "back"
            "HOME" -> "home"
            "APP_SWITCH", "RECENTS" -> "recents"
            "ENTER" -> return ToolResult("ENTER requires focused IME; use android_ui_type_text or computer tool", isError = true)
            "MENU" -> return ToolResult("MENU not available via accessibility global actions", isError = true)
            "SEARCH" -> return ToolResult("SEARCH not available via accessibility global actions", isError = true)
            else -> key.trim().lowercase()
        }
        val r = a11y.sendKey(mapped)
        return if (r.isSuccess) ToolResult("key=$mapped backend=on_device")
        else ToolResult(r.exceptionOrNull()?.message ?: "key failed", isError = true)
    }

    fun noEmulatorMessage(action: String): ToolResult = ToolResult(
        "$action unavailable on this Android host. The host itself is the device (serial=local). " +
            "Use android_list_devices / android_install_app / android_launch_app on local, " +
            "or install desktop Android SDK emulator when targeting nested AVDs.",
        isError = true,
    )

    private fun canRequestInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.packageManager.canRequestPackageInstalls()
        } else true
    }

    private fun stageApk(src: File): File {
        val dir = File(app.cacheDir, "apk-install").apply { mkdirs() }
        val dest = File(dir, src.name.ifBlank { "app.apk" })
        src.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    private fun resolveLocalFile(path: String, access: WorkspaceAccess): File? {
        val raw = path.trim()
        if (raw.isBlank()) return null
        val asHost = File(raw)
        if (asHost.isFile) return asHost
        val guest = access.resolvePath(raw)
        val fromGuest = runCatching {
            GuestFs(ProotRuntime(app)).resolve(guest)
        }.getOrNull()
        if (fromGuest != null && fromGuest.isFile) return fromGuest
        val hostProject = access.hostPath
        if (hostProject != null) {
            val rel = guest.removePrefix("/root/project").trimStart('/')
            val candidate = File(hostProject, rel)
            if (candidate.isFile) return candidate
            val direct = File(hostProject, raw)
            if (direct.isFile) return direct
        }
        return null
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    companion object {
        private val LOCAL_SERIALS = setOf("local", "self", "device", "host", "this", "on_device")
    }
}
