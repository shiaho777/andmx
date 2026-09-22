package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class WebFetchGuardTest {

    private fun blocked(raw: String): String? = try {
        WebFetchGuard.normalizeUrl(raw).toString()
        null
    } catch (b: WebFetchGuard.Blocked) {
        b.reason
    }

    @Test
    fun publicHostAndIpPass() {
        assertNotNull(WebFetchGuard.normalizeUrl("https://example.com/a"))
        assertNotNull(WebFetchGuard.normalizeUrl("https://8.8.8.8/x"))
        assertNotNull(WebFetchGuard.normalizeUrl("https://[2606:4700:4700::1111]/"))
    }

    @Test
    fun httpUpgradesToHttps() {
        assertEquals("https", WebFetchGuard.normalizeUrl("http://example.com").scheme)
    }

    @Test
    fun localAndPrivateTargetsBlocked() {
        for (raw in listOf(
            "https://localhost/x",
            "https://foo.localhost/",
            "https://printer.local/",
            "https://10.0.0.1/",
            "https://127.0.0.1/",
            "https://169.254.1.1/",
            "https://172.16.5.5/",
            "https://192.168.1.1/",
            "https://100.64.0.1/",
            "https://198.18.0.1/",
            "https://[::1]/",
            "https://[fd00::1]/",
            "https://[::ffff:10.0.0.1]/",
            "https://[64:ff9b::a00:1]/",
        )) {
            assertNotNull("expected blocked: $raw", blocked(raw))
        }
    }

    @Test
    fun credentialsAndBadSchemesRejected() {
        assertNotNull(blocked("https://user:pass@example.com/"))
        assertNotNull(blocked("ftp://example.com/"))
        assertNotNull(blocked("file:///etc/passwd"))
        assertNotNull(blocked("https://singlelabel/"))
    }

    @Test
    fun permittedRedirectRequiresSameHostAndPublicTarget() {
        val from = URI("https://www.example.com/a")
        assertTrue(WebFetchGuard.isPermittedRedirect(from, URI("https://example.com/b")))
        assertFalse(WebFetchGuard.isPermittedRedirect(from, URI("https://other.com/b")))
        assertFalse(WebFetchGuard.isPermittedRedirect(from, URI("https://example.com:8443/b")))
        assertFalse(WebFetchGuard.isPermittedRedirect(from, URI("https://10.0.0.1/b")))
        assertFalse(WebFetchGuard.isPermittedRedirect(from, URI("http://example.com/b")))
    }

    @Test
    fun cacheRoundTripsAndExpires() {
        WebFetchCache.clear()
        assertNull(WebFetchCache.get("https://a/"))
        WebFetchCache.put("https://a/", "text")
        assertEquals("text", WebFetchCache.get("https://a/"))
        WebFetchCache.clear()
        assertNull(WebFetchCache.get("https://a/"))
    }
}
