package com.simtether.shared

import android.content.Context
import java.net.URI

/**
 * Remote-access (internet fallback) settings — opt-in on BOTH apps.
 * The bridge holds the relay address/token and ships them inside the
 * pairing QR, so the client learns them automatically; the enable
 * toggle stays a separate per-device consent.
 * Keystore-encrypted like the pairing data — the token grants room
 * access on the relay.
 */
object RemoteStore {
    private const val PREFS = "remote"

    /**
     * Built-in hosted relay (Fly.io) — used when remote is on and no
     * custom server is configured. The token is an abuse gate, not a
     * security boundary: room ownership is proven cryptographically
     * (RelayProof). It ships in the APK via BuildConfig — sourced from
     * the gitignored root relay.properties so it never lands in git
     * history. Rollover: `fly secrets set ACCESS_TOKENS=old,new` keeps
     * installed clients working while the new token ships.
     */
    const val DEFAULT_RELAY = "wss://simtether-relay.fly.dev:443"
    private val DEFAULT_RELAY_TOKEN = BuildConfig.DEFAULT_RELAY_TOKEN

    /** Relay in effect — the custom override, else the built-in server. */
    fun effectiveRelay(context: Context): String =
        relay(context) ?: DEFAULT_RELAY

    fun effectiveRelayToken(context: Context): String? =
        relayToken(context) ?: DEFAULT_RELAY_TOKEN.takeIf { it.isNotBlank() }

    fun isEnabled(context: Context): Boolean =
        SecureStore.getString(context, PREFS, "enabled") == "1"

    fun setEnabled(context: Context, v: Boolean) {
        SecureStore.putString(context, PREFS, "enabled", if (v) "1" else "0")
        // Consent audit trail — "remote was never turned on" claims get
        // answered by this timestamp on the device, not by our word.
        SecureStore.putString(context, PREFS, "enabled_at",
            if (v) System.currentTimeMillis().toString() else null)
    }

    /** Epoch ms of the last consent, null if never enabled. */
    fun enabledAt(context: Context): Long? =
        SecureStore.getString(context, PREFS, "enabled_at")?.toLongOrNull()

    fun relay(context: Context): String? =
        SecureStore.getString(context, PREFS, "relay")
            ?.let(::normalizeRelay)

    fun relayToken(context: Context): String? =
        SecureStore.getString(context, PREFS, "token")?.takeIf { it.isNotBlank() }

    fun setRelay(context: Context, addr: String?, token: String?) {
        SecureStore.putString(context, PREFS, "relay", normalizeRelay(addr) ?: "")
        SecureStore.putString(context, PREFS, "token", token?.trim() ?: "")
    }

    /**
     * Canonical "ws(s)://host[:port]" form, or null when the input is
     * unusable. Bare "host:port" gets ws://; foreign schemes are
     * rejected. A trailing all-digit label with no port ("host.443")
     * is the classic '.'-for-':' typo — it never resolves (Java URI
     * even reports host=null, which java-websocket silently turns
     * into a localhost dial), so rewrite it as the intended port.
     */
    /** Loopback, link-local, RFC1918 and mDNS-style names — the only
     *  places cleartext ws:// is acceptable for a relay address. */
    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h == "::1" || h.endsWith(".local") ||
            h.endsWith(".lan") || h.endsWith(".internal") ||
            h.startsWith("127.") || h.startsWith("10.") ||
            h.startsWith("192.168.") || h.startsWith("169.254.") ||
            (h.startsWith("172.") &&
                (h.substringAfter("172.").substringBefore('.')
                    .toIntOrNull() ?: -1) in 16..31) ||
            // IPv6: fc00::/7 ULA and fe80::/10 link-local.
            (':' in h && (h.startsWith("fc") || h.startsWith("fd") ||
                (h.startsWith("fe") &&
                    h.getOrNull(2)?.lowercaseChar() in '8'..'b')))
    }

    fun normalizeRelay(addr: String?): String? {
        var s = addr?.trim().orEmpty()
        if (s.isEmpty()) return null
        val explicitScheme = "://" in s
        if (!explicitScheme) s = "ws://$s"
        if (!s.startsWith("ws://") && !s.startsWith("wss://")) return null
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        var host = u.host
        var port = u.port
        if (host == null) {
            // Authority that isn't a legal host — try the dot-port rescue.
            val auth = u.rawAuthority?.substringAfterLast('@').orEmpty()
            val tail = auth.substringAfterLast('.', "")
            val p = tail.toIntOrNull()
            if (p == null || p !in 1..65535) return null
            host = auth.substringBeforeLast('.', "")
            port = p
        }
        if (port < 0) {
            val tail = host.substringAfterLast('.', "")
            val p = tail.toIntOrNull()
            if (p != null && p in 1..65535) {
                host = host.substringBeforeLast('.')
                port = p
            }
        }
        if (host.isEmpty() || port > 65535) return null
        // URI.getHost() keeps IPv6 brackets — strip once, test and
        // re-bracket, or "[fd00::1]" reads as a public host and the
        // final form becomes "[[fd00::1]]".
        val bare = host.removeSurrounding("[", "]")
        // Cleartext ws:// to a public host puts the access token and
        // room ticket on the wire for anyone on the path. A bare
        // "host:port" upgrades to wss://; an explicitly typed
        // "ws://public" is refused — don't silently honor a request
        // for something insecure.
        val scheme = if (u.scheme == "ws" && !isPrivateHost(bare)) {
            if (explicitScheme) return null else "wss"
        } else u.scheme
        val h = if (':' in bare) "[$bare]" else bare
        return "$scheme://$h" + if (port >= 0) ":$port" else ""
    }
}
