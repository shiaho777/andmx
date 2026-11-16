package com.andmx.agent

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.resume

internal object JsReplCore {
    const val DEFAULT_TIMEOUT_MS = 10_000L
    const val MAX_TIMEOUT_MS = 30_000L
    const val ASYNC_GRACE_MS = 500L

    private val json = Json { ignoreUnknownKeys = true }

    fun clampTimeout(requested: Long?): Long =
        (requested ?: DEFAULT_TIMEOUT_MS).coerceIn(1, MAX_TIMEOUT_MS)

    fun withLogs(payload: String, logs: List<String>): String {
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return errorPayload("ReplError", "Malformed evaluator payload", "", logs)
        val merged = JsonObject(obj + ("logs" to logsArray(logs)))
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    fun resultPayload(value: String?, logs: List<String>): String {
        val obj = buildJsonObject {
            put("result", value.orEmpty())
            put("logs", logsArray(logs))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun errorPayload(name: String, message: String, stack: String, logs: List<String>): String {
        val obj = buildJsonObject {
            putJsonObject("error") {
                put("name", name)
                put("message", message)
                put("stack", stack)
            }
            put("logs", logsArray(logs))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun asyncOutcome(state: String, logs: List<String>): String? {
        val obj = runCatching { json.parseToJsonElement(state).jsonObject }.getOrNull() ?: return null
        return when (obj["state"]?.jsonPrimitive?.contentOrNull) {
            "done" -> resultPayload(obj["value"]?.jsonPrimitive?.contentOrNull, logs)
            "error" -> errorPayload(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "AsyncError",
                message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                stack = obj["stack"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                logs = logs,
            )
            else -> null
        }
    }

    fun isAsync(payload: String): Boolean = runCatching {
        json.parseToJsonElement(payload).jsonObject["async"]?.jsonPrimitive?.booleanOrNull == true
    }.getOrDefault(false)

    private fun logsArray(logs: List<String>) = JsonArray(logs.map { JsonPrimitive(it) })
}

object JsReplRuntime {
    class LogBridge {
        private val lines = ArrayList<String>()

        @JavascriptInterface
        fun log(text: String) {
            synchronized(lines) { lines.add(text) }
        }

        fun drain(): List<String> = synchronized(lines) {
            val copy = lines.toList()
            lines.clear()
            copy
        }
    }

    private class Session(val webView: WebView, val bridge: LogBridge)

    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun eval(sessionKey: String, context: Context, code: String, timeoutMs: Long): String =
        withContext(Dispatchers.Main) {
            val session = sessions.getOrPut(sessionKey) { createSession(context) }
            session.bridge.drain()
            val deadline = System.currentTimeMillis() + timeoutMs
            val encoded = org.json.JSONObject.quote(code)
            val script = """(function(){
                |try{
                |  var r=(0,eval)($encoded);
                |  if(r&&typeof r.then==='function'){
                |    window.__andmxAsync={state:'pending'};
                |    r.then(function(v){window.__andmxAsync={state:'done',value:window.__andmxFmt(v)}},
                |      function(e){window.__andmxAsync={state:'error',name:String(e&&e.name||'Error'),
                |        message:String(e&&e.message||e),stack:String(e&&e.stack||'')}});
                |    return JSON.stringify({async:true})
                |  }
                |  var out=r===undefined?undefined:window.__andmxFmt(r);
                |  return JSON.stringify({result:out})
                |}catch(e){
                |  return JSON.stringify({error:{name:String(e&&e.name||'Error'),message:String(e&&e.message||e),stack:String(e&&e.stack||'')}})
                |}})()""".trimMargin()
            val first = withTimeoutOrNull(timeoutMs) { evalJs(session.webView, script, timeoutMs) }
                ?: return@withContext JsReplCore.errorPayload(
                    "TimeoutError",
                    "JS evaluation timed out after ${timeoutMs}ms",
                    "",
                    session.bridge.drain(),
                )
            if (JsReplCore.isAsync(first)) {
                while (System.currentTimeMillis() < deadline) {
                    delay(100)
                    val state = runCatching {
                        evalJs(session.webView, "JSON.stringify(window.__andmxAsync||{state:'pending'})", 2_000)
                    }.getOrNull() ?: continue
                    val outcome = JsReplCore.asyncOutcome(state, session.bridge.drain())
                    if (outcome != null) return@withContext outcome
                }
                return@withContext JsReplCore.errorPayload(
                    "TimeoutError",
                    "Async JS evaluation timed out after ${timeoutMs}ms",
                    "",
                    session.bridge.drain(),
                )
            }
            val graceDeadline = minOf(deadline, System.currentTimeMillis() + JsReplCore.ASYNC_GRACE_MS)
            while (System.currentTimeMillis() < graceDeadline) {
                delay(50)
                val pending = runCatching {
                    evalJs(session.webView, "String(window.__andmxPending||0)", 2_000)
                }.getOrNull() ?: break
                if (pending.trim() == "0") break
            }
            JsReplCore.withLogs(first, session.bridge.drain())
        }

    fun reset(sessionKey: String) {
        dispose(sessionKey)
    }

    fun dispose(sessionKey: String) {
        sessions.remove(sessionKey)?.let { session ->
            session.webView.post { session.webView.destroy() }
        }
    }

    private fun createSession(context: Context): Session {
        val bridge = LogBridge()
        val webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(bridge, "__andmxRepl")
            evaluateJavascript(INIT_JS, null)
        }
        return Session(webView, bridge)
    }

    private suspend fun evalJs(webView: WebView, js: String, guardMs: Long): String =
        suspendCancellableCoroutine { cont ->
            val guard = android.os.Handler(android.os.Looper.getMainLooper())
            val timeout = Runnable { if (cont.isActive) cont.resume("") }
            guard.postDelayed(timeout, guardMs)
            webView.evaluateJavascript(js) { raw ->
                guard.removeCallbacks(timeout)
                val unquoted = runCatching {
                    org.json.JSONArray("[$raw]").getString(0)
                }.getOrElse { raw.orEmpty() }
                if (cont.isActive) cont.resume(unquoted)
            }
            cont.invokeOnCancellation { guard.removeCallbacks(timeout) }
        }

    private val INIT_JS = """window.__andmxPending=0;
        |window.__andmxFmt=function(a){
        |  if(typeof a!=='object'||a===null)return String(a);
        |  var seen=new WeakSet();
        |  try{return JSON.stringify(a,function(k,v){
        |    if(typeof v==='object'&&v!==null){if(seen.has(v))return '[Circular]';seen.add(v)}
        |    return v})}catch(e){return String(a)}
        |};
        |['log','info','warn','error','debug'].forEach(function(k){
        |  var o=console[k];console[k]=function(){
        |    try{__andmxRepl.log(Array.prototype.map.call(arguments,window.__andmxFmt).join(' '))}catch(_){}
        |    o&&o.apply(console,arguments);
        |  };});
        |var __andmxSetTimeout=window.setTimeout;
        |window.setTimeout=function(f,d){
        |  if(typeof f==='function'){window.__andmxPending++;
        |    return __andmxSetTimeout(function(){window.__andmxPending--;f.apply(this,arguments)},d)}
        |  return __andmxSetTimeout.apply(window,arguments);
        |};""".trimMargin()
}

class JsReplTool(context: Context, private val sessionKey: String) : Tool {
    private val appContext = context.applicationContext
    override val name = "webview_js"
    override val shouldDefer = true
    override val description =
        "Execute JavaScript in this conversation's persistent WebView REPL. " +
            "window/globalThis state (variables, functions) persists across calls. " +
            "console.log/info/warn/error/debug output is captured into `logs`, including from Promises and setTimeout. " +
            "Args: `code` (required), `timeout_ms` (optional, default 10000, max 30000), " +
            "`reset` (optional boolean, clears the session context before running), " +
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
                put("description", "Max wait ms (default 10000, max 30000)")
            }
            putJsonObject("reset") {
                put("type", "boolean")
                put("description", "Clear the persistent session context before executing")
            }
            putJsonObject("title") {
                put("type", "string")
                put("description", "Required short user-facing title in the user's language describing the action, without implementation jargon")
            }
        }
        putJsonArray("required") { add("code"); add("title") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("code is required", isError = true)
        args["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult("title is required", isError = true)
        val timeoutMs = JsReplCore.clampTimeout(args["timeout_ms"]?.jsonPrimitive?.longOrNull)
        if (args["reset"]?.jsonPrimitive?.booleanOrNull == true) {
            JsReplRuntime.reset(sessionKey)
        }
        val out = JsReplRuntime.eval(sessionKey, appContext, code, timeoutMs)
        val img = DATA_IMAGE_URL.find(out)?.value
        return if (img != null && img.length <= 6 * 1024 * 1024) {
            ToolResult(out.replace(img, "[image output]"), imageUrls = listOf(img))
        } else {
            ToolResult(out, isError = out.contains("\"error\""))
        }
    }

    private companion object {
        val DATA_IMAGE_URL =
            Regex("data:image/(?:png|jpe?g|gif|webp|bmp);base64,[A-Za-z0-9+/=\\r\\n]+")
    }
}