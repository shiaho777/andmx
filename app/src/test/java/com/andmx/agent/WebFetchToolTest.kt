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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WebFetchToolTest {
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
        val description = listOf(
            "Fetches a URL, converts the page to markdown, and answers `prompt` against it using a small fast model.",
            "",
            "- Fails on authenticated/private URLs — use an authenticated MCP tool or `gh` for those instead.",
            "- HTTP is upgraded to HTTPS. Cross-host redirects are returned to you rather than followed; call again with the redirect URL.",
            "- Responses are cached for 15 minutes per URL.",
        ).joinToString("\n")
        for (legacy in listOf(true, false)) {
            val tools = surface(legacy)
            assertEquals(1, tools.count { it.name == "WebFetch" })
            assertEquals(1, tools.count { it is WebFetchTool })
            assertEquals(1, tools.count { it.name == "WebSearch" })
        }
        val legacy = surface(true)
        val direct = surface(false)
        assertTrue(legacy.any { it.name == "browse" })
        assertTrue(direct.none { it.name == "browse" })
        val fetch = legacy.single { it.name == "WebFetch" }
        assertTrue(fetch is WebFetchTool)
        assertEquals(description, fetch.description)
        assertEquals(listOf("url", "prompt"), required(fetch))
        assertEquals(setOf("url", "prompt"), props(fetch).keys)
        assertEquals(false, fetch.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(
            "https://json-schema.org/draft/2020-12/schema",
            fetch.parameters["\$schema"]!!.jsonPrimitive.content,
        )
        assertEquals("uri", props(fetch)["url"]!!.jsonObject.getValue("format").jsonPrimitive.content)
        assertEquals(
            "The URL to fetch content from",
            props(fetch)["url"]!!.jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "The prompt to run on the fetched content",
            props(fetch)["prompt"]!!.jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertTrue(fetch.readOnly)
        assertFalse(fetch.destructive)
        assertTrue(fetch.concurrentSafe)
        assertTrue(fetch.needsApproval)
        assertEquals("network", fetch.sideEffectScope)
        assertEquals("medium", fetch.riskLevel)
        assertEquals("webfetch", fetch.permissionName)
        assertEquals(60_000L, fetch.timeoutMs)
        assertEquals(100_000, fetch.maxOutputBytes)
        val search = legacy.single { it.name == "WebSearch" }
        assertTrue(search is WebSearchTool)
        assertFalse(search is WebFetchTool)
        assertTrue(search.readOnly)
        assertFalse(search.needsApproval)
        assertEquals("network", search.sideEffectScope)
        assertEquals("low", search.riskLevel)
        assertFalse(search.description.contains("provider-native", ignoreCase = true))
    }

    @Test
    fun preapprovedUrlIsAllowedAndOthersAreNot() {
        val fetch = surface(false).single { it.name == "WebFetch" }
        val capability = PermissionToolCapability(
            readOnly = fetch.readOnly,
            destructive = fetch.destructive,
            needsApproval = fetch.needsApproval,
            riskLevel = fetch.riskLevel,
            sideEffectScope = fetch.sideEffectScope,
            permissionName = fetch.permissionName,
        )
        val service = PermissionService()
        val preapproved = service.checkPermission(
            ctx("https://docs.python.org/3/library/pathlib.html"),
            capability,
        )
        assertEquals("allow", preapproved.decision)
        assertEquals("tool.webfetch.preapproved", preapproved.ruleId)
        val prefix = service.checkPermission(ctx("https://vercel.com/docs"), capability)
        assertEquals("allow", prefix.decision)
        assertEquals("tool.webfetch.preapproved", prefix.ruleId)
        val vendor = service.checkPermission(ctx("https://github.com/anthropics/example"), capability)
        assertEquals("ask", vendor.decision)
        assertNotEquals("tool.webfetch.preapproved", vendor.ruleId)
        val encoded = service.checkPermission(ctx("https://vercel.com/docs/%2e%2e/admin"), capability)
        assertNotEquals("tool.webfetch.preapproved", encoded.ruleId)
    }

    @Test
    fun cacheHitSkipsConnectionUntilClockExpires() = runTest {
        var now = 0L
        val client = fixed(ok("hello"))
        val tool = tool(
            client = client,
            cache = WebFetchContentCache { now },
            summarizer = { content, prompt -> "$prompt:$content" },
        )
        val first = tool.execute(args("https://example.com/cache", "First prompt"))
        val second = tool.execute(args("https://example.com/cache", "Second prompt"))
        assertEquals("First prompt:hello", first.output)
        assertEquals("Second prompt:hello", second.output)
        assertEquals(listOf("https://example.com/cache"), client.urls)
        now = 15 * 60 * 1000L - 1
        tool.execute(args("https://example.com/cache", "Still cached"))
        assertEquals(1, client.urls.size)
        now = 15 * 60 * 1000L
        val third = tool.execute(args("https://example.com/cache", "Third prompt"))
        assertEquals(2, client.urls.size)
        assertEquals("Third prompt:hello", third.output)
    }

    @Test
    fun crossHostRedirectTextIsReturnedAndNotFollowed() = runTest {
        val client = fixed(
            WebFetchHttpResponse(
                status = 302,
                statusText = "Found",
                headers = mapOf("Location" to "https://evil.example/final"),
            ),
        )
        val tool = tool(client)
        val result = tool.execute(args("https://example.com/start", "Summarize"))
        val again = tool.execute(args("https://example.com/start", "Summarize"))
        assertFalse(result.isError)
        assertEquals(redirectText("https://example.com/start", "https://evil.example/final", "302 Found", "Summarize"), result.output)
        assertEquals(redirectText("https://example.com/start", "https://evil.example/final", "302 Found", "Summarize"), again.output)
        assertEquals(listOf("https://example.com/start", "https://example.com/start"), client.urls)
    }

    @Test
    fun emptyRedirectStatusUsesReasonPhrase() = runTest {
        val client = fixed(
            WebFetchHttpResponse(
                status = 302,
                statusText = "",
                headers = mapOf("location" to "https://evil.example/final"),
            ),
        )
        val result = tool(client).execute(args("https://example.com/start", "Summarize"))
        assertTrue(result.output.contains("Status: 302 Found"))
        assertEquals(1, client.urls.size)
    }

    @Test
    fun sameHostRedirectIsFollowedAndCrossHostStops() = runTest {
        val client = script { url ->
            when (url) {
                "https://example.com/start" -> WebFetchHttpResponse(
                    status = 302,
                    statusText = "Found",
                    headers = mapOf("Location" to "https://www.example.com/mid"),
                )
                "https://www.example.com/mid" -> WebFetchHttpResponse(
                    status = 302,
                    statusText = "Found",
                    headers = mapOf("Location" to "https://evil.example/final"),
                )
                else -> error("unexpected $url")
            }
        }
        val result = tool(client).execute(args("https://example.com/start", "Summarize"))
        assertEquals(
            redirectText("https://www.example.com/mid", "https://evil.example/final", "302 Found", "Summarize"),
            result.output,
        )
        assertEquals(listOf("https://example.com/start", "https://www.example.com/mid"), client.urls)
    }

    @Test
    fun credentialRedirectIsRedactedAndNotFollowed() = runTest {
        val client = fixed(
            WebFetchHttpResponse(
                status = 302,
                statusText = "Found",
                headers = mapOf("Location" to "https://user:secret@evil.example/final"),
            ),
        )
        val result = tool(client).execute(args("https://example.com/start", "Summarize"))
        assertTrue(result.output.contains("REDIRECT DETECTED"))
        assertTrue(result.output.contains("Redirect URL: https://evil.example/final"))
        assertTrue(result.output.contains("- url: \"https://evil.example/final\""))
        assertFalse(result.output.contains("secret"))
        assertFalse(result.output.contains("user:secret"))
        assertEquals(listOf("https://example.com/start"), client.urls)
    }

    @Test
    fun httpErrorTextOmitsBody() = runTest {
        val client = fixed(
            WebFetchHttpResponse(
                status = 404,
                statusText = "Not Found",
                body = "not found body".toByteArray(),
            ),
        )
        val tool = tool(client)
        val result = tool.execute(args("https://example.com/missing", "Summarize"))
        tool.execute(args("https://example.com/missing", "Summarize"))
        assertFalse(result.isError)
        assertEquals(httpErrorText("404 Not Found"), result.output)
        assertFalse(result.output.contains("not found body"))
        assertEquals(2, client.urls.size)
    }

    @Test
    fun emptyHttpStatusUsesReasonPhrase() = runTest {
        val client = fixed(
            WebFetchHttpResponse(status = 404, statusText = "   ", body = "hidden".toByteArray()),
        )
        val result = tool(client).execute(args("https://example.com/missing"))
        assertEquals(httpErrorText("404 Not Found"), result.output)
        assertFalse(result.output.contains("hidden"))
    }

    @Test
    fun retryAfterIsIncludedOnlyForNumericValues() = runTest {
        val numeric = fixed(
            WebFetchHttpResponse(
                status = 503,
                statusText = "Service Unavailable",
                headers = mapOf("Retry-After" to "120"),
            ),
        )
        val numericResult = tool(numeric).execute(args("https://example.com/slow"))
        assertEquals(
            listOf(
                "The server returned HTTP 503 Service Unavailable.",
                "Retry-After: 120",
                "",
                AUTH_HINT,
            ).joinToString("\n"),
            numericResult.output,
        )
        val ignored = fixed(
            WebFetchHttpResponse(
                status = 503,
                statusText = "Service Unavailable",
                headers = mapOf("Retry-After" to "soon"),
            ),
        )
        val ignoredResult = tool(ignored).execute(args("https://example.com/slow"))
        assertEquals(httpErrorText("503 Service Unavailable"), ignoredResult.output)
        assertFalse(ignoredResult.output.contains("Retry-After"))
    }

    @Test
    fun notModifiedIsAnHttpErrorEvenWithLocation() = runTest {
        val client = fixed(
            WebFetchHttpResponse(
                status = 304,
                statusText = "Not Modified",
                headers = mapOf("Location" to "https://evil.example/final"),
                body = "not modified body".toByteArray(),
            ),
        )
        val result = tool(client).execute(args("https://example.com/start"))
        assertEquals(httpErrorText("304 Not Modified"), result.output)
        assertFalse(result.output.contains("REDIRECT DETECTED"))
        assertFalse(result.output.contains("not modified body"))
        assertEquals(listOf("https://example.com/start"), client.urls)
    }

    @Test
    fun privateDeniedAndEncodedUrlsDoNotFetch() = runTest {
        val privateClient = fixed(ok("nope"))
        val privateResult = tool(privateClient).execute(args("https://127.0.0.1/secret"))
        assertTrue(privateResult.isError)
        assertEquals("WebFetch cannot access private or local IP addresses", privateResult.output)
        assertTrue(privateClient.urls.isEmpty())
        val local = tool(fixed(ok("nope"))).execute(args("https://localhost/x"))
        assertTrue(local.isError)
        assertEquals("WebFetch cannot access private or local hostnames", local.output)
        val deniedClient = fixed(ok("nope"))
        val denied = tool(
            client = deniedClient,
            policy = NetworkPolicy(
                listOf(NetworkPolicy.NetworkRule("example.com", NetworkPolicy.NetworkAction.DENY, "no")),
            ),
        ).execute(args("https://example.com/docs"))
        assertTrue(denied.isError)
        assertEquals("Access to example.com is denied by network policy", denied.output)
        assertTrue(deniedClient.urls.isEmpty())
        for (raw in listOf(
            "https://example.com/%2e%2e/secret",
            "https://example.com/foo%2fbar",
            "https://example.com/foo%5cbar",
            "https://example.com/%252e%252e/secret",
            "http://example.com/%2e%2e/x",
            "https://vercel.com/docs/%2e%2e/admin",
        )) {
            val client = fixed(ok("nope"))
            val result = tool(client).execute(args(raw))
            assertTrue(raw, result.isError)
            assertEquals(raw, "WebFetch rejects encoded dot or slash in the URL", result.output)
            assertTrue(raw, client.urls.isEmpty())
        }
    }

    @Test
    fun httpIsUpgradedBeforeConnectAndCachedByInputUrl() = runTest {
        val client = fixed(ok("page"))
        val tool = tool(client)
        val first = tool.execute(args("http://example.com/docs", "A"))
        val second = tool.execute(args("http://example.com/docs", "B"))
        assertFalse(first.isError)
        assertEquals("page", first.output)
        assertEquals("page", second.output)
        assertEquals(listOf("https://example.com/docs"), client.urls)
    }

    @Test
    fun htmlIsConvertedToMarkdown() = runTest {
        val html = "<h1>Title</h1><p>Hello <a href=\"https://example.com/a\">docs</a></p><script>secret()</script>"
        val client = fixed(ok(html, "text/html; charset=utf-8"))
        val result = tool(client).execute(args("https://example.com/page"))
        assertEquals("# Title\nHello [docs](https://example.com/a)", result.output)
        assertFalse(result.output.contains("secret()"))
    }

    @Test
    fun preapprovedMarkdownSkipsTheSummarizer() = runTest {
        var calls = 0
        val client = fixed(ok("# Doc", "text/markdown"))
        val tool = tool(client) { _, _ ->
            calls += 1
            "summarized"
        }
        val direct = tool.execute(args("https://docs.python.org/3/library/pathlib.html", "Explain"))
        assertEquals("# Doc", direct.output)
        assertEquals(0, calls)
        val processed = tool.execute(args("https://example.com/post", "Explain"))
        assertEquals("summarized", processed.output)
        assertEquals(1, calls)
        assertEquals(2, client.urls.size)
    }

    @Test
    fun missingUrlOrPromptDoesNotFetch() = runTest {
        val client = fixed(ok("nope"))
        val tool = tool(client)
        val neither = tool.execute(buildJsonObject { })
        val urlOnly = tool.execute(buildJsonObject { put("url", "https://example.com/a") })
        assertTrue(neither.isError)
        assertTrue(urlOnly.isError)
        assertEquals("url and prompt are required", neither.output)
        assertEquals("url and prompt are required", urlOnly.output)
        assertTrue(client.urls.isEmpty())
    }

    private fun ctx(url: String) = PermissionContext(
        toolName = "WebFetch",
        input = mapOf("url" to url),
        mode = CollaborationMode.BUILD,
    )

    private fun tool(
        client: ScriptClient,
        cache: WebFetchContentCache = WebFetchContentCache { 0L },
        policy: NetworkPolicy = NetworkPolicy.PERMISSIVE,
        summarizer: (suspend (String, String) -> String?)? = null,
    ) = WebFetchTool(
        networkPolicy = policy,
        cache = cache,
        client = client,
        summarizer = summarizer,
    )

    private fun args(url: String, prompt: String = "Summarize") = buildJsonObject {
        put("url", url)
        put("prompt", prompt)
    }

    private fun script(handler: (String) -> WebFetchHttpResponse) = ScriptClient(handler)

    private fun fixed(response: WebFetchHttpResponse) = ScriptClient { _ -> response }

    private fun ok(body: String, contentType: String = "text/plain") = WebFetchHttpResponse(
        status = 200,
        statusText = "OK",
        headers = mapOf("content-type" to contentType),
        body = body.toByteArray(Charsets.UTF_8),
    )

    private fun props(tool: Tool): JsonObject = tool.parameters["properties"]!!.jsonObject

    private fun required(tool: Tool): List<String> =
        tool.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }

    private class ScriptClient(
        private val handler: (String) -> WebFetchHttpResponse,
    ) : WebFetchClient {
        val urls = mutableListOf<String>()
        override fun get(url: String): WebFetchHttpResponse {
            urls += url
            return handler(url)
        }
    }

    private fun redirectText(original: String, redirect: String, status: String, prompt: String) = listOf(
        "REDIRECT DETECTED: The URL redirects to a different host.",
        "",
        "Original URL: $original",
        "Redirect URL: $redirect",
        "Status: $status",
        "",
        "To complete your request, I need to fetch content from the redirected URL. Please use WebFetch again with these parameters:",
        "- url: \"$redirect\"",
        "- prompt: \"$prompt\"",
    ).joinToString("\n")

    private fun httpErrorText(status: String) = listOf(
        "The server returned HTTP $status.",
        "",
        AUTH_HINT,
    ).joinToString("\n")

    private companion object {
        const val AUTH_HINT =
            "The response body was not retrieved. If this URL requires authentication, use an authenticated tool (e.g. `gh` for GitHub, or an MCP-provided fetch tool) instead of WebFetch."
    }
}
