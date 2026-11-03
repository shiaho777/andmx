package com.andmx.agent.workflow.script

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 工作流脚本的静态分析器。
 *
 * 上游对应物是 `analyzeWorkflowScript`（`@zcode/dynamic-workflow`）。AndMX 里它跑在
 * WebView 上——**跑的是上游那份 TypeScript 代码本身**，不是它的 Kotlin 复述。
 * 打包与校验见 `tools/dwf-analyzer`。
 */
interface WorkflowScriptAnalyzer {
    /** 编译并分析一段脚本。上游 `analyzeWorkflowScript`。 */
    suspend fun analyze(script: String): WorkflowAnalysis

    /** 只问「编不编得过」。上游 `compileWorkflowScript`。 */
    suspend fun compile(script: String): CompileOutcome
}

/** 上游 `CompileResult`。 */
data class CompileOutcome(val ok: Boolean, val diagnostics: List<CompileDiagnostic>)

/**
 * 分析器不可用（没有 WebView、assets 里没有 analyzer.js、脚本执行超时……）。
 *
 * **必须**按「编不过」处理，不能按「没问题」处理。上游对这件事的说法是：
 * 「能存下来但跑不起来」是这个特性最难解释的一种坏掉方式。
 */
class WorkflowAnalyzerUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * 单槽记忆——上游 `workflow-script-analysis.ts` 的 Kotlin 版。
 *
 * 审批 gate 会在执行前分析脚本，handler 执行后又分析一次，一次获批调用因此要跑两遍
 * 编译器。按脚本原文做单槽记忆把这一对折叠成一次编译。
 *
 * 刻意**不是**通用缓存：唯一值得收敛的重复就是这组紧邻的前后调用。也正因如此，
 * 槽里只留一格——留多了就变成缓存，而缓存要处理失效，那才是真正的复杂度来源。
 */
class MemoizedWorkflowScriptAnalyzer(
    private val inner: WorkflowScriptAnalyzer,
) : WorkflowScriptAnalyzer {

    private val lock = Mutex()
    private var lastScript: String? = null
    private var lastResult: WorkflowAnalysis? = null

    override suspend fun analyze(script: String): WorkflowAnalysis = lock.withLock {
        val cached = lastResult
        if (cached != null && lastScript == script) return@withLock cached
        inner.analyze(script).also {
            lastScript = script
            lastResult = it
        }
    }

    override suspend fun compile(script: String): CompileOutcome = lock.withLock {
        val cached = lastResult
        val analysis = if (cached != null && lastScript == script) {
            cached
        } else {
            inner.analyze(script).also {
                lastScript = script
                lastResult = it
            }
        }
        CompileOutcome(ok = analysis.ok, diagnostics = analysis.diagnostics)
    }
}

/**
 * 在 WebView 里执行上游分析器。
 *
 * 线程：WebView 只在主线程上被创建和驱动，所有调用经一把锁串行化——两个会话同时
 * 分析会交错 `evaluateJavascript` 的回调，那是最难查的一类串味。
 *
 * 隔离：页面只加载 `file:///android_asset/dwf/analyzer.html`，脚本关掉网络与跨源。
 * 用户脚本是通过 `JSONObject.quote` 转成字符串字面量再进去的，不会被当代码执行。
 */
