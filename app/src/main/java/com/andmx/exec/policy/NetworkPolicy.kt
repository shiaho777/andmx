package com.andmx.exec.policy

/**
 * Network domain-level access control — mirrors Codex's network proxy rules.
 *
 * On Android (no root, no iptables), full network isolation isn't possible
 * inside proot. Instead, we implement two complementary approaches:
 *
 * 1. Domain allow/deny lists — enforced at the application layer for tools
 *    that make HTTP requests (browse, web_search)
 * 2. hosts file injection — for the proot guest's DNS resolution
 *
 * This is weaker than Codex's SOCKS5 proxy, but provides meaningful control
 * over where agent-initiated network requests can go.
 */
class NetworkPolicy(
    private val rules: List<NetworkRule> = emptyList(),
) {
    data class NetworkRule(
        val host: String,              // supports *.example.com (single level) and **.example.com (multi-level)
        val action: NetworkAction,
        val justification: String? = null,
    )

    enum class NetworkAction { ALLOW, DENY }

    data class Decision(
        val action: NetworkAction,
        val matchedRule: NetworkRule? = null,
    ) {
        val isAllowed get() = action == NetworkAction.ALLOW
        val isDenied get() = action == NetworkAction.DENY
    }

    fun check(host: String): Decision {
        val normalized = host.lowercase().trim()

        // Check deny rules first (deny takes precedence)
        for (rule in rules) {
            if (rule.action == NetworkAction.DENY && matches(normalized, rule.host.lowercase())) {
                return Decision(NetworkAction.DENY, rule)
            }
        }
        // Then check allow rules
        for (rule in rules) {
            if (rule.action == NetworkAction.ALLOW && matches(normalized, rule.host.lowercase())) {
                return Decision(NetworkAction.ALLOW, rule)
            }
        }

        // If there are allow rules but no match, deny by default
        val hasAllowRules = rules.any { it.action == NetworkAction.ALLOW }
        return if (hasAllowRules) Decision(NetworkAction.DENY) else Decision(NetworkAction.ALLOW)
    }

    /** Check a URL by extracting its host. */
    fun checkUrl(url: String): Decision {
        val host = extractHost(url)
        return if (host != null) check(host) else Decision(NetworkAction.ALLOW)
    }

    /** Generate /etc/hosts entries for the proot guest (deny rules → 127.0.0.1). */
    fun hostsEntries(): String {
        val denyHosts = rules.filter { it.action == NetworkAction.DENY }.map { it.host }
        if (denyHosts.isEmpty()) return ""
        return buildString {
            appendLine("# AndMX NetworkPolicy — denied hosts")
            for (h in denyHosts) {
                val clean = h.removePrefix("*.").removePrefix("**.")
                appendLine("127.0.0.1 $clean")
                appendLine("::1 $clean")
            }
        }
    }

    private fun matches(host: String, pattern: String): Boolean {
        return when {
            pattern.startsWith("**.") -> {
                val suffix = pattern.substring(3)
                host == suffix || host.endsWith(".$suffix")
            }
            pattern.startsWith("*.") -> {
                val suffix = pattern.substring(2)
                // *.example.com matches sub.example.com but NOT example.com or a.b.example.com
                host.endsWith(".$suffix") && !host.dropLast(suffix.length + 1).contains(".")
            }
            pattern == "*" -> true
            else -> host == pattern
        }
    }

    private fun extractHost(url: String): String? {
        return runCatching {
            val u = java.net.URI(url)
            u.host ?: url.substringAfter("://", url).substringBefore("/").substringBefore(":")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** A permissive default policy (allow everything). */
        val PERMISSIVE = NetworkPolicy(emptyList())

        /** A restrictive policy that blocks known dangerous domains. */
        val SAFE_DEFAULT = NetworkPolicy(listOf(
            NetworkRule("**.malware-site.example", NetworkAction.DENY, "已知的恶意站点"),
            NetworkRule("**.phishing-attempt.example", NetworkAction.DENY, "钓鱼站点"),
        ))
    }
}
