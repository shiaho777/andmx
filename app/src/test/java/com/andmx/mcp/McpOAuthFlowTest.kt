package com.andmx.mcp

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class McpOAuthFlowTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        McpOAuthStore(context).clearAll()
    }

    private fun server() = McpServerConfig(
        name = "srv",
        command = "https://mcp.example/rpc",
        transport = McpTransport.HTTP,
        oauthJson = """
            {"authorizationUrl":"https://auth.example/authorize","tokenUrl":"https://auth.example/token","clientId":"client","scopes":["read","write"]}
        """.trimIndent(),
    )

    @Test
    fun pkceChallengeIsUrlSafeAndDeterministic() {
        val a = McpOAuthFlow.codeChallenge("verifier")
        val b = McpOAuthFlow.codeChallenge("verifier")

        assertEquals(a, b)
        assertEquals(43, a.length)
        assertTrue(a.none { it == '=' || it == '+' || it == '/' })
        assertFalse(a == McpOAuthFlow.codeChallenge("other"))
    }

    @Test
    fun authorizationUrlCarriesPkceAndState() {
        val url = McpOAuth(context = context).authorizationUrl(
            server = server(),
            state = "state-1",
            codeChallenge = "challenge-1",
            redirectUri = McpOAuthFlow.REDIRECT_URI,
        )!!

        assertTrue(url.startsWith("https://auth.example/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client"))
        assertTrue(url.contains("state=state-1"))
        assertTrue(url.contains("code_challenge=challenge-1"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("scope=read+write"))
        assertTrue(url.contains("redirect_uri=andmx%3A%2F%2Fmcp-oauth"))
    }

    @Test
    fun callbackExchangesCodeAndPersistsToken() = runBlocking {
        val srv = server()
        McpOAuthFlow.savePending(
            context,
            McpOAuthFlow.Pending(
                serverLine = srv.toLine(),
                verifier = "verifier",
                state = "state-1",
                redirectUri = McpOAuthFlow.REDIRECT_URI,
                createdAt = System.currentTimeMillis(),
            ),
        )
        val form = AtomicReference("")
        val oauth = McpOAuth(context = context)
        oauth.poster = { _, body ->
            form.set(body)
            """{"access_token":"token-1","refresh_token":"refresh-1","expires_in":3600}"""
        }

        val result = McpOAuthFlow.complete(
            context,
            Uri.parse("andmx://mcp-oauth?code=abc&state=state-1"),
            oauth,
        )

        assertTrue(result.message, result.ok)
        assertTrue(form.get().contains("grant_type=authorization_code"))
        assertTrue(form.get().contains("code=abc"))
        assertTrue(form.get().contains("code_verifier=verifier"))
        assertTrue(form.get().contains("redirect_uri=andmx%3A%2F%2Fmcp-oauth"))
        val stored = McpOAuthStore(context).load(McpOAuthKeys.of(srv))!!
        assertEquals("token-1", stored.accessToken)
        assertEquals("refresh-1", stored.refreshToken)
    }

    @Test
    fun wrongStateOrExpiredPendingIsRejected() = runBlocking {
        val srv = server()
        McpOAuthFlow.savePending(
            context,
            McpOAuthFlow.Pending(
                serverLine = srv.toLine(),
                verifier = "verifier",
                state = "expected",
                redirectUri = McpOAuthFlow.REDIRECT_URI,
                createdAt = System.currentTimeMillis(),
            ),
        )
        val oauth = McpOAuth(context)
        var calls = 0
        oauth.poster = { _, _ ->
            calls++
            """{"access_token":"should-not-happen"}"""
        }
        val wrong = McpOAuthFlow.complete(
            context,
            Uri.parse("andmx://mcp-oauth?code=abc&state=other"),
            oauth,
        )
        assertFalse(wrong.ok)
        assertEquals(0, calls)

        McpOAuthFlow.savePending(
            context,
            McpOAuthFlow.Pending(
                serverLine = srv.toLine(),
                verifier = "verifier",
                state = "expected",
                redirectUri = McpOAuthFlow.REDIRECT_URI,
                createdAt = System.currentTimeMillis() - 11 * 60 * 1000L,
            ),
        )
        val expired = McpOAuthFlow.complete(
            context,
            Uri.parse("andmx://mcp-oauth?code=abc&state=expected"),
            oauth,
        )
        assertFalse(expired.ok)
        assertEquals(0, calls)
    }
}
