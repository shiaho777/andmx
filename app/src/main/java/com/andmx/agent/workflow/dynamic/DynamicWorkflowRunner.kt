package com.andmx.agent.workflow.dynamic

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.andmx.agent.workflow.WorkflowArtifact
import com.andmx.agent.workflow.WorkflowFailure
import com.andmx.agent.workflow.WorkflowGraph
import com.andmx.agent.workflow.WorkflowGraphEdge
import com.andmx.agent.workflow.WorkflowGraphNode
import com.andmx.agent.workflow.WorkflowNodeStatus
import com.andmx.agent.workflow.WorkflowPresetArtifact
import com.andmx.agent.workflow.WorkflowReportItem
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
    val seedJson: String? = null,
)

sealed interface DynamicAskResult {
    data class Text(val text: String) : DynamicAskResult
    data class Submit(val valueJson: String) : DynamicAskResult
    data class Fail(val message: String) : DynamicAskResult
}

interface DynamicWorkflowHost {
    suspend fun createActor(
        runId: String,
        siteId: String,
        ordinal: Int,
        personaJson: String,
        seedJson: String,
    ): String
    suspend fun ask(request: DynamicAskRequest): DynamicAskResult
    suspend fun continueAsk(request: DynamicAskRequest, verdictJson: String): DynamicAskResult
    fun cancelAsk(requestId: String)
    suspend fun worldRead(op: String, argsJson: String): String
    suspend fun publishArtifact(runId: String, requestJson: String): String
    fun actorMessageCount(sessionId: String): Int = 0

    /** 子代理即将执行改写型工具：关闭 amend 导入缓存，避免复用陈旧结果。 */
    suspend fun markMutating(runId: String, siteId: String, ordinal: Int) {}
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
    private val snapshotLock = Mutex()

    suspend fun start(
        script: String,
        cwd: String,
        conversationId: Long,
        runIdOverride: String? = null,
        resumedFrom: String? = null,
        importJournalFrom: String? = null,
    ): Pair<String?, String?> {
        val analysis = runCatching { analyzer.analyze(script) }.getOrElse {
            return null to (it.message ?: "workflow analysis failed")
        }
        if (!analysis.ok) {
            return null to analysis.diagnostics.joinToString("\n") { it.render() }
        }
        val lowered = loweredOf(analysis) ?: return null to "workflow analysis did not produce a lowered body"
        val runId = runIdOverride
            ?: "dwf_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val now = Instant.now().toString()
        val snapshot = WorkflowRunSnapshot(
            artifacts = emptyList(),
            createdAt = now,
            cwd = cwd,
            graph = analysis.siteGraph?.let { site ->
                WorkflowGraph(
                    edges = site.edges.map { WorkflowGraphEdge(from = it.from, to = it.to) },
                    nodes = site.nodes.map { node ->
                        WorkflowGraphNode(
                            id = node.id,
                            kind = node.kind,
                            title = node.label,
                            status = WorkflowNodeStatus.pending,
                        )
                    },
                )
            } ?: WorkflowGraph(edges = emptyList(), nodes = emptyList()),
            kind = KIND,
            phaseOrder = emptyList(),
            phases = emptyList(),
            runId = runId,
            resumedFrom = resumedFrom,
            status = WorkflowRunStatus.running,
            strategy = WorkflowStrategy.DEFAULT,
            task = script.lineSequence().firstOrNull()?.trim()?.take(200).orEmpty().ifBlank { "dynamic workflow" },
            updatedAt = now,
            definitionId = KIND,
        )
        runStore.writeRunRow(snapshot, conversationId)
        val importedJournal = importJournalFrom?.let { journalStore.load(it) }
        val config = configJson(
            runId = runId,
            analysis = analysis,
            script = script,
            cwd = cwd,
            lowered = lowered,
            journalJson = null,
            conversationId = conversationId,
            resumedFrom = resumedFrom,
            importedJournal = importedJournal,
        )
        val error = launch(config)
        return if (error == null) runId to null else {
            updateSnapshot(runId) { it.copy(status = WorkflowRunStatus.failed, updatedAt = Instant.now().toString()) }
            null to error
        }
    }

