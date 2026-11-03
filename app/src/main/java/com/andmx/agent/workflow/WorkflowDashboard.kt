package com.andmx.agent.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure projection of `report()` rows into the preset artifacts the run declared. */
object WorkflowDashboard {
    private val json = Json { ignoreUnknownKeys = true }

    data class Field(val field: String, val label: String, val unit: String? = null)
    data class Table(val columns: List<Field>, val rows: List<List<String>>)
    data class Metric(val label: String, val value: String, val unit: String? = null)
    data class BoardColumn(val name: String, val cards: List<String>)
    data class ChartPoint(val index: Int, val x: String, val y: Double)
    data class ChartSeries(val label: String, val unit: String?, val points: List<ChartPoint>)
    data class Chart(
        val type: String,
        val scale: String,
        val baseline: Double?,
        val xLabels: List<String>,
        val series: List<ChartSeries>,
    )

    fun parseSpec(specJson: String): JsonObject? =
        runCatching { json.parseToJsonElement(specJson).jsonObject }.getOrNull()

    fun parseItems(items: List<String>): List<JsonObject> =
        items.mapNotNull { text -> runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() }

    fun table(spec: JsonObject, items: List<JsonObject>): Table? {
        val columns = fields(spec["columns"]) ?: return null
        val key = spec["key"]?.stringOrNull()
        val rows = LinkedHashMap<String, List<String>>()
        items.forEachIndexed { index, item ->
            val row = columns.map { cell(item, it.field) }
            val rowKey = key?.let { cell(item, it) }?.takeIf { it.isNotBlank() } ?: "#$index"
            rows[rowKey] = row
        }
        return Table(columns, rows.values.toList())
    }

    fun metrics(spec: JsonObject, items: List<JsonObject>): List<Metric> {
        val fields = fields(spec["metrics"]) ?: return emptyList()
        return fields.mapNotNull { field ->
            val item = items.lastOrNull { it[field.field] != null } ?: return@mapNotNull null
            Metric(field.label, cell(item, field.field), field.unit)
        }
    }

    fun board(spec: JsonObject, items: List<JsonObject>): List<BoardColumn> {
        val columns = spec["columns"]?.let { element ->
            runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        }.orEmpty()
        val key = spec["key"]?.stringOrNull() ?: return emptyList()
        val statusField = spec["status"]?.stringOrNull() ?: return emptyList()
        val titleField = spec["cardTitle"]?.stringOrNull()
        val cards = LinkedHashMap<String, Pair<String, String>>()
        items.forEachIndexed { index, item ->
            val cardKey = cell(item, key).ifBlank { "#$index" }
            val status = cell(item, statusField).ifBlank { "other" }
            val title = titleField?.let { cell(item, it) }?.takeIf { it.isNotBlank() } ?: cardKey
            cards[cardKey] = status to title
        }
        val ordered = LinkedHashMap<String, MutableList<String>>()
        columns.forEach { ordered[it] = mutableListOf() }
        cards.values.forEach { (status, title) ->
            ordered.getOrPut(status) { mutableListOf() }.add(title)
        }
        return ordered.map { (name, values) -> BoardColumn(name, values) }
    }

    fun chart(spec: JsonObject, items: List<JsonObject>): Chart? {
        val x = spec["x"]?.jsonObject?.let {
            Field(it["field"]?.stringOrNull().orEmpty(), it["label"]?.stringOrNull().orEmpty())
        } ?: return null
        val ySpecs = when (val y = spec["y"]) {
            null -> return null
            is JsonArray -> y.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(y)
            else -> return null
        }
        val series = ySpecs.mapNotNull { ySpec ->
            val field = ySpec["field"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = ySpec["label"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: field
            val unit = ySpec["unit"]?.stringOrNull()
            val points = items.mapIndexedNotNull { index, item ->
                val yValue = cell(item, field).toDoubleOrNull() ?: return@mapIndexedNotNull null
                ChartPoint(index, cell(item, x.field), yValue)
            }
            if (points.isEmpty()) null else ChartSeries(label, unit, points)
        }
        if (series.isEmpty()) return null
        val type = spec["type"]?.stringOrNull()?.takeIf { it in setOf("line", "bar", "scatter") } ?: "line"
        val scale = spec["scale"]?.stringOrNull()?.takeIf { it == "log" } ?: "linear"
        val baselineField = (spec["baseline"] as? JsonObject)?.get("field")?.stringOrNull()
        val baseline = baselineField?.let { field ->
            items.firstNotNullOfOrNull { item -> cell(item, field).toDoubleOrNull() }
        }
        return Chart(
            type = type,
            scale = scale,
            baseline = baseline,
            xLabels = items.map { cell(it, x.field) },
            series = series,
        )
    }

    private fun fields(element: JsonElement?): List<Field>? {
        val array = element as? JsonArray ?: return null
        return array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val field = obj["field"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Field(
                field = field,
                label = obj["label"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: field,
                unit = obj["unit"]?.stringOrNull(),
            )
        }
    }

    private fun cell(item: JsonObject, field: String): String {
        val value = item[field] ?: return ""
        return when (value) {
            is JsonPrimitive -> value.content
            else -> value.toString()
        }
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
