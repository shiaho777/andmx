package com.andmx.agent.workflow.dynamic

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.andmx.agent.workflow.WorkflowFailure
import com.andmx.agent.workflow.WorkflowGraph
import com.andmx.agent.workflow.WorkflowRunSnapshot
import com.andmx.agent.workflow.WorkflowRunStatus
import com.andmx.agent.workflow.WorkflowStrategy
import com.andmx.agent.workflow.script.WorkflowAnalysis
import com.andmx.agent.workflow.script.WorkflowScriptAnalyzer
import com.andmx.data.WorkflowJournalStore
import com.andmx.data.WorkflowStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume

data class DynamicAskRequest(
    val runId: String,
    val requestId: String,
    val sessionId: String,
    val siteId: String,
    val ordinal: Int,
    val instructions: String,
    val typed: Boolean,
    val schemaJson: String?,
    val conversationId: Long,
    val personaJson: String? = null,
)

sealed interface DynamicAskResult {
    data class Text(val text: String) : DynamicAskResult
    data class Submit(val valueJson: String) : DynamicAskResult
    data class Fail(val message: String) : DynamicAskResult
}

interface DynamicWorkflowHost {
    suspend fun createActor(runId: String, siteId: String, ordinal: Int, personaJson: String): String
    suspend fun ask(request: DynamicAskRequest): DynamicAskResult
    suspend fun continueAsk(request: DynamicAskRequest, verdictJson: String): DynamicAskResult
    fun cancelAsk(requestId: String)
    suspend fun worldRead(op: String, argsJson: String): String
    suspend fun publishArtifact(runId: String, requestJson: String): String
}

/**
 * WebView-hosted dynamic-workflow script runner.
 *
 * JS owns the engine and the cell realm; Kotlin owns durable journal writes,
 * actor turns, world reads and artifact publishing. The JS bridge is
 * asynchronous except `saveJournal`, which is deliberately synchronous so every
 * journal mutation survives process death.
 */
