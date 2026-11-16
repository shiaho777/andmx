package com.andmx.agent.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun boardLanesKeepCardKeysAndStatuses() {
        val lanes = WorkflowDashboard.boardLanes(
            spec(
                """
                {"key":"id","status":"state","cardTitle":"title","columns":["pending","running","done"]}
                """.trimIndent(),
            ),
            items(
                """{"id":"a","state":"pending","title":"A"}""",
                """{"id":"b","state":"running","title":"B"}""",
                """{"id":"c","state":"done","title":"C"}""",
                """{"id":"b","state":"done","title":"B2"}""",
            ),
        )

        assertEquals(listOf("pending", "running", "done"), lanes.map { it.name })
        assertEquals(listOf("A"), lanes[0].cards.map { it.title })
        assertEquals(emptyList<String>(), lanes[1].cards.map { it.title })
        assertEquals(listOf("B2", "C"), lanes[2].cards.map { it.title })
        assertEquals(listOf("b", "c"), lanes[2].cards.map { it.key })
        assertTrue(lanes[2].cards.all { it.status == "done" })
    }

    @Test
    fun boardLanesAppendUnknownStatusAfterDeclaredColumns() {
        val lanes = WorkflowDashboard.boardLanes(
            spec("""{"key":"id","status":"state","columns":["pending"]}"""),
            items(
                """{"id":"a","state":"pending"}""",
                """{"id":"b","state":"blocked"}""",
                """{"id":"c"}""",
            ),
        )

        assertEquals(listOf("pending", "blocked", "other"), lanes.map { it.name })
        assertEquals(listOf("b"), lanes[1].cards.map { it.key })
        assertEquals(listOf("c"), lanes[2].cards.map { it.title })
    }

    @Test
    fun boardLanesRequireKeyAndStatus() {
        assertEquals(
            emptyList<WorkflowDashboard.BoardLane>(),
            WorkflowDashboard.boardLanes(spec("""{"status":"state"}"""), items("""{"state":"x"}""")),
        )
        assertEquals(
            emptyList<WorkflowDashboard.BoardLane>(),
            WorkflowDashboard.boardLanes(spec("""{"key":"id"}"""), items("""{"id":"a"}""")),
        )
    }

    @Test
    fun boardProjectionMatchesLanes() {
        val spec = spec(
            """{"key":"id","status":"state","cardTitle":"title","columns":["todo","done"]}""",
        )
        val items = items(
            """{"id":"a","state":"todo","title":"A"}""",
            """{"id":"b","state":"done","title":"B"}""",
        )
        val lanes = WorkflowDashboard.boardLanes(spec, items)
        val columns = WorkflowDashboard.board(spec, items)
        assertEquals(lanes.map { it.name }, columns.map { it.name })
        assertEquals(lanes.map { l -> l.cards.map { it.title } }, columns.map { it.cards })
    }

    @Test
    fun statusToneClassifiesCommonStates() {
        assertEquals("done", WorkflowDashboard.statusTone("DONE"))
        assertEquals("done", WorkflowDashboard.statusTone(" completed "))
        assertEquals("running", WorkflowDashboard.statusTone("in_progress"))
        assertEquals("running", WorkflowDashboard.statusTone("Doing"))
        assertEquals("failed", WorkflowDashboard.statusTone("error"))
        assertEquals("none", WorkflowDashboard.statusTone("pending"))
        assertEquals("none", WorkflowDashboard.statusTone(""))
        assertEquals("none", WorkflowDashboard.statusTone("banana"))
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
