package com.andmx.mcp

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class McpOAuthTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        McpOAuthStore(context).clearAll()
    }

    @Test
    fun parsesCamelCaseAndSnakeCaseTokens() {
        val camel = McpOAuthConfig.parse(
            """{"accessToken":"a","refreshToken":"r","tokenUrl":"https://x/token","expiresIn":3600,"scopes":["read","write"]}""",
        )!!
        assertEquals("a", camel.accessToken)
        assertEquals("r", camel.refreshToken)
        assertEquals(listOf("read", "write"), camel.scopes)
        assertTrue(camel.expiresAtMs > System.currentTimeMillis())

        val snake = McpOAuthConfig.parse(
            """{"access_token":"a","refresh_token":"r","token_endpoint":"https://x/token","scope":"read write"}""",
        )!!
        assertEquals("a", snake.accessToken)
        assertEquals("r", snake.refreshToken)
        assertEquals(listOf("read", "write"), snake.scopes)
    }

    @Test
    fun storeIsPerServerAndCanBeCleared() {
        val store = McpOAuthStore(context)
        val srv = McpOAuthKeys.of("srv", "http://a")
        val other = McpOAuthKeys.of("other", "http://b")
        store.save(srv, McpOAuthConfig(accessToken = "one"))
        store.save(other, McpOAuthConfig(accessToken = "two"))

        assertEquals("one", store.load(srv)?.accessToken)
        assertEquals("two", store.load(other)?.accessToken)

        store.clear(srv)
        assertNull(store.load(srv))
        assertEquals("two", store.load(other)?.accessToken)
    }

    @Test
    fun expiredTokenIsRefreshedAndPersisted() = runBlocking {
        val calls = AtomicInteger(0)
        val grant = AtomicReference("")
        val oauth = McpOAuth(context)
        oauth.poster = { _, body ->
            calls.incrementAndGet()
            grant.set(body)
            """{"access_token":"fresh","refresh_token":"rotated","expires_in":3600,"token_type":"Bearer"}"""
        }
        val config = McpServerConfig(
            name = "srv",
            command = "http://example.invalid/mcp",
            transport = McpTransport.HTTP,
            oauthJson = """
                {"refreshToken":"old-refresh","tokenUrl":"https://example.invalid/token","clientId":"client","clientSecret":"secret","expiresAt":1}
            """.trimIndent(),
        )

        val token = oauth.accessToken(config)

        assertEquals("fresh", token)
        assertEquals(1, calls.get())
        assertTrue(grant.get().contains("grant_type=refresh_token"))
        assertTrue(grant.get().contains("refresh_token=old-refresh"))
        assertTrue(grant.get().contains("scope=") || !grant.get().contains("scope"))
        val stored = McpOAuthStore(context).load(
            McpOAuthKeys.of("srv", "http://example.invalid/mcp"),
        )!!
        assertEquals("fresh", stored.accessToken)
        assertEquals("rotated", stored.refreshToken)
    }

    @Test
    fun clientCredentialsAreUsedWhenNoRefreshTokenExists() = runBlocking {
        val grant = AtomicReference("")
        val oauth = McpOAuth(context)
        oauth.poster = { _, body ->
            grant.set(body)
            """{"access_token":"cc","expires_in":60}"""
        }
        val config = McpServerConfig(
            name = "srv",
            command = "http://example.invalid/mcp",
            transport = McpTransport.HTTP,
            oauthJson = """{"tokenUrl":"https://example.invalid/token","clientId":"id","clientSecret":"secret"}""",
        )

        assertEquals("cc", oauth.accessToken(config))
        assertTrue(grant.get().contains("grant_type=client_credentials"))
    }

    @Test
    fun futureTokenIsUsedWithoutNetwork() = runBlocking {
        val calls = AtomicInteger(0)
        val oauth = McpOAuth(context)
        oauth.poster = { _, _ ->
            calls.incrementAndGet()
            null
        }
        val config = McpServerConfig(
            name = "srv",
            command = "http://example.invalid/mcp",
            transport = McpTransport.HTTP,
            oauthJson = """
                {"accessToken":"cached","tokenUrl":"https://example.invalid/token","expiresAt":${System.currentTimeMillis() + 3_600_000}}
            """.trimIndent(),
        )

        assertEquals("cached", oauth.accessToken(config))
        assertEquals(0, calls.get())
    }

    @Test
    fun tokensAreNotReusedWhenTheEndpointChanges() = runBlocking {
        val key = McpOAuthKeys.of("srv", "http://old.example/mcp")
        McpOAuthStore(context).save(
            key,
            McpOAuthConfig(accessToken = "old", expiresAtMs = System.currentTimeMillis() + 3_600_000),
        )
        val calls = AtomicInteger(0)
        val oauth = McpOAuth(context)
        oauth.poster = { _, _ ->
            calls.incrementAndGet()
            """{"access_token":"new","expires_in":3600}"""
        }
        val config = McpServerConfig(
            name = "srv",
            command = "http://new.example/mcp",
            transport = McpTransport.HTTP,
            oauthJson = """{"refreshToken":"r","tokenUrl":"https://new.example/token"}""",
        )

        assertEquals("new", oauth.accessToken(config))
        assertEquals(1, calls.get())
    }

    @Test
    fun missingOrUnusableConfigReturnsNull() = runBlocking {
        val none = McpServerConfig(name = "srv", command = "http://x", transport = McpTransport.HTTP)
        assertNull(McpOAuth(context).accessToken(none))

        val bare = McpServerConfig(
            name = "srv",
            command = "http://x",
            transport = McpTransport.HTTP,
            oauthJson = """{"expiresAt":1}""",
        )
        assertNull(McpOAuth(context).accessToken(bare))
    }
}