class DynamicWorkflowRunner(
    private val context: Context,
    private val analyzer: WorkflowScriptAnalyzer,
    private val host: DynamicWorkflowHost,
    private val runStore: WorkflowStore,
    private val journalStore: WorkflowJournalStore,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val pendingAsks = java.util.concurrent.ConcurrentHashMap<String, DynamicAskRequest>()
    private val actorPersonas = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var webView: WebView? = null
    @Volatile private var ready = false
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun start(
        script: String,
        cwd: String,
        conversationId: Long,
    ): Pair<String?, String?> {
        val analysis = runCatching { analyzer.analyze(script) }.getOrElse {
            return null to (it.message ?: "workflow analysis failed")
        }
        if (!analysis.ok) {
            return null to analysis.diagnostics.joinToString("\n") { it.render() }
        }
        val lowered = loweredOf(analysis) ?: return null to "workflow analysis did not produce a lowered body"
        val runId = "dwf_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val now = Instant.now().toString()
        val snapshot = WorkflowRunSnapshot(
            artifacts = emptyList(),
            createdAt = now,
            cwd = cwd,
            graph = WorkflowGraph(edges = emptyList(), nodes = emptyList()),
            kind = KIND,
            phaseOrder = emptyList(),
            phases = emptyList(),
            runId = runId,
            status = WorkflowRunStatus.running,
            strategy = WorkflowStrategy.DEFAULT,
            task = script.lineSequence().firstOrNull()?.trim()?.take(200).orEmpty().ifBlank { "dynamic workflow" },
            updatedAt = now,
            definitionId = KIND,
        )
        runStore.writeRunRow(snapshot, conversationId)
        val config = configJson(
            runId = runId,
            analysis = analysis,
            script = script,
            cwd = cwd,
            lowered = lowered,
            journalJson = null,
            conversationId = conversationId,
        )
        val error = launch(config)
        return if (error == null) runId to null else {
            updateSnapshot(runId) { it.copy(status = WorkflowRunStatus.failed, updatedAt = Instant.now().toString()) }
            null to error
        }
    }

    suspend fun resume(runId: String): Pair<Boolean, String?> {
        val journal = journalStore.load(runId) ?: return false to "No journal for workflow run $runId."
        val record = runCatching {
            json.parseToJsonElement(journal).jsonObject["runs"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return false to "Workflow journal for $runId is malformed."
        val script = record["scriptText"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (script.isBlank()) return false to "Workflow run $runId has no script text to resume."
        val cwd = record["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val analysis = runCatching { analyzer.analyze(script) }.getOrElse {
            return false to (it.message ?: "workflow analysis failed")
        }
        if (!analysis.ok) return false to "Workflow script no longer compiles."
        val lowered = loweredOf(analysis) ?: return false to "workflow analysis did not produce a lowered body"
        updateSnapshot(runId) { it.copy(status = WorkflowRunStatus.running, updatedAt = Instant.now().toString()) }
        val config = configJson(
            runId = runId,
            analysis = analysis,
            script = script,
            cwd = cwd,
            lowered = lowered,
            journalJson = journal,
            conversationId = runStore.conversationId(runId),
        )
        val error = launch(config)
        return if (error == null) true to null else false to error
    }

    suspend fun cancel(runId: String) {
        withContext(Dispatchers.Main.immediate) {
            webView?.evaluateJavascript(
                "window.__DWF_RUNNER && window.__DWF_RUNNER.cancel(${JSONObject.quote(runId)})",
                null,
            )
        }
    }

    suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            webView?.destroy()
            webView = null
            ready = false
        }
    }

    private suspend fun launch(config: String): String? {
        val view = ensureWebView() ?: return "dynamic workflow WebView is unavailable"
        return withContext(Dispatchers.Main.immediate) {
            view.evaluateJavascript(
                "window.__DWF_RUNNER.start(${JSONObject.quote(config)})" +
                    ".catch(function(e){ __andmx.log(String((e&&e.stack)||e)); })",
                null,
            )
            null
        }
    }

    private suspend fun ensureWebView(): WebView? = lock.withLock {
        if (ready) return@withLock webView
        val view = withContext(Dispatchers.Main.immediate) {
            webView ?: createWebView().also { webView = it }
        }
        if (view == null) return@withLock null
        withContext(Dispatchers.Main.immediate) {
            view.loadUrl(ASSET_URL)
        }
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val probe = evaluate(view, "typeof window.__DWF_RUNNER")
            if (probe != null && probe.contains("object")) {
                ready = true
                return@withLock view
            }
            delay(50)
        }
        null
    }

    private suspend fun evaluate(view: WebView, script: String): String? =
        withContext(Dispatchers.Main.immediate) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(script) { value -> cont.resume(value) }
            }
        }

    private fun createWebView(): WebView? = runCatching {
        WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.loadsImagesAutomatically = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    Log.d(
                        "DynamicWorkflowRunner",
                        "${message.message()} @${message.sourceId()}:${message.lineNumber()}",
                    )
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "__andmx")
        }
    }.getOrNull()

    private fun configJson(
        runId: String,
        analysis: WorkflowAnalysis,
        script: String,
        cwd: String,
        lowered: String,
        journalJson: String?,
        conversationId: Long,
    ): String {
        val raw = analysis.raw
        val askSpecs = raw["askSpecs"] as? JsonArray ?: JsonArray(emptyList())
        val sitePhases = raw["sitePhases"] as? JsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            put("runId", runId)
            put("lowered", lowered)
            put("scriptText", script)
            put("cwd", cwd)
            put("askSpecs", askSpecs)
            put("sitePhases", sitePhases)
            put("caps", buildJsonObject { put("maxConcurrency", 4) })
            put("scriptHash", sha256(script))
            put("conversationId", conversationId)
            journalJson?.let { put("journalJson", it) }
        }.toString()
    }

    private fun loweredOf(analysis: WorkflowAnalysis): String? =
        ((analysis.raw["lowered"] as? JsonObject)?.get("code") as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun postJs(method: String, vararg args: String) {
        val view = webView ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val quoted = args.joinToString(",") { JSONObject.quote(it) }
            view.evaluateJavascript(
                "window.__DWF_RUNNER && window.__DWF_RUNNER.$method($quoted)",
                null,
            )
        }
    }

    private fun postJsRaw(method: String, vararg args: String) {
        val view = webView ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            view.evaluateJavascript(
                "window.__DWF_RUNNER && window.__DWF_RUNNER.$method(${args.joinToString(",")})",
                null,
            )
        }
    }

    private fun updateSnapshot(runId: String, transform: (WorkflowRunSnapshot) -> WorkflowRunSnapshot) {
        scope.launch {
            val current = runStore.readSnapshot(runId) ?: return@launch
            runStore.writeSnapshot(transform(current))
        }
    }

    private fun appendEvent(runId: String, type: String, message: String?) {
        scope.launch {
            runCatching {
                runStore.appendEvent(
                    com.andmx.agent.workflow.WorkflowEvent(
                        kind = KIND,
                        message = message,
                        runId = runId,
                        timestamp = Instant.now().toString(),
                        type = type,
                    ),
                )
            }
        }
    }

    private fun askResultJson(result: DynamicAskResult): String = when (result) {
        is DynamicAskResult.Text -> buildJsonObject {
            put("kind", "text")
            put("value", result.text)
        }.toString()
        is DynamicAskResult.Submit -> buildJsonObject {
            put("kind", "submit")
            put("value", json.parseToJsonElement(result.valueJson))
        }.toString()
        is DynamicAskResult.Fail -> buildJsonObject {
            put("kind", "fail")
            put("error", result.message)
        }.toString()
    }

    private fun settlementStatus(status: String, stopReason: String?): WorkflowRunStatus = when (status) {
        "completed" -> WorkflowRunStatus.completed
        "errored" -> WorkflowRunStatus.failed
        "stopped" -> if (stopReason == "user" || stopReason == "model") {
            WorkflowRunStatus.cancelled
        } else {
            WorkflowRunStatus.paused
        }
        else -> WorkflowRunStatus.running
    }

    inner class Bridge {
        @JavascriptInterface
        fun saveJournal(runId: String, journal: String) {
            runCatching { journalStore.saveBlocking(runId, journal) }
        }

        @JavascriptInterface
        fun emit(runId: String, eventJson: String) {
            val event = runCatching { json.parseToJsonElement(eventJson).jsonObject }.getOrNull()
            val type = event?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "event" }
            val message = event?.get("message")?.jsonPrimitive?.contentOrNull
            appendEvent(runId, type, message)
        }

        @JavascriptInterface
        fun createActor(runId: String, siteId: String, ordinal: Int, personaJson: String) {
            actorPersonas["$runId|$siteId|$ordinal"] = personaJson
            scope.launch { runCatching { host.createActor(runId, siteId, ordinal, personaJson) } }
        }

        @JavascriptInterface
        fun startAsk(runId: String, requestId: String, requestJson: String) {
            val request = parseAsk(runId, requestId, requestJson) ?: return
            pendingAsks[requestId] = request
            scope.launch {
                val result = runCatching { host.ask(request) }
                    .getOrElse { DynamicAskResult.Fail(it.message ?: "agent failed") }
                postJs("askResult", requestId, askResultJson(result))
            }
        }

        @JavascriptInterface
        fun continueAsk(requestId: String, verdictJson: String) {
            val request = pendingAsks[requestId] ?: return
            scope.launch {
                val result = runCatching { host.continueAsk(request, verdictJson) }
                    .getOrElse { DynamicAskResult.Fail(it.message ?: "agent failed") }
                postJs("askResult", requestId, askResultJson(result))
            }
        }

        @JavascriptInterface
        fun cancelAsk(requestId: String) {
            pendingAsks.remove(requestId)
            host.cancelAsk(requestId)
        }

        @JavascriptInterface
        fun worldRead(requestId: String, op: String, argsJson: String) {
            scope.launch {
                val result = runCatching { host.worldRead(op, argsJson) }
                result.onSuccess {
                    postJsRaw(
                        "worldResult",
                        JSONObject.quote(requestId),
                        "true",
                        JSONObject.quote(it),
                        "null",
                    )
                }.onFailure {
                    postJsRaw(
                        "worldResult",
                        JSONObject.quote(requestId),
                        "false",
                        JSONObject.quote("null"),
                        JSONObject.quote(it.message ?: "world read failed"),
                    )
                }
            }
        }

        @JavascriptInterface
        fun publishArtifact(requestId: String, requestJson: String) {
            scope.launch {
                val parsed = runCatching { json.parseToJsonElement(requestJson).jsonObject }.getOrNull()
                val runId = parsed?.get("runId")?.jsonPrimitive?.contentOrNull.orEmpty()
                val request = parsed?.get("request")?.toString() ?: requestJson
                val result = runCatching { host.publishArtifact(runId, request) }
                result.onSuccess {
                    postJsRaw(
                        "artifactResult",
                        JSONObject.quote(requestId),
                        "true",
                        JSONObject.quote(it),
                        "null",
                    )
                }.onFailure {
                    postJsRaw(
                        "artifactResult",
                        JSONObject.quote(requestId),
                        "false",
                        JSONObject.quote("null"),
                        JSONObject.quote(it.message ?: "artifact publish failed"),
                    )
                }
            }
        }

        @JavascriptInterface
        fun settled(runId: String, settlementJson: String) {
            val settlement = runCatching { json.parseToJsonElement(settlementJson).jsonObject }.getOrNull()
            val statusText = settlement?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty()
            val stopReason = settlement?.get("stopReason")?.jsonPrimitive?.contentOrNull
            val status = settlementStatus(statusText, stopReason)
            val failureMessage = settlement?.get("error")
                ?.let { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull }
                ?: stopReason
            updateSnapshot(runId) { snapshot ->
                snapshot.copy(
                    status = status,
                    updatedAt = Instant.now().toString(),
                    completedAt = if (status == WorkflowRunStatus.running) null else Instant.now().toString(),
                    failure = failureMessage?.let {
                        WorkflowFailure(
                            kind = if (status == WorkflowRunStatus.failed) "workflow" else "interrupted",
                            message = it,
                            recoverable = status == WorkflowRunStatus.paused,
                            retryable = status == WorkflowRunStatus.paused,
                        )
                    },
                )
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.i("DynamicWorkflowRunner", message.take(4_000))
        }
    }

    private fun parseAsk(runId: String, requestId: String, requestJson: String): DynamicAskRequest? =
        runCatching {
            val obj = json.parseToJsonElement(requestJson).jsonObject
            val siteId = obj["siteId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ordinal = obj["ordinal"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
            DynamicAskRequest(
                runId = runId,
                requestId = requestId,
                sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                siteId = siteId,
                ordinal = ordinal,
                instructions = obj["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                typed = obj["typed"]?.jsonPrimitive?.contentOrNull == "true",
                schemaJson = obj["schema"]?.toString(),
                conversationId = obj["conversationId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                personaJson = actorPersonas["$runId|$siteId|$ordinal"],
            )
        }.getOrNull()

    companion object {
        const val KIND = "dynamic"
        const val ASSET_URL = "file:///android_asset/dwf/runner.html"
        const val READY_TIMEOUT_MS = 20_000L
    }
}
