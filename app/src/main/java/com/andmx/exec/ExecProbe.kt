package com.andmx.exec

import android.content.Context
import com.andmx.BuildConfig
import java.io.File

/**
 * Validates the single biggest unknown for on-device, no-root execution:
 * the W^X policy. It exec's the bundled probe binary two ways and reports
 * which succeed.
 *
 *  - Case A: exec straight from nativeLibraryDir. Expected to ALWAYS work,
 *            since that directory is executable regardless of targetSdk.
 *  - Case B: copy the binary into the writable app data dir, then exec it.
 *            Expected to FAIL on targetSdk >= 29 (the "lite" flavor) and to
 *            SUCCEED on targetSdk 28 (the "proot" flavor). Case B is exactly
 *            the capability proot needs to run guest binaries from a rootfs.
 */
class ExecProbe(
    private val context: Context,
    private val env: ExecutionEnvironment = LocalProcessEnvironment(),
) {
    private val probeName = "libandmxprobe.so"

    private fun nativeProbe(): File =
        File(context.applicationInfo.nativeLibraryDir, probeName)

    suspend fun run(): ProbeReport {
        val sb = StringBuilder()
        sb.appendLine("# AndMX 执行环境探针")
        sb.appendLine("flavor       = ${BuildConfig.FLAVOR}")
        sb.appendLine("targetSdk    = ${BuildConfig.PROBE_TARGET_SDK}")
        sb.appendLine("abi          = ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        sb.appendLine("nativeLibDir = ${context.applicationInfo.nativeLibraryDir}")
        sb.appendLine()

        // ---- Case A: nativeLibraryDir ----
        val nativeBin = nativeProbe()
        sb.appendLine("[A] exec 自 nativeLibraryDir")
        val caseA = if (!nativeBin.exists()) {
            sb.appendLine("    ✗ 探针未找到: ${nativeBin.path}")
            false
        } else {
            val r = env.execute(ProcessSpec(argv = listOf(nativeBin.path, "from-nativelib"), redirectErrorStream = true))
            appendResult(sb, r)
            r.ok && r.stdout.contains("ANDMX_PROBE_OK")
        }
        sb.appendLine()

        // ---- Case B: writable data dir ----
        sb.appendLine("[B] exec 自 可写数据目录 (filesDir)")
        val caseB = runCaseB(sb)
        sb.appendLine()

        // ---- Verdict ----
        sb.appendLine("# 结论")
        sb.appendLine("nativeLibraryDir 可执行 : ${verdict(caseA)}")
        sb.appendLine("数据目录可执行 (proot需要): ${verdict(caseB)}")
        if (caseB) {
            sb.appendLine("→ 本变体可承载 proot 用户态发行版。")
        } else {
            sb.appendLine("→ W^X 生效:需 targetSdk 28 的 proot 变体才能跑发行版二进制。")
        }

        return ProbeReport(text = sb.toString().trimEnd(), nativeExecOk = caseA, dataDirExecOk = caseB).also {
            android.util.Log.i("AndmxProbe", "\n" + it.text)
        }
    }

    private suspend fun runCaseB(sb: StringBuilder): Boolean {
        val src = nativeProbe()
        if (!src.exists()) {
            sb.appendLine("    ✗ 源探针缺失,跳过")
            return false
        }
        return try {
            val dst = File(context.filesDir, "probe_copy")
            src.copyTo(dst, overwrite = true)
            @Suppress("SetWorldReadable")
            dst.setExecutable(true, false)
            sb.appendLine("    复制到: ${dst.path} (可执行=${dst.canExecute()})")
            val r = env.execute(ProcessSpec(argv = listOf(dst.path, "from-datadir"), redirectErrorStream = true))
            appendResult(sb, r)
            r.ok && r.stdout.contains("ANDMX_PROBE_OK")
        } catch (t: Throwable) {
            sb.appendLine("    ✗ 异常: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun appendResult(sb: StringBuilder, r: ProcessResult) {
        if (r.error != null) {
            sb.appendLine("    ✗ ${r.error}")
            return
        }
        sb.appendLine("    exit=${r.exitCode}  (${r.durationMs}ms)")
        r.stdout.lineSequence().filter { it.isNotBlank() }.forEach { sb.appendLine("    | $it") }
    }

    private fun verdict(ok: Boolean) = if (ok) "✓ 是" else "✗ 否"
}

data class ProbeReport(
    val text: String,
    val nativeExecOk: Boolean,
    val dataDirExecOk: Boolean,
)
