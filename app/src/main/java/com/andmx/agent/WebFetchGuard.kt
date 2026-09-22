package com.andmx.agent

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * 上游 webfetch-url.ts + webfetch-egress-guard.ts 对齐：
 * URL 形态过滤（协议/凭据/长度/host 形态）+ 字面量 IP 私网阻断。
 * 与上游一致不做 DNS preflight——普通域名只查字面形态，IP 字面量在每次真实 GET 前查。
 */
object WebFetchGuard {

    const val MAX_URL_CHARS = 2_000
    const val MAX_REDIRECTS = 5

    class Blocked(val reason: String) : Exception(reason)

    fun normalizeUrl(raw: String): URI {
        if (raw.length > MAX_URL_CHARS) throw Blocked("URL too long (${raw.length} chars)")
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw Blocked("Invalid URL: $raw")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw Blocked("WebFetch only supports http and https URLs")
        }
        if (!uri.userInfo.isNullOrEmpty()) throw Blocked("WebFetch URLs must not include credentials")
        val upgraded = if (scheme == "http") {
            URI("https" + raw.trim().substring(4))
        } else uri
        assertPublicHost(upgraded.host ?: throw Blocked("URL must include a hostname"))
        return upgraded
    }

    /** 每次真实 GET（含重定向跳点）前调用：字面量 IP 必须是公网地址。 */
    fun assertLiteralEgress(uri: URI) {
        val host = uri.host ?: throw Blocked("URL must include a hostname")
        assertPublicHost(host)
    }

    /**
     * 上游 isPermittedRedirect：同协议/端口/host(mod www)、目标无凭据且为公网 → 自动跟随；
     * 否则交给调用方返回 REDIRECT 提示让模型重新发起（跨域跳转需要模型显式确认）。
     */
    fun isPermittedRedirect(from: URI, to: URI): Boolean {
        if (!to.userInfo.isNullOrEmpty()) return false
        val toHost = to.host?.lowercase() ?: return false
        if (runCatching { assertPublicHost(toHost) }.isFailure) return false
        val fromHost = from.host?.lowercase() ?: return false
        val fromPort = from.port.takeIf { it >= 0 } ?: if (from.scheme == "https") 443 else 80
        val toPort = to.port.takeIf { it >= 0 } ?: if (to.scheme == "https") 443 else 80
        if (from.scheme != to.scheme || fromPort != toPort) return false
        return stripWww(fromHost) == stripWww(toHost)
    }

    fun resolveRedirect(location: String, current: URI): URI =
        runCatching { current.resolve(location) }.getOrNull()
            ?: throw Blocked("Redirect Location is not a valid URL: $location")

    private fun assertPublicHost(hostname: String) {
        val host = hostname.trim().lowercase().removeSuffix(".")
            .removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) throw Blocked("URL must include a hostname")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw Blocked("WebFetch cannot access private or local hostnames")
        }
        parseIpv4(host)?.let { bytes ->
            if (!isPublicIpv4(bytes)) throw Blocked("WebFetch cannot access private or local IP addresses")
            return
        }
        if (host.contains(':')) {
            val addr = runCatching { InetAddress.getByName(host) as? Inet6Address }.getOrNull()
                ?: throw Blocked("Invalid IPv6 literal: $host")
            val unwrapped = unwrapCarrierAddress(addr)
            if (unwrapped != null) {
                if (!isPublicIpv4(unwrapped.address)) throw Blocked("WebFetch cannot access private or local IP addresses")
                return
            }
            if (!isPublicIpv6(addr)) throw Blocked("WebFetch cannot access private or local IP addresses")
            return
        }
        if (!host.contains('.')) throw Blocked("Invalid URL")
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
        val bytes = parts.map { it.toIntOrNull() ?: return null }
        if (bytes.any { it > 255 }) return null
        return bytes.map { it.toByte() }.toByteArray()
    }

    private fun isPublicIpv4(b: ByteArray): Boolean {
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val b2 = b[2].toInt() and 0xFF
        val b3 = b[3].toInt() and 0xFF
        return when {
            b0 == 0 || b0 == 10 || b0 == 127 -> false
            b0 == 100 && b1 in 64..127 -> false
            b0 == 169 && b1 == 254 -> false
            b0 == 172 && b1 in 16..31 -> false
            b0 == 192 && b1 == 0 && b2 == 0 -> false
            b0 == 192 && b1 == 0 && b2 == 2 -> false
            b0 == 192 && b1 == 168 -> false
            b0 == 198 && (b1 == 18 || b1 == 19) -> false
            b0 == 198 && b1 == 51 && b2 == 100 -> false
            b0 == 203 && b1 == 0 && b2 == 113 -> false
            b0 >= 224 -> false
            b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255 -> false
            else -> true
        }
    }

    /** IPv4-mapped(::ffff:a.b.c.d) 与 NAT64(64:ff9b::/96) 还原低 32 位 IPv4 再套用同一策略。 */
    private fun unwrapCarrierAddress(addr: Inet6Address): Inet4Address? {
        val bytes = addr.address
        if (bytes.size != 16) return null
        val v4Mapped = (0..9).all { bytes[it] == 0.toByte() } && bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte()
        val nat64 = (0..11).all { bytes[it] == NAT64_PREFIX[it] }
        if (!v4Mapped && !nat64) return null
        return runCatching {
            InetAddress.getByAddress(bytes.sliceArray(12..15)) as? Inet4Address
        }.getOrNull()
    }

    private fun isPublicIpv6(addr: Inet6Address): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress) return false
        return SPECIAL_USE_V6.none { (prefix, bits) -> prefixMatches(addr.address, prefix, bits) }
    }

    private fun prefixMatches(addr: ByteArray, prefix: ByteArray, bits: Int): Boolean {
        for (i in 0 until bits) {
            val byteIdx = i / 8
            val bitIdx = 7 - (i % 8)
            if (((addr[byteIdx].toInt() shr bitIdx) and 1) != ((prefix[byteIdx].toInt() shr bitIdx) and 1)) {
                return false
            }
        }
        return true
    }

    private val NAT64_PREFIX = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

    private val SPECIAL_USE_V6: List<Pair<ByteArray, Int>> = listOf(
        v6("fc00::") to 7,
        v6("64:ff9b:1::") to 48,
        v6("100::") to 64,
        v6("2001:2::") to 48,
        v6("2001:10::") to 28,
        v6("2001:20::") to 28,
        v6("2001:db8::") to 32,
    )

    private fun v6(s: String): ByteArray =
        (InetAddress.getByName(s) as Inet6Address).address

    private fun stripWww(host: String): String = host.removePrefix("www.")
}

