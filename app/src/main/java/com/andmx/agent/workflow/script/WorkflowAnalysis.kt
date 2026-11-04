package com.andmx.agent.workflow.script

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 上游 `AnalyzeResult` 在 Kotlin 侧的形状。
 *
 * ## 这里为什么是「解码器」而不是「移植的分析器」
 *
 * 上游的分析层（`packages/dynamic-workflow/src/analysis`，13.6k 行 / 57 文件）建立在
 * **TypeScript 编译器**之上：`ts.createProgram` 建程序、`getSyntacticDiagnostics` /
 * `getSemanticDiagnostics` 出诊断、然后走语法树做站点表、污染不动点与时序走查。
 * 用 Kotlin 重写它等于重写一个 TypeScript 类型检查器——写出来的一定不是同一个东西，
 * 诊断码、文案、图节点都会漂。
 *
 * 所以选择让**上游那份代码自己跑**：它被打成一个 IIFE 放进 assets，在 WebView 里执行
 * （见 `tools/dwf-analyzer`）。本文件只负责把它的输出解回 Kotlin 类型。
 *
 * ## 解码边界（刻意划的，不是遗漏）
 *
 * - **完整解码**：`diagnostics` / `declaredArtifacts` / `modelReferences` / `graph`。
 *   这四个是工具层真正消费的（上游 `create-workflow` / `amend-workflow` /
 *   `save-workflow` / `fill-workflow-hole` / `workflow-script-models` 只读这几个）。
 * - **原样保留**：`causality` / `flow` / `handoff` / `core` 留在 [WorkflowAnalysis.raw]
 *   里，一个字节没丢。它们的类型面分别是 174 / 552 / 252 行，而当前没有任何消费方
 *   （上游这些只喂工作流看板渲染）。等看板真的要做时再补解码器——那时也只需要读
 *   `raw`，不必重跑分析。
 *
 * 换句话说：分析本身是逐字的，Kotlin 侧只省了「没人读的字段的反序列化」。
 */
data class WorkflowAnalysis(
    /** 上游 `ok`：可提交。任何一条诊断都清掉它。 */
    val ok: Boolean,
    val diagnostics: List<CompileDiagnostic>,
    val declaredArtifacts: List<DeclaredArtifact>,
    val modelReferences: List<ModelReference>,
    /** 站点图：facade 调用点之间的 may-flow 有向图。上游 `SiteNode` 全集在此。 */
    val siteGraph: SiteGraph?,
    /** 上游结果的完整 JSON（已按设计摘掉 `core`）。图的其余投影都在这里。 */
    val raw: JsonObject,
) {
    /** 只关心「能不能提交」时用这个（对应上游 `compileWorkflowScript` 的答案）。 */
    val compileFailed: Boolean get() = !ok

    /** 只有 `FANOUT_ACTOR_NAME` 这一条**不扣下图**：它描述的是运行期撞名，不是脚本不可分析。 */
    val withholdsGraphs: Boolean
        get() = diagnostics.any { it.code != WorkflowDiagnosticCode.FANOUT_ACTOR_NAME }

    fun hasCode(code: Int): Boolean = diagnostics.any { it.code == code }

    companion object {
        val EMPTY = WorkflowAnalysis(
            ok = false,
            diagnostics = emptyList(),
            declaredArtifacts = emptyList(),
            modelReferences = emptyList(),
            siteGraph = null,
            raw = JsonObject(emptyMap()),
        )
    }
}

/** 上游 `CompileDiagnostic`。`code` 是 TypeScript 诊断码，9001..9012 是本特性自己的规则。 */
data class CompileDiagnostic(
    val code: Int,
    val line: Int,
    val column: Int,
    val message: String,
) {
    /** 这条诊断来自工作流自己的规则（9001..9012），而不是 TypeScript 的类型检查。 */
    val isAuthoringRule: Boolean
        get() = code in WorkflowDiagnosticCode.AUTHORING_RULE_RANGE

    /** `line:column: message`，与上游向模型回报时用的形状一致。 */
    fun render(): String = "$line:$column: $message"
}

/** 上游 `DeclaredArtifact`：脚本声明的用户面产物。`kind` 是 `ARTIFACT_REGISTRY` 的成员。 */
data class DeclaredArtifact(val id: String, val kind: String)

