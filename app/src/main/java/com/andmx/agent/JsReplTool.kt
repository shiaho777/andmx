package com.andmx.agent

import android.content.Context
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.resume

/**
 * ZCode `js`/`node_repl` 对齐：Android 无 Node runtime，用常驻 WebView 的
 * JS 引擎做等价 REPL——每个会话一个实例，globalThis 跨调用保状态。
 * console.* 捕获进 logs；结果为 completion value JSON。
 */
object JsReplRuntime {
    private val sessions = ConcurrentHashMap<String, WebView>()

    suspend fun eval(sessionKey: String, context: Context, code: String, timeoutMs: Long): String =
        withContext(Dispatchers.Main) {
            val webView = sessions.getOrPut(sessionKey) {
                WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    evaluateJavascript(
                        """window.__andmxLogs=[];
                        |['log','info','warn','error','debug'].forEach(function(k){
                        |  var o=console[k];console[k]=function(){
                        |    __andmxLogs.push(Array.prototype.map.call(arguments,function(a){
                        |      try{return typeof a==='object'?JSON.stringify(a):String(a)}catch(_){return String(a)}
                        |    }).join(' '));o&&o.apply(console,arguments);
                        |  };});""".trimMargin(),
                        null,
                    )
                }
            }
            val encoded = org.json.JSONObject.quote(code)
            val script = """(function(){
                |__andmxLogs.length=0;
                |try{
                |  var r=(0,eval)($encoded);
                |  if(r&&typeof r.then==='function'){
                |    window.__andmxAsync={state:'pending',value:undefined};
                |    r.then(function(v){__andmxAsync={state:'done',value:String(v)}},
                |      function(e){__andmxAsync={state:'error',value:String(e&&e.message||e)}});
                |    return JSON.stringify({async:true,logs:__andmxLogs.slice()})
                |  }
                |  var out=r===undefined?undefined:(typeof r==='object'&&r!==null?JSON.stringify(r):String(r));
                |  return JSON.stringify({result:out,logs:__andmxLogs.slice()})
                |}catch(e){
                |  return JSON.stringify({error:{name:e.name||'Error',message:String(e&&e.message||e),stack:String(e&&e.stack||'')},logs:__andmxLogs.slice()})
                |}})()""".trimMargin()
            val deadline = System.currentTimeMillis() + timeoutMs
            suspend fun evalJs(js: String): String =
                suspendCancellableCoroutine { cont ->
                    webView.evaluateJavascript(js) { raw ->
                        val unquoted = runCatching {
                            org.json.JSONArray("[$raw]").getString(0)
                        }.getOrElse { raw.orEmpty() }
                        if (cont.isActive) cont.resume(unquoted)
                    }
                }
            val first = withTimeoutOrNull(timeoutMs) { evalJs(script) }
                ?: return@withContext """{"error":{"name":"TimeoutError","message":"JS evaluation timed out after ${timeoutMs}ms"},"logs":[]}"""
            if (!first.contains("\"async\":true")) return@withContext first
            // promise：轮询 __andmxAsync 至 settle 或超时（上游 node_repl await 等价）
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(150)
                val state = runCatching {
                    evalJs("JSON.stringify(window.__andmxAsync||{state:'pending'})")
                }.getOrNull() ?: continue
                if (state.contains("\"done\"")) {
                    val value = runCatching {
                        org.json.JSONObject(state).getString("value")
                    }.getOrDefault("")
                    return@withContext """{"result":${org.json.JSONObject.quote(value)},"logs":[]}"""
                }
                if (state.contains("\"error\"")) {
                    val value = runCatching {
                        org.json.JSONObject(state).getString("value")
                    }.getOrDefault("async error")
                    return@withContext """{"error":{"name":"AsyncError","message":${org.json.JSONObject.quote(value)}},"logs":[]}"""
                }
            }
            """{"error":{"name":"TimeoutError","message":"Async JS evaluation timed out"},"logs":[]}"""
        }

    fun dispose(sessionKey: String) {
        sessions.remove(sessionKey)?.let { w ->
            w.post { w.destroy() }
        }
    }
}

class JsReplTool(context: Context, private val sessionKey: String) : Tool {
    private val appContext = context.applicationContext
    override val name = "js"
    override val description =
        "Execute JavaScript in a persistent REPL session (globalThis persists across calls). " +
            "Args: `code` (required), `timeout_ms` (optional, default 30000, max 120000), " +
            "`title` (short user-facing description). " +
            "Returns JSON {result, logs} or {error:{name,message,stack}, logs}."
    override val risk = ToolRisk.EXECUTE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("code") {
                put("type", "string")
                put("description", "JavaScript code to execute in the REPL session")
            }
            putJsonObject("timeout_ms") {
                put("type", "integer")
                put("description", "Max wait ms (default 30000, max 120000)")
            }
            putJsonObject("title") {
                put("type", "string")
                put("description", "Required short user-facing title in the user's language describing the action, without implementation terms such as js, JavaScript, or node_repl")
            }
        }
        putJsonArray("required") { add("code"); add("title") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("code is required", isError = true)
        args["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult("title is required", isError = true)
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 30_000L)
            .coerceIn(1, 120_000)
        val out = JsReplRuntime.eval(sessionKey, appContext, code, timeoutMs)
        return ToolResult(out, isError = out.contains("\"error\""))
    }
}