    suspend fun resume(runId: String): Pair<Boolean, String?> {
        val existing = runStore.readSnapshot(runId)
        if (existing?.pauseReason == "superseded") {
            val by = existing.supersededBy?.let { " by $it" }.orEmpty()
            return false to "Workflow run $runId was superseded$by; resume the successor instead."
        }
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

    suspend fun supersede(runId: String, byRunId: String): Boolean {
        val view = ensureWebView()
        val stopped = if (view == null) {
            false
        } else {
            val raw = evaluate(
                view,
                "String(window.__DWF_RUNNER.stop(" +
                    "${JSONObject.quote(runId)},${JSONObject.quote("superseded")},${JSONObject.quote(byRunId)}))",
            )
            raw?.contains("true") == true
        }
        if (!stopped) {
            val current = runStore.readSnapshot(runId) ?: return false
            if (current.status == WorkflowRunStatus.running ||
                current.status == WorkflowRunStatus.paused ||
                current.status == WorkflowRunStatus.pending
            ) {
                val now = Instant.now().toString()
                runStore.writeSnapshot(
                    current.copy(
                        status = WorkflowRunStatus.cancelled,
                        updatedAt = now,
                        completedAt = now,
                        pauseReason = "superseded",
                        supersededBy = byRunId,
                        failure = null,
                    ),
                )
            }
        }
        return true
    }

    suspend fun markMutating(runId: String, siteId: String, ordinal: Int): Boolean {
        val view = ensureWebView() ?: return false
        val raw = evaluate(
            view,
            "String(window.__DWF_RUNNER.markMutating(" +
                "${JSONObject.quote(runId)},${JSONObject.quote(siteId)},$ordinal))",
        )
        return raw?.contains("true") == true
    }

    suspend fun fillHole(runId: String, fillJson: String): Pair<Boolean, String?> {
        val view = ensureWebView() ?: return false to "dynamic workflow WebView is unavailable"
        val raw = evaluate(
            view,
            "JSON.stringify(window.__DWF_RUNNER.fillHole(" +
                "${JSONObject.quote(runId)},${JSONObject.quote(fillJson)}))",
        ) ?: return false to "fill hole returned no result"
        val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return false to "fill hole returned malformed JSON"
        val ok = obj["ok"]?.jsonPrimitive?.contentOrNull == "true"
        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
        return ok to reason
    }

    suspend fun openHoles(runId: String): String {
        val view = ensureWebView() ?: return "[]"
        val raw = evaluate(
            view,
            "JSON.stringify(window.__DWF_RUNNER.openHoles(${JSONObject.quote(runId)}))",
        ) ?: return "[]"
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
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
        resumedFrom: String? = null,
        importedJournal: String? = null,
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
            resumedFrom?.let { put("resumedFrom", it) }
            importedJournal?.let { put("importedJournal", it) }
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
            snapshotLock.withLock {
                val current = runStore.readSnapshot(runId) ?: return@withLock
                runStore.writeSnapshot(transform(current))
            }
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
        "stopped" -> if (stopReason == "user" || stopReason == "model" || stopReason == "superseded") {
            WorkflowRunStatus.cancelled
        } else {
            WorkflowRunStatus.paused
        }
        else -> WorkflowRunStatus.running
    }

    private fun noteDashboardEvent(runId: String, type: String, event: JsonObject) {
        when (type) {
            "report" -> {
                val artifactId = event["artifactId"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return
                val item = event["item"] ?: return
                val instance = event["instance"] as? JsonObject
                val ordinal = instance?.get("ordinal")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val siteId = instance?.get("siteId")?.jsonPrimitive?.contentOrNull.orEmpty()
                val report = WorkflowReportItem(
                    artifactId = artifactId,
                    itemJson = item.toString(),
                    ordinal = ordinal,
                    siteId = siteId,
                )
                scope.launch {
                    snapshotLock.withLock {
                        val current = runStore.readSnapshot(runId) ?: return@withLock
                        if (current.reports.any {
                                it.artifactId == artifactId &&
                                    it.siteId == siteId &&
                                    it.ordinal == ordinal
                            }
                        ) {
                            return@withLock
                        }
                        runStore.writeSnapshot(
                            current.copy(
                                reports = current.reports + report,
                                updatedAt = Instant.now().toString(),
                            ),
                        )
                    }
                }
            }
            "artifact-published" -> {
                val record = event["artifact"] as? JsonObject ?: return
                val preset = presetFromRecord(record) ?: return
                scope.launch {
                    snapshotLock.withLock {
                        val current = runStore.readSnapshot(runId) ?: return@withLock
                        if (current.presets.any { it.id == preset.id }) return@withLock
                        runStore.writeSnapshot(
                            current.copy(
                                presets = current.presets + preset,
                                updatedAt = Instant.now().toString(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun presetFromRecord(record: JsonObject): WorkflowPresetArtifact? {
        val id = record["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val kind = record["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (kind !in PRESET_KINDS) return null
        return WorkflowPresetArtifact(
            description = record["description"]?.jsonPrimitive?.contentOrNull,
            id = id,
            kind = kind,
            specJson = record["spec"]?.toString() ?: "{}",
            title = record["title"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private suspend fun journalDashboard(
        runId: String,
    ): Pair<List<WorkflowReportItem>, List<WorkflowPresetArtifact>> {
        val text = journalStore.load(runId) ?: return emptyList<WorkflowReportItem>() to emptyList()
        return runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val reports = (root["nodes"]?.jsonArray ?: JsonArray(emptyList()))
                .flatMap { bucket ->
                    val entries = bucket.jsonArray.getOrNull(1)?.jsonArray ?: return@flatMap emptyList()
                    entries.mapNotNull { entry ->
                        val node = entry.jsonObject["event"] as? JsonObject
                            ?: entry.jsonArray.getOrNull(1)?.jsonObject
                            ?: return@mapNotNull null
                        if (node["kind"]?.jsonPrimitive?.contentOrNull != "report") return@mapNotNull null
                        val artifactId = node["artifactId"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val item = node["result"] ?: return@mapNotNull null
                        WorkflowReportItem(
                            artifactId = artifactId,
                            itemJson = item.toString(),
                            ordinal = node["ordinal"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                            siteId = node["siteId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                    }
                }
            val presets = (root["events"]?.jsonArray ?: JsonArray(emptyList()))
                .flatMap { bucket ->
                    val entries = bucket.jsonArray.getOrNull(1)?.jsonArray ?: return@flatMap emptyList()
                    entries.mapNotNull { entry ->
                        val event = entry.jsonObject["event"]?.jsonObject ?: return@mapNotNull null
                        if (event["type"]?.jsonPrimitive?.contentOrNull != "artifact-published") {
                            return@mapNotNull null
                        }
                        (event["artifact"] as? JsonObject)?.let(::presetFromRecord)
                    }
                }
            reports to presets.distinctBy { it.id }
        }.getOrDefault(emptyList<WorkflowReportItem>() to emptyList())
    }

    private fun noteGraphEvent(runId: String, type: String, event: JsonObject) {
        val instance = event["instance"] as? JsonObject ?: return
        val siteId = instance["siteId"]?.jsonPrimitive?.contentOrNull ?: return
        val outcome = event["outcome"]?.jsonPrimitive?.contentOrNull
        val status = when (type) {
            "node-queued", "node-dispatched", "node-executing" -> WorkflowNodeStatus.active
            "node-settled" -> when (outcome) {
                "ok" -> WorkflowNodeStatus.completed
                "failed" -> WorkflowNodeStatus.failed
                "cancelled" -> WorkflowNodeStatus.cancelled
                else -> null
            }
            else -> null
        } ?: return
        val error = event["error"]
            ?.let { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull }
        updateSnapshot(runId) { snapshot ->
            if (status == WorkflowNodeStatus.active &&
                snapshot.status != WorkflowRunStatus.running &&
                snapshot.status != WorkflowRunStatus.pending
            ) {
                return@updateSnapshot snapshot
            }
            if (snapshot.graph.nodes.none { it.id == siteId }) {
                snapshot
            } else {
                snapshot.copy(
                    graph = snapshot.graph.copy(
                        nodes = snapshot.graph.nodes.map { node ->
                            if (node.id != siteId) {
                                node
                            } else {
                                node.copy(
                                    status = status,
                                    error = if (status == WorkflowNodeStatus.failed) error else node.error,
                                )
                            }
                        },
                    ),
                )
            }
        }
    }

    private fun settledGraph(
        graph: WorkflowGraph,
        status: WorkflowRunStatus,
    ): WorkflowGraph {
        val terminal = when (status) {
            WorkflowRunStatus.completed -> WorkflowNodeStatus.completed
            WorkflowRunStatus.failed -> WorkflowNodeStatus.failed
            WorkflowRunStatus.cancelled -> WorkflowNodeStatus.cancelled
            else -> return graph
        }
        return graph.copy(
            nodes = graph.nodes.map { node ->
                when (node.status) {
                    WorkflowNodeStatus.pending -> node.copy(status = WorkflowNodeStatus.skipped)
                    WorkflowNodeStatus.active -> node.copy(status = terminal)
                    else -> node
                }
            },
        )
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
            event?.let {
                noteGraphEvent(runId, type, it)
                noteDashboardEvent(runId, type, it)
            }
        }

        @JavascriptInterface
        fun createActor(
            runId: String,
            siteId: String,
            ordinal: Int,
            personaJson: String,
            seedJson: String,
        ) {
            actorPersonas["$runId|$siteId|$ordinal"] = personaJson
            scope.launch {
                runCatching { host.createActor(runId, siteId, ordinal, personaJson, seedJson) }
            }
        }

        @JavascriptInterface
        fun actorMessageCount(sessionId: String): Int = host.actorMessageCount(sessionId)

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
                result.onSuccess { recordJson ->
                    recordArtifact(runId, recordJson)
                    postJsRaw(
                        "artifactResult",
                        JSONObject.quote(requestId),
                        "true",
                        JSONObject.quote(recordJson),
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
            val stopReason = settlement?.get("reason")?.jsonPrimitive?.contentOrNull
                ?: settlement?.get("stopReason")?.jsonPrimitive?.contentOrNull
            val status = settlementStatus(statusText, stopReason)
            val failureMessage = if (
                statusText == "stopped" &&
                (stopReason == "user" || stopReason == "model" || stopReason == "superseded")
            ) {
                null
            } else {
                settlement?.get("error")
                    ?.let { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull }
                    ?: stopReason
            }
            val supersededBy = settlement?.get("supersededBy")?.jsonPrimitive?.contentOrNull
            val resultJson = settlement?.get("artifact")
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.toString()
                ?.take(64_000)
            scope.launch {
                snapshotLock.withLock {
                    val current = runStore.readSnapshot(runId) ?: return@withLock
                    val journalArtifacts = journalArtifacts(runId)
                    val (journalReports, journalPresets) = journalDashboard(runId)
                    val merged = (current.artifacts + journalArtifacts).distinctBy { it.path to it.label }
                    runStore.writeSnapshot(
                        current.copy(
                            artifacts = merged,
                            presets = (current.presets + journalPresets).distinctBy { it.id },
                            reports = (current.reports + journalReports)
                                .distinctBy { Triple(it.artifactId, it.siteId, it.ordinal) },
                            status = status,
                            updatedAt = Instant.now().toString(),
                            completedAt = if (status == WorkflowRunStatus.running) null else Instant.now().toString(),
                            resultJson = resultJson ?: current.resultJson,
                            supersededBy = supersededBy ?: current.supersededBy,
                            pauseReason = if (stopReason == "superseded") "superseded" else current.pauseReason,
                            graph = settledGraph(current.graph, status),
                            failure = failureMessage?.let {
                                WorkflowFailure(
                                    kind = if (status == WorkflowRunStatus.failed) "workflow" else "interrupted",
                                    message = it,
                                    recoverable = status == WorkflowRunStatus.paused,
                                    retryable = status == WorkflowRunStatus.paused,
                                )
                            },
                        ),
                    )
                }
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.i("DynamicWorkflowRunner", message.take(4_000))
        }
    }

    private fun recordArtifact(runId: String, recordJson: String) {
        val obj = runCatching { json.parseToJsonElement(recordJson).jsonObject }.getOrNull() ?: return
        val artifact = artifactFromRecord(obj) ?: return
        scope.launch {
            snapshotLock.withLock {
                val current = runStore.readSnapshot(runId) ?: return@withLock
                if (current.artifacts.any { it.path == artifact.path && it.label == artifact.label }) {
                    return@withLock
                }
                runStore.writeSnapshot(
                    current.copy(
                        artifacts = current.artifacts + artifact,
                        updatedAt = Instant.now().toString(),
                    ),
                )
            }
        }
    }

    private suspend fun journalArtifacts(runId: String): List<WorkflowArtifact> {
        val text = journalStore.load(runId) ?: return emptyList()
        return runCatching {
            val buckets = json.parseToJsonElement(text).jsonObject["events"]?.jsonArray
                ?: return@runCatching emptyList()
            buckets.flatMap { bucket ->
                val stored = bucket.jsonArray.getOrNull(1)?.jsonArray ?: return@flatMap emptyList()
                stored.mapNotNull { entry ->
                    val event = entry.jsonObject["event"]?.jsonObject ?: return@mapNotNull null
                    if (event["type"]?.jsonPrimitive?.contentOrNull != "artifact-published") {
                        return@mapNotNull null
                    }
                    (event["artifact"] as? JsonObject)?.let(::artifactFromRecord)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun artifactFromRecord(record: JsonObject): WorkflowArtifact? {
        val id = record["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val version = record["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        val kind = record["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val contentType = record["contentType"]?.jsonPrimitive?.contentOrNull
            ?: when (kind) {
                "markdown" -> "text/markdown"
                "file" -> "application/octet-stream"
                else -> "application/json"
            }
        val path = record["uri"]?.jsonPrimitive?.contentOrNull
            ?: record["sourcePath"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val publishedAt = record["publishedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val createdAt = publishedAt?.let { Instant.ofEpochMilli(it).toString() }
            ?: Instant.now().toString()
        val title = record["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val label = title ?: if (path.isNotBlank()) "$id v$version" else "$id · $kind v$version"
        return WorkflowArtifact(
            contentType = contentType,
            createdAt = createdAt,
            label = label,
            path = path,
            phase = record["siteId"]?.jsonPrimitive?.contentOrNull,
        )
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
                seedJson = obj["seedJson"]?.jsonPrimitive?.contentOrNull,
            )
        }.getOrNull()

    companion object {
        const val KIND = "dynamic"
        const val ASSET_URL = "file:///android_asset/dwf/runner.html"
        const val READY_TIMEOUT_MS = 20_000L
        private val PRESET_KINDS = setOf("chart", "table", "metrics", "board")
    }
}
