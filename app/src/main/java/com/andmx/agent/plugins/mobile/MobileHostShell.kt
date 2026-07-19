package com.andmx.agent.plugins.mobile

import android.content.Context
import android.os.Build
import com.andmx.exec.ProcessResult
import com.andmx.workspace.WorkspaceAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object MobileHostShell {
    suspend fun run(
        context: Context,
        command: String,
        cwd: String? = null,
        timeoutSec: Long = 120,
        preferGuest: Boolean = false,
    ): ProcessResult = withContext(Dispatchers.IO) {
        if (!preferGuest) {
            val host = runHost(command, cwd, timeoutSec)
            if (host != null) return@withContext host
        }
        WorkspaceAccess(context).executeShell(command, cwd = cwd)
    }

    private fun runHost(command: String, cwd: String?, timeoutSec: Long): ProcessResult? {
        return runCatching {
            val pb = ProcessBuilder("sh", "-lc", command)
            if (!cwd.isNullOrBlank()) pb.directory(File(cwd))
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["PATH"] = pathEnv()
            androidSdkRoot()?.let {
                env.putIfAbsent("ANDROID_HOME", it)
                env.putIfAbsent("ANDROID_SDK_ROOT", it)
            }
            val p = pb.start()
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return@runCatching ProcessResult(-1, "", "timeout after ${timeoutSec}s", timeoutSec * 1000, error = "timeout")
            }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            ProcessResult(p.exitValue(), out, "", 0)
        }.getOrNull()
    }

    fun pathEnv(): String {
        return buildString {
            append(System.getenv("PATH") ?: "/system/bin:/system/xbin")
            append(":/data/data/com.termux/files/usr/bin")
            append(":/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin")
            androidSdkRoot()?.let { sdk ->
                append(":$sdk/platform-tools:$sdk/emulator:$sdk/cmdline-tools/latest/bin:$sdk/tools/bin")
            }
        }
    }

    fun androidSdkRoot(): String? {
        return listOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_PLUGIN_SDK_PATH"),
            "${System.getProperty("user.home")}/Library/Android/sdk",
            "${System.getProperty("user.home")}/Android/Sdk",
            "/usr/lib/android-sdk",
            "/opt/android-sdk",
        ).firstOrNull { !it.isNullOrBlank() && File(it).isDirectory }
    }

    fun which(name: String): String? {
        val dirs = pathEnv().split(':').filter { it.isNotBlank() }.distinct()
        for (dir in dirs) {
            val f = File(dir, name)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        val sdk = androidSdkRoot() ?: return null
        val candidates = listOf(
            "$sdk/platform-tools/$name",
            "$sdk/emulator/$name",
            "$sdk/cmdline-tools/latest/bin/$name",
            "$sdk/tools/bin/$name",
        )
        for (candidate in candidates) {
            val f = File(candidate)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }

    fun hostLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

    fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) ||
            System.getProperty("java.specification.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            Build.VERSION.SDK_INT > 0
}
