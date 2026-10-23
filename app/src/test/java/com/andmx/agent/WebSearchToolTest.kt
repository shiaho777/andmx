package com.andmx.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.permission.CollaborationMode
import com.andmx.agent.permission.PermissionContext
import com.andmx.agent.permission.PermissionService
import com.andmx.agent.permission.PermissionToolCapability
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.agent.zcode.buildZCodeToolSurface
import com.andmx.exec.policy.NetworkPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WebSearchToolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun surface(legacy: Boolean = true): List<Tool> = buildZCodeToolSurface(
        context = context,
        networkPolicy = NetworkPolicy(),
        planTool = UpdatePlanTool(),
        goalState = GoalToolState(),
        todo = TodoState(),
        planMode = PlanModeState(),
        cwdProvider = { "/root" },
        includeLegacyAliases = legacy,
    )

    @Test
    fun schemaCapabilitiesAndSingleRegistration() {
        val now = Calendar.getInstance()
        val month = MONTHS[now.get(Calendar.MONTH)]
        val year = now.get(Calendar.YEAR)
        for (legacy in listOf(true, false)) {
            val tools = surface(legacy)
            assertEquals(1, tools.count { it.name == "WebSearch" })
            assertEquals(1, tools.count { it is WebSearchTool })
        }
        val legacy = surface(true)
        val direct = surface(false)
        assertTrue(legacy.any { it.name == "web_search" && it is LegacyWebSearchTool })
        assertTrue(direct.none { it.name == "web_search" })
        val search = legacy.single { it.name == "WebSearch" }
        assertTrue(search is WebSearchTool)
        assertEquals(description(month, year), search.description)
        assertTrue(search.description.contains("Search the web. Returns result blocks with titles and URLs. US-only."))
        assertTrue(search.description.contains("$month $year"))
        assertTrue(search.description.contains("`allowed_domains` / `blocked_domains` filter results."))
        assertTrue(search.description.contains("Sources:"))
        assertFalse(search.description.contains("provider-native", ignoreCase = true))
        assertFalse(search.description.contains("uses provider-native web search", ignoreCase = true))
        assertEquals(listOf("query"), required(search))
        assertEquals(setOf("query", "allowed_domains", "blocked_domains"), props(search).keys)
        assertEquals(false, search.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(
            "https://json-schema.org/draft/2020-12/schema",
            search.parameters["\$schema"]!!.jsonPrimitive.content,
        )
        assertEquals("string", props(search)["query"]!!.jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("2", props(search)["query"]!!.jsonObject.getValue("minLength").jsonPrimitive.content)
        assertEquals(
            "The search query to use",
            props(search)["query"]!!.jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "Only include search results from these domains",
            props(search)["allowed_domains"]!!.jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "Never include search results from these domains",
            props(search)["blocked_domains"]!!.jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "string",
            props(search)["allowed_domains"]!!.jsonObject.getValue("items").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "string",
            props(search)["blocked_domains"]!!.jsonObject.getValue("items").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertTrue(search.readOnly)
        assertFalse(search.destructive)
        assertTrue(search.concurrentSafe)
        assertFalse(search.needsApproval)
        assertEquals("network", search.sideEffectScope)
        assertEquals("low", search.riskLevel)
        assertEquals("websearch", search.permissionName)
        assertEquals(60_000L, search.timeoutMs)
        assertEquals(20_000, search.maxOutputBytes)
        val old = legacy.single { it.name == "web_search" }
        assertEquals("用 DuckDuckGo 联网搜索,返回前若干条结果(标题/链接/摘要)。", old.description)
        assertEquals(45_000L, old.timeoutMs)
    }

    @Test
    fun descriptionReadsTheClockAndDoesNotClaimProviderNativeSearch() {
        val clock = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2031)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 15)
        }
        val tool = WebSearchTool(now = { clock })
        val text = tool.description
        assertEquals(description("January", 2031), text)
        assertTrue(text.contains("January 2031"))
        assertTrue(text.contains("Search the web. Returns result blocks with titles and URLs. US-only."))
        assertTrue(text.contains("`allowed_domains` / `blocked_domains` filter results."))
        assertTrue(text.contains("Sources:"))
        assertFalse(text.contains("provider-native", ignoreCase = true))
        assertFalse(text.contains("the call uses provider-native web search", ignoreCase = true))
    }

    @Test
    fun fakeHttpResponseMatchesDirectResultText() = runTest {
        val html = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=x">Example <b>Title</b></a>
              <a class="result__snippet" href="#">A nice <b>snippet</b> here.</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://docs.example.com/guide">Docs Guide</a>
              <a class="result__snippet" href="#">Second snippet text</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://EXAMPLE.com/page">Duplicate Title</a>
              <a class="result__snippet" href="#">dup snippet</a>
            </div>
        """.trimIndent()
        val client = FakeClient(html)
        val tool = WebSearchTool(client = client)
        val result = tool.execute(args("kotlin android"))
        assertFalse(result.isError)
        assertEquals(directText("kotlin android"), result.output)
        assertFalse(result.output.contains("snippet"))
        assertFalse(result.output.contains("Duplicate"))
        assertEquals(
            listOf("https://html.duckduckgo.com/html/?q=kotlin+android"),
            client.urls,
        )
    }

    @Test
    fun emptyHtmlUsesNoLinksText() = runTest {
        val client = FakeClient("<html></html>")
        val result = WebSearchTool(client = client).execute(args("zz"))
        assertFalse(result.isError)
        assertEquals(
            listOf(
                "Web search results for query: \"zz\"",
                "",
                "Links:",
                "- No links found.",
                "",
                "REMINDER: You MUST include the sources above in your response to the user using markdown hyperlinks.",
            ).joinToString("\n"),
            result.output,
        )
        assertEquals(listOf("https://html.duckduckgo.com/html/?q=zz"), client.urls)
    }

    @Test
    fun linksStopAtTwenty() = runTest {
        val html = buildString {
            for (i in 1..21) {
                append("""<a class="result__a" href="https://example.com/p$i">Title $i</a>""")
            }
        }
        val result = WebSearchTool(client = FakeClient(html)).execute(args("many results"))
        assertFalse(result.isError)
        assertTrue(result.output.contains("- [Title 1](https://example.com/p1)"))
        assertTrue(result.output.contains("- [Title 20](https://example.com/p20)"))
        assertFalse(result.output.contains("p21"))
        assertFalse(result.output.contains("Title 21"))
    }

    @Test
    fun allowedDomainsFilterTheQueryAndTheResults() = runTest {
        val html = """
            <a class="result__a" href="https://example.com/keep">Keep</a>
            <a class="result__a" href="https://docs.example.com/also">Also</a>
            <a class="result__a" href="https://other.test/drop">Drop</a>
        """.trimIndent()
        val client = FakeClient(html)
        val result = WebSearchTool(client = client).execute(
            args("kotlin android", allowed = listOf("example.com")),
        )
        assertFalse(result.isError)
        assertEquals(
            listOf(
                "Web search results for query: \"kotlin android\"",
                "",
                "Links:",
                "- [Keep](https://example.com/keep)",
                "- [Also](https://docs.example.com/also)",
                "",
                "REMINDER: You MUST include the sources above in your response to the user using markdown hyperlinks.",
            ).joinToString("\n"),
            result.output,
        )
        assertEquals(
            listOf("https://html.duckduckgo.com/html/?q=kotlin+android+site%3Aexample.com"),
            client.urls,
        )
    }

    @Test
    fun blockedDomainsFilterTheQueryAndTheResults() = runTest {
        val html = """
            <a class="result__a" href="https://example.com/keep">Keep</a>
            <a class="result__a" href="https://docs.example.com/drop">Drop</a>
            <a class="result__a" href="https://notexample.com/stay">Stay</a>
        """.trimIndent()
        val client = FakeClient(html)
        val result = WebSearchTool(client = client).execute(
            args("kotlin android", blocked = listOf("docs.example.com")),
        )
        assertFalse(result.isError)
        assertTrue(result.output.contains("- [Keep](https://example.com/keep)"))
        assertTrue(result.output.contains("- [Stay](https://notexample.com/stay)"))
        assertFalse(result.output.contains("docs.example.com"))
        assertEquals(
            listOf("https://html.duckduckgo.com/html/?q=kotlin+android+-site%3Adocs.example.com"),
            client.urls,
        )
    }

    @Test
    fun bothDomainListsAreRejectedWithoutFetch() = runTest {
        val client = FakeClient("<html></html>")
        val result = WebSearchTool(client = client).execute(
            args("kotlin android", allowed = listOf("example.com"), blocked = listOf("evil.test")),
        )
        assertTrue(result.isError)
        assertEquals("allowed_domains and blocked_domains cannot both be specified", result.output)
        assertTrue(client.urls.isEmpty())
    }

    @Test
    fun missingOrShortQueryDoesNotFetch() = runTest {
        val client = FakeClient("<html></html>")
        val tool = WebSearchTool(client = client)
        val missing = tool.execute(buildJsonObject {})
        val short = tool.execute(args("a"))
        assertTrue(missing.isError)
        assertEquals("query is required", missing.output)
        assertTrue(short.isError)
        assertEquals("query must be at least 2 characters", short.output)
        assertTrue(client.urls.isEmpty())
    }

    @Test
    fun deniedHostDoesNotFetch() = runTest {
        val client = FakeClient("<html></html>")
        val policy = NetworkPolicy(
            listOf(NetworkPolicy.NetworkRule("html.duckduckgo.com", NetworkPolicy.NetworkAction.DENY)),
        )
        val result = WebSearchTool(networkPolicy = policy, client = client).execute(args("kotlin android"))
        assertTrue(result.isError)
        assertEquals("Access to html.duckduckgo.com is denied by network policy", result.output)
        assertTrue(client.urls.isEmpty())
    }

    @Test
    fun readOnlyNetworkSearchIsAllowedInBuild() {
        val search = surface(false).single { it.name == "WebSearch" }
        val decision = PermissionService().checkPermission(
            PermissionContext(toolName = "WebSearch", mode = CollaborationMode.BUILD, riskLevel = search.riskLevel),
            PermissionToolCapability(
                readOnly = search.readOnly,
                destructive = search.destructive,
                needsApproval = search.needsApproval,
                riskLevel = search.riskLevel,
                sideEffectScope = search.sideEffectScope,
                permissionName = search.permissionName,
            ),
        )
        assertEquals("allow", decision.decision)
        assertEquals("mode.build.readOnly", decision.ruleId)
        assertEquals("network", decision.sideEffectScope)
        assertEquals("low", decision.riskLevel)
    }

    private fun args(
        query: String,
        allowed: List<String>? = null,
        blocked: List<String>? = null,
    ) = buildJsonObject {
        put("query", query)
        if (allowed != null) {
            put("allowed_domains", buildJsonArray { allowed.forEach { add(it) } })
        }
        if (blocked != null) {
            put("blocked_domains", buildJsonArray { blocked.forEach { add(it) } })
        }
    }

    private fun props(tool: Tool): JsonObject = tool.parameters["properties"]!!.jsonObject

    private fun required(tool: Tool): List<String> =
        tool.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun directText(query: String) = listOf(
        "Web search results for query: \"$query\"",
        "",
        "Links:",
        "- [Example Title](https://example.com/page)",
        "- [Docs Guide](https://docs.example.com/guide)",
        "",
        "REMINDER: You MUST include the sources above in your response to the user using markdown hyperlinks.",
    ).joinToString("\n")

    private fun description(month: String, year: Int) = listOf(
        "Search the web. Returns result blocks with titles and URLs. US-only.",
        "",
        "- The current month is $month $year — use this when searching for recent information.",
        "- `allowed_domains` / `blocked_domains` filter results.",
        "- After answering from results, end with a \"Sources:\" list of the URLs you used as markdown links.",
    ).joinToString("\n")

    private class FakeClient(private val html: String) : WebSearchClient {
        val urls = mutableListOf<String>()
        override fun fetch(url: String): String {
            urls += url
            return html
        }
    }

    private companion object {
        val MONTHS = listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )
    }
}
