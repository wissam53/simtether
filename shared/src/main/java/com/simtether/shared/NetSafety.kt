package com.simtether.shared

/**
 * Where cleartext traffic may go. The app carries
 * usesCleartextTraffic-equivalent NSC config because the bridge link
 * is a literal-IP ws:// dial and Android's domain rules can't express
 * private ranges — this check is the compensating control: no socket
 * may speak unencrypted to a public destination.
 */
object NetSafety {

    /**
     * Loopback, link-local, RFC1918 and mDNS-style names — the only
     * places cleartext ws:// is acceptable.
     *
     * The shape is tested before the prefixes: IP-literal rules apply
     * only to strings that ARE numeric, so a hostname like
     * "10.evil.example" can't pose as the 10.x range — the old prefix
     * check would have passed it.
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (':' in h) {
            // IPv6 literal: ::1 loopback, fc00::/7 ULA, fe80::/10
            // link-local (fe8–feb). Site-local fec0::/10 is deprecated
            // and deliberately excluded.
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
                (h.startsWith("fe") && h.getOrNull(2) in '8'..'b')
        }
        if (h.all { it.isDigit() || it == '.' }) {
            // IPv4 literal — prefix match is only meaningful here.
            return h.startsWith("127.") || h.startsWith("10.") ||
                h.startsWith("192.168.") || h.startsWith("169.254.") ||
                (h.startsWith("172.") &&
                    (h.substringAfter("172.").substringBefore('.')
                        .toIntOrNull() ?: -1) in 16..31)
        }
        // Hostnames: suffixes that can't resolve via public DNS plus
        // the exact loopback name.
        return h == "localhost" || h.endsWith(".local") ||
            h.endsWith(".lan") || h.endsWith(".internal")
    }
}