/**
 * 上游 webfetch-cache.ts：15min TTL、50MB 总量、LRU。
 * 缓存的是工具最终返回给模型的文本（含标题/正文/摘要后内容）。
 */
object WebFetchCache {

    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_BYTES = 50 * 1024 * 1024L

    private data class Entry(val text: String, val expiresAt: Long, val sizeBytes: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = false
    }
    private var totalBytes = 0L

    @Synchronized
    fun get(url: String): String? {
        val e = map[url] ?: return null
        if (e.expiresAt <= System.currentTimeMillis()) {
            map.remove(url); totalBytes -= e.sizeBytes
            return null
        }
        return e.text
    }

    @Synchronized
    fun put(url: String, text: String) {
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (size > MAX_BYTES) return
        map.remove(url)?.let { totalBytes -= it.sizeBytes }
        map[url] = Entry(text, System.currentTimeMillis() + TTL_MS, size)
        totalBytes += size
        val now = System.currentTimeMillis()
        map.entries.removeIf { (k, e) ->
            if (e.expiresAt <= now) { totalBytes -= e.sizeBytes; true } else false
        }
        while (totalBytes > MAX_BYTES && map.isNotEmpty()) {
            val eldest = map.entries.first()
            totalBytes -= eldest.value.sizeBytes
            map.remove(eldest.key)
        }
    }

    @Synchronized
    fun clear() {
        map.clear(); totalBytes = 0
    }
}
