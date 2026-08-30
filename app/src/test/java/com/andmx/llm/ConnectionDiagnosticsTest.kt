package com.andmx.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ConnectionDiagnosticsTest {

    @Test
    fun everyZcodeDocumentedStatusLandsInItsCategory() {
        val table = listOf(
            401 to ConnectionFailure.AUTH,
            403 to ConnectionFailure.AUTH,
            404 to ConnectionFailure.MODEL_NOT_FOUND,
            410 to ConnectionFailure.MODEL_NOT_FOUND,
            429 to ConnectionFailure.RATE_LIMIT,
            408 to ConnectionFailure.NETWORK,
            504 to ConnectionFailure.NETWORK,
            500 to ConnectionFailure.SERVER,
            502 to ConnectionFailure.SERVER,
            503 to ConnectionFailure.SERVER,
            599 to ConnectionFailure.SERVER,
        )
        for ((status, expected) in table) {
            assertEquals("HTTP $status", expected, classifyHttpFailure(status))
        }
    }

    @Test
    fun statusesOutsideTheTaxonomyFallBackToUnknown() {
        for (status in listOf(0, 1, 200, 204, 301, 302, 400, 402, 405, 409, 413, 422, 451)) {
            assertEquals("HTTP $status", ConnectionFailure.UNKNOWN, classifyHttpFailure(status))
        }
    }

    @Test
    fun aRateLimitedThrowIsRateLimitWhateverItsMessageSays() {
        assertEquals(
            ConnectionFailure.RATE_LIMIT,
            classifyConnectionFailure(RateLimitException("quota exceeded")),
        )
        assertEquals(
            ConnectionFailure.RATE_LIMIT,
            classifyConnectionFailure(RateLimitException("")),
        )
    }

    @Test
    fun aRetryableThrowIsClassifiedByItsOwnStatusCode() {
        assertEquals(
            ConnectionFailure.NETWORK,
            classifyConnectionFailure(RetryableHttpException(408, "timeout")),
        )
        assertEquals(
            ConnectionFailure.SERVER,
            classifyConnectionFailure(RetryableHttpException(503, "overloaded")),
        )
        assertEquals(
            ConnectionFailure.UNKNOWN,
            classifyConnectionFailure(RetryableHttpException(409, "conflict")),
        )
    }

    @Test
    fun aNonRetryableThrowKeepsTheStatusCodeTheTransportDropped() {
        assertEquals(
            ConnectionFailure.AUTH,
            classifyConnectionFailure(HttpStatusException(401, """{"error":"bad key"}""")),
        )
        assertEquals(
            ConnectionFailure.MODEL_NOT_FOUND,
            classifyConnectionFailure(HttpStatusException(404, "no such model")),
        )
        assertEquals(
            ConnectionFailure.UNKNOWN,
            classifyConnectionFailure(HttpStatusException(400, "bad request")),
        )
    }

    @Test
    fun transportLevelFailuresAreAllNetwork() {
        val throwables = listOf(
            UnknownHostException("api.example.com"),
            ConnectException("Connection refused"),
            SocketTimeoutException("Read timed out"),
            IOException("unexpected end of stream"),
        )
        for (t in throwables) {
            assertEquals(t::class.simpleName, ConnectionFailure.NETWORK, classifyConnectionFailure(t))
        }
    }

    @Test
    fun anUnrecognisedThrowableIsUnknownRatherThanNetwork() {
        assertEquals(
            ConnectionFailure.UNKNOWN,
            classifyConnectionFailure(IllegalStateException("boom")),
        )
        assertEquals(ConnectionFailure.UNKNOWN, classifyConnectionFailure(null))
    }

    @Test
    fun theDisplayTextCoversAllSevenZcodeStrings() {
        val expected = mapOf(
            ConnectionFailure.AUTH to "认证失败",
            ConnectionFailure.MODEL_NOT_FOUND to "模型未找到",
            ConnectionFailure.RATE_LIMIT to "请求频率限制",
            ConnectionFailure.SERVER to "服务端错误",
            ConnectionFailure.NETWORK to "网络错误",
            ConnectionFailure.NO_ENDPOINT to "未配置 endpoint",
            ConnectionFailure.UNKNOWN to "测试失败",
        )
        for (failure in ConnectionFailure.values()) {
            assertEquals(failure.name, expected[failure], connectionFailureText(failure))
        }
    }

    @Test
    fun everyCategoryHasADistinctDisplayString() {
        val texts = ConnectionFailure.values().map { connectionFailureText(it) }
        assertEquals(
            "two categories share a display string",
            texts.size,
            texts.toSet().size,
        )
    }

    @Test
    fun successRendersTheZcodeCelebration() {
        assertEquals("连接成功！", formatConnectionResult(success = true, failure = null))
        assertEquals(
            "连接成功！",
            formatConnectionResult(success = true, failure = ConnectionFailure.AUTH),
        )
    }

    @Test
    fun aKnownFailureIsRenderedWithItsReason() {
        assertEquals(
            "连接失败：认证失败",
            formatConnectionResult(success = false, failure = ConnectionFailure.AUTH),
        )
        assertEquals(
            "连接失败：模型未找到",
            formatConnectionResult(success = false, failure = ConnectionFailure.MODEL_NOT_FOUND),
        )
        assertEquals(
            "连接失败：未配置 endpoint",
            formatConnectionResult(success = false, failure = ConnectionFailure.NO_ENDPOINT),
        )
    }

    @Test
    fun anUnclassifiedFailureCollapsesToTheBareWording() {
        assertEquals(
            "测试失败",
            formatConnectionResult(success = false, failure = ConnectionFailure.UNKNOWN),
        )
        assertEquals("连接失败", formatConnectionResult(success = false, failure = null))
    }

    @Test
    fun everyCategorySurvivesTheClassifyThenFormatRoundTrip() {
        for (failure in ConnectionFailure.values()) {
            val rendered = formatConnectionResult(success = false, failure = failure)
            assert(rendered.isNotBlank()) { "$failure rendered blank" }
            if (failure != ConnectionFailure.UNKNOWN) {
                assert(rendered.contains(connectionFailureText(failure))) {
                    "$failure rendered as $rendered"
                }
            }
        }
    }
}
