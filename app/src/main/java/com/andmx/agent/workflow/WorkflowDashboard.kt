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
    data class ChartSeries(val label: String, val unit: String?, val points: List<Pair<String, Double>>)

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

    fun chart(spec: JsonObject, items: List<JsonObject>): List<ChartSeries> {
        val x = spec["x"]?.jsonObject?.let { Field(it["field"]?.stringOrNull().orEmpty(), it["label"]?.stringOrNull().orEmpty()) }
            ?: return emptyList()
        val ySpecs = when (val y = spec["y"]) {
            null -> return emptyList()
            is JsonArray -> y.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(y)
            else -> return emptyList()
        }
        return ySpecs.mapNotNull { ySpec ->
            val field = ySpec["field"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = ySpec["label"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: field
            val unit = ySpec["unit"]?.stringOrNull()
            val points = items.mapNotNull { item ->
                val yValue = cell(item, field).toDoubleOrNull() ?: return@mapNotNull null
                cell(item, x.field) to yValue
            }
            if (points.isEmpty()) null else ChartSeries(label, unit, points)
        }
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
