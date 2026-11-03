package com.andmx.agent.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowDashboardTest {

    private fun spec(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun items(vararg texts: String) =
        texts.map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun tableReplacesRowsByKey() {
        val table = WorkflowDashboard.table(
            spec("""{"key":"id","columns":[{"field":"id","label":"ID"},{"field":"value","label":"Value"}]}"""),
            items(
                """{"id":"a","value":1}""",
                """{"id":"b","value":2}""",
                """{"id":"a","value":3}""",
            ),
        )!!

        assertEquals(listOf("ID", "Value"), table.columns.map { it.label })
        assertEquals(listOf(listOf("a", "3"), listOf("b", "2")), table.rows)
    }

    @Test
    fun metricsUseNewestValue() {
        val metrics = WorkflowDashboard.metrics(
            spec("""{"metrics":[{"field":"ms","label":"Latency","unit":"ms"}]}"""),
            items("""{"ms":10}""", """{"ms":42}"""),
        )

        assertEquals(1, metrics.size)
        assertEquals("Latency", metrics[0].label)
        assertEquals("42", metrics[0].value)
        assertEquals("ms", metrics[0].unit)
    }

    @Test
    fun boardGroupsCardsByStatusAndKeepsColumnOrder() {
        val board = WorkflowDashboard.board(
            spec(
                """
                {"key":"id","status":"state","cardTitle":"title","columns":["todo","doing","done"]}
                """.trimIndent(),
            ),
            items(
                """{"id":"a","state":"todo","title":"A"}""",
                """{"id":"b","state":"doing","title":"B"}""",
                """{"id":"a","state":"done","title":"A2"}""",
            ),
        )

        assertEquals(listOf("todo", "doing", "done"), board.map { it.name })
        assertEquals(emptyList<String>(), board[0].cards)
        assertEquals(listOf("B"), board[1].cards)
        assertEquals(listOf("A2"), board[2].cards)
    }

    @Test
    fun chartProjectsNumericPoints() {
        val chart = WorkflowDashboard.chart(
            spec(
                """
                {"type":"bar","scale":"linear","x":{"field":"label"},
                 "y":[{"field":"v","label":"Value","unit":"ms"}],"baseline":{"field":"base"}}
                """.trimIndent(),
            ),
            items(
                """{"label":"a","v":1,"base":0}""",
                """{"label":"b","v":2,"base":0}""",
                """{"label":"c","v":"x","base":0}""",
            ),
        )!!

        assertEquals("bar", chart.type)
        assertEquals("linear", chart.scale)
        assertEquals(0.0, chart.baseline)
        assertEquals(listOf("a", "b", "c"), chart.xLabels)
        assertEquals("Value", chart.series[0].label)
        assertEquals(listOf(0 to 1.0, 1 to 2.0), chart.series[0].points.map { it.index to it.y })
    }
}