/** 上游 `ModelReference`：脚本点名的模型。`name` 逐字如脚本所写，运行期 persona 里就是这串。 */
data class ModelReference(val name: String, val line: Int, val column: Int)

/** 上游 `NamePattern`：名字只在运行期成形时，静态能拿到的那部分形状。`head`/`tail` 至少一个在场。 */
data class NamePattern(val head: String?, val tail: String?)

/** 上游 `SiteKind`。留了原串，遇到新成员时不会把信息吃掉。 */
enum class SiteKind(val wire: String) {
    ASK("ask"),
    WORLD_READ("world-read"),
    JOIN("join"),
    FAN_OUT("fan-out"),
    HOLE("hole"),
    SOURCE("source"),
    SINK("sink"),
    ;

    companion object {
        fun from(wire: String): SiteKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** 上游 `SiteGraph`：站点表的 may-flow 有向图，`source`/`sink` 是两个虚拟端点。 */
data class SiteGraph(
    val nodes: List<SiteNode>,
    val actors: List<ActorSite>,
    val edges: List<SiteEdge>,
)

/**
 * 上游 `SiteNode`。虚拟端点（`source`/`sink`）没有位置，标签固定。
 * `actors` 只在 `ask` 节点上有：该 ask 的接收者可能落到的 actor 站点集合。
 * `within` 是最近的、被提升的 fan-out 节点 id——该站点随元素重复执行。
 */
data class SiteNode(
    val id: String,
    val kind: String,
    val loc: ScriptLoc?,
    val label: String,
    val labelPattern: NamePattern?,
    val actors: List<String>,
    val within: String?,
    val artifactType: String?,
) {
    val kindOrNull: SiteKind? get() = SiteKind.from(kind)
}

/** 上游 `ActorSite`：一个 `agent()` 站点。画成车道，不是图节点。 */
data class ActorSite(
    val id: String,
    val loc: ScriptLoc,
    val name: String?,
    val namePattern: NamePattern?,
    val within: String?,
)

/** 上游 `SiteEdge`。`data` 边载产物，`context` 边关联共享同一 actor 的 ask。 */
data class SiteEdge(
    val from: String,
    val to: String,
    val kind: String,
    /** 一旦宽化规则沿见证路径触发过就是 false。 */
    val exact: Boolean,
    /** join 入参 / 元素位置（当 join 实参是静态数组字面量时）。 */
    val port: Int?,
    /** 产出者产物的类型名；`context` 边与不可知时缺席。 */
    val type: String?,
)

/** 脚本里 1-based、已剥掉前导的位置。上游 `ScriptLoc`。 */
data class ScriptLoc(val line: Int, val column: Int)

/**
 * 上游自定义规则的诊断码。
 *
 * 这些不是 TypeScript 的码：它们是工作流自己的「运行期才炸不如现在就教改写」那一类。
 */
object WorkflowDiagnosticCode {
    /** 9001：facade 可调用对象逃进值空间，没有站点。 */
    const val FACADE_SITING = 9001

    /** 9002：schema 诊断。 */
    const val SCHEMA = 9002

    /** 9003：`world.run` 的 `cmd` 必须是字面量（确认窗展示的命令集在编译期闭合）。 */
    const val WORLD_RUN_LITERAL = 9003

    /** 9004：phase 标记必须是语句位置上的字面量名字。 */
    const val PHASE_MARKER = 9004

    /** 9005：字面量 actor 重名的正门在运行期，这一趟把看得穿的部分提前。 */
    const val DUPLICATE_ACTOR_NAME = 9005

    /** 9006：fan-out 里的静态 actor 名。**唯一不扣下图**的一条。 */
    const val FANOUT_ACTOR_NAME = 9006

    /** 9007：产物 id 必须是编译期字面量。 */
    const val ARTIFACT_DECLARATION = 9007

    /** 9008：产物声明被提升后位置不合法。 */
    const val ARTIFACT_HOISTING = 9008

    /** 9009：同一 id 不横跨两种成员。 */
    const val ARTIFACT_PRIMARY_CONFLICT = 9009

    /** 9010：子代理模型名的集合必须封闭。 */
    const val MODEL_REFERENCE = 9010

    /** 9011：脚本点名的模型在**本机**模型目录里解不出来。改的是脚本，不是参数。 */
    const val MODEL_UNRESOLVED = 9011

    /** 9012：留白（`hole`）的编译期规则。 */
    const val HOLE = 9012

    /** TS1184「修饰符不能出现在这里」——脚本体被编译进函数体，所以顶层 `declare`/`export` 都错。 */
    const val MISPLACED_MODIFIER = 1184

    val AUTHORING_RULE_RANGE = 9001..9012
}

/** 把上游 `AnalyzeResult` 的 JSON 解回 Kotlin。字段名逐字对应，不做重命名。 */
object WorkflowAnalysisCodec {

    fun decode(root: JsonObject): WorkflowAnalysis = WorkflowAnalysis(
        ok = root.bool("ok") ?: false,
        diagnostics = root.objects("diagnostics").map(::decodeDiagnostic),
        declaredArtifacts = root.objects("declaredArtifacts").map { a ->
            DeclaredArtifact(id = a.string("id").orEmpty(), kind = a.string("kind").orEmpty())
        },
        modelReferences = root.objects("modelReferences").map { m ->
            ModelReference(
                name = m.string("name").orEmpty(),
                line = m.int("line") ?: 0,
                column = m.int("column") ?: 0,
            )
        },
        siteGraph = root.object_("graph")?.let(::decodeSiteGraph),
        raw = root,
    )

    /** `compile` 档只有两个字段，不必走整条 decode。 */
    fun decodeCompileOutcome(root: JsonObject): CompileOutcome = CompileOutcome(
        ok = root.bool("ok") ?: false,
        diagnostics = root.objects("diagnostics").map(::decodeDiagnostic),
    )

    fun decodeSnippet(root: JsonObject): SnippetPreparation = SnippetPreparation(
        ok = root.bool("ok") ?: false,
        diagnostics = root.objects("diagnostics").map(::decodeDiagnostic),
        lowered = root.string("lowered"),
    )

    private fun decodeDiagnostic(d: JsonObject): CompileDiagnostic = CompileDiagnostic(
        code = d.int("code") ?: 0,
        line = d.int("line") ?: 0,
        column = d.int("column") ?: 0,
        message = d.string("message").orEmpty(),
    )

    private fun decodeSiteGraph(graph: JsonObject): SiteGraph = SiteGraph(
        nodes = graph.objects("nodes").map { n ->
            SiteNode(
                id = n.string("id").orEmpty(),
                kind = n.string("kind").orEmpty(),
                loc = n.object_("loc")?.let(::decodeLoc),
                label = n.string("label").orEmpty(),
                labelPattern = n.object_("labelPattern")?.let(::decodePattern),
                actors = n.strings("actors"),
                within = n.string("within"),
                artifactType = n.string("artifactType"),
            )
        },
        actors = graph.objects("actors").map { a ->
            ActorSite(
                id = a.string("id").orEmpty(),
                // ActorSite.loc 在上游是必填的，缺了当 1:1 处理，绝不抛——图残缺好过没有图。
                loc = a.object_("loc")?.let(::decodeLoc) ?: ScriptLoc(1, 1),
                name = a.string("name"),
                namePattern = a.object_("namePattern")?.let(::decodePattern),
                within = a.string("within"),
            )
        },
        edges = graph.objects("edges").map { e ->
            SiteEdge(
                from = e.string("from").orEmpty(),
                to = e.string("to").orEmpty(),
                kind = e.string("kind").orEmpty(),
                // 上游 `exact` 是必填布尔；缺省按 false 处理，是宽化方向，不会假装精确。
                exact = e.bool("exact") ?: false,
                port = e.int("port"),
                type = e.string("type"),
            )
        },
    )

    private fun decodeLoc(loc: JsonObject): ScriptLoc =
        ScriptLoc(line = loc.int("line") ?: 0, column = loc.int("column") ?: 0)

    private fun decodePattern(pattern: JsonObject): NamePattern =
        NamePattern(head = pattern.string("head"), tail = pattern.string("tail"))
}

// —— kotlinx.serialization 的小读法：缺字段一律给 null，绝不抛 ——

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

/** 不用 `object` 这个名字（Kotlin 关键字），叫 `object_`。 */
private fun JsonObject.object_(key: String): JsonObject? =
    (this[key] as? JsonElement)?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonElement)?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        .orEmpty()

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonElement)?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        .orEmpty()
