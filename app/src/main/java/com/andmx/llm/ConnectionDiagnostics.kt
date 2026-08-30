package com.andmx.llm

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * ZCode's `testModel` failure taxonomy.
 *
 * Reverse-engineered from `settings.modelProvider.testModel.error.*` (the seven
 * display strings) together with the HTTP classification cascade in the renderer
 * bundle, which maps a status code to an `(errorSource, failureReason)` pair:
 *
 * | status | failureReason   | source   |
 * |--------|-----------------|----------|
 * | 401/403| auth_failed     | provider |
 * | 404/410| model_not_found | provider |
 * | 429    | rate_limited    | provider |
 * | 408/504| timeout         | network  |
 * | 5xx    | server_error    | provider |
 *
 * ZCode renders `连接失败：{reason}` when a reason is known and a bare
 * `连接失败` when it is not; a result set with zero endpoints is reported as
 * `未配置 endpoint`.
 */
internal enum class ConnectionFailure {
    AUTH,
    MODEL_NOT_FOUND,
    RATE_LIMIT,
    SERVER,
    NETWORK,
    NO_ENDPOINT,
    UNKNOWN,
}

internal fun classifyHttpFailure(code: Int): ConnectionFailure = when (code) {
    401, 403 -> ConnectionFailure.AUTH
    404, 410 -> ConnectionFailure.MODEL_NOT_FOUND
    429 -> ConnectionFailure.RATE_LIMIT
    408, 504 -> ConnectionFailure.NETWORK
    in 500..599 -> ConnectionFailure.SERVER
    else -> ConnectionFailure.UNKNOWN
}

internal fun classifyConnectionFailure(t: Throwable?): ConnectionFailure = when (t) {
    null -> ConnectionFailure.UNKNOWN
    is RateLimitException -> ConnectionFailure.RATE_LIMIT
    is HttpStatusException -> classifyHttpFailure(t.statusCode)
    is RetryableHttpException -> classifyHttpFailure(t.statusCode)
    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
        ConnectionFailure.NETWORK
    is IOException -> ConnectionFailure.NETWORK
    else -> ConnectionFailure.UNKNOWN
}

internal fun connectionFailureText(failure: ConnectionFailure): String = when (failure) {
    ConnectionFailure.AUTH -> "认证失败"
    ConnectionFailure.MODEL_NOT_FOUND -> "模型未找到"
    ConnectionFailure.RATE_LIMIT -> "请求频率限制"
    ConnectionFailure.SERVER -> "服务端错误"
    ConnectionFailure.NETWORK -> "网络错误"
    ConnectionFailure.NO_ENDPOINT -> "未配置 endpoint"
    ConnectionFailure.UNKNOWN -> "测试失败"
}

internal fun formatConnectionResult(success: Boolean, failure: ConnectionFailure?): String = when {
    success -> "连接成功！"
    failure == null -> "连接失败"
    failure == ConnectionFailure.UNKNOWN -> connectionFailureText(ConnectionFailure.UNKNOWN)
    else -> "连接失败：${connectionFailureText(failure)}"
}