class WebViewWorkflowScriptAnalyzer(
    private val context: Context,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
    private val analyzeTimeoutMs: Long = ANALYZE_TIMEOUT_MS,
) : WorkflowScriptAnalyzer {

    private val lock = Mutex()
    private var webView: WebView? = null
    private var ready = false

    override suspend fun analyze(script: String): WorkflowAnalysis = lock.withLock {
        val root = call("prepare", script)
        WorkflowAnalysisCodec.decode(root)
    }

    override suspend fun compile(script: String): CompileOutcome = lock.withLock {
        WorkflowAnalysisCodec.decodeCompileOutcome(call("compile", script))
    }

    /** 释放 WebView。长期持有会白占一个渲染进程，界面销毁时应当调用。 */
    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        webView?.destroy()
        webView = null
        ready = false
    }

    private suspend fun call(method: String, script: String): JsonObject {
        val raw = evaluate(script = script, method = method)
            ?: throw WorkflowAnalyzerUnavailableException("分析器没有返回结果（$method 超时或 WebView 未就绪）")

        // evaluateJavascript 回传的是「返回值的 JSON 表示」；我们让 JS 侧返回一个字符串，
        // 所以这里先脱一层引号，再当成 JSON 文本解析。
        val text = runCatching { JSONTokener(raw).nextValue() }
            .getOrNull()
            ?.let { if (it is String) it else raw }
            ?: raw

        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw WorkflowAnalyzerUnavailableException("分析器返回的不是 JSON：${text.take(200)}")

        val error = root["__andmxError"] as? JsonObject
        if (error != null) {
            val message = (error["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: "未知错误"
            throw WorkflowAnalyzerUnavailableException("分析器执行失败：$message")
        }
        return root
    }

    private suspend fun evaluate(script: String, method: String): String? =
        withTimeoutOrNull(analyzeTimeoutMs) {
            ensureReady()
            withContext(Dispatchers.Main.immediate) {
                val view = webView
                    ?: throw WorkflowAnalyzerUnavailableException("分析器 WebView 已释放")
                val quoted = JSONObject.quote(script)
                val js = """
                    (function(){
                      try { return JSON.stringify(__DWF.$method($quoted)); }
                      catch (e) {
                        return JSON.stringify({__andmxError:{
                          name:(e&&e.name)||'Error',
                          message:String((e&&e.message)||e),
                          stack:String((e&&e.stack)||'')
                        }});
                      }
                    })()
                """.trimIndent()
                view.evaluateJavascriptSuspend(js)
            }
        }

    private suspend fun ensureReady() {
        if (ready) return
        withContext(Dispatchers.Main.immediate) {
            if (ready) return@withContext
            val view = webView ?: createWebView().also { webView = it }
            if (ready) return@withContext

            // 首选：以 file:// 页面加载 assets 里的 analyzer.js——子资源由 WebView 自己取，
            // 不必把 4 MiB 源码经 JNI 灌一遍。
            view.loadUrl(ASSET_URL)
            if (awaitReady(view)) {
                ready = true
                return@withContext
            }

            // 兜底：个别 WebView 实现对 file:// 子资源很保守。直接把 bundle 注进 about:blank。
            // 慢一点，但把「某个厂商的 WebView 不认这条路」从致命故障降级成一次多余的重试。
            view.loadUrl(BLANK_URL)
            view.evaluateJavascriptSuspend(readAnalyzerSource())
            if (awaitReady(view)) {
                ready = true
                return@withContext
            }

            throw WorkflowAnalyzerUnavailableException(
                "分析器未能在 ${readyTimeoutMs * 2}ms 内就绪：$HTML_ASSET 与 ${JS_ASSET} 都在 assets 里，"
                    + "但 WebView 无法执行（是否停用了 Android System WebView？）",
            )
        }
    }

    /**
     * 轮询全局探针直到就绪。
     *
     * 不靠 `onPageFinished` 排序：4 MiB 脚本解析完才算真就绪，而 `onPageFinished` 会在那之前
     * 就触发。直接问 `typeof __DWF` 最快也最准。
     */
    private suspend fun awaitReady(view: WebView): Boolean {
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val probe = view.evaluateJavascriptSuspend("typeof __DWF")
            if (probe != null && probe.contains("object")) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun readAnalyzerSource(): String =
        context.assets.open(JS_ASSET).bufferedReader().use { it.readText() }

    private fun createWebView(): WebView {
        requireAsset()
        return try {
            WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                // 页面与被加载的 analyzer.js 都在 assets 里，file:// 必须允许；
                // 跨源读、ContentProvider、网络三样都关掉，用户脚本没有出口。
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.loadsImagesAutomatically = false
            }
        } catch (e: Throwable) {
            throw WorkflowAnalyzerUnavailableException("无法创建 WebView：${e.message}", e)
        }
    }

    private fun requireAsset() {
        for (asset in listOf(HTML_ASSET, JS_ASSET)) {
            val missing = runCatching { context.assets.open(asset).close() }.isFailure
            if (missing) {
                throw WorkflowAnalyzerUnavailableException(
                    "assets 里没有 $asset：先跑 `node tools/dwf-analyzer/build.mjs` 生成分析器",
                )
            }
        }
    }

    companion object {
        const val HTML_ASSET = "dwf/analyzer.html"
        const val JS_ASSET = "dwf/analyzer.js"
        const val ASSET_URL = "file:///android_asset/$HTML_ASSET"
        const val BLANK_URL = "about:blank"
        const val READY_TIMEOUT_MS = 15_000L
        const val ANALYZE_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 20L
    }
}

/** `evaluateJavascript` 的回调收成挂起函数，好让超时能真正打断等待。 */
private suspend fun WebView.evaluateJavascriptSuspend(js: String): String? =
    suspendCancellableCoroutine { cont ->
        try {
            evaluateJavascript(js) { value ->
                if (cont.isActive) cont.resume(value)
            }
        } catch (e: Throwable) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
