package com.simtether.client

import android.content.Context
import com.simtether.shared.SecureStore
import com.simtether.shared.pairing.PairingPayload

/**
 * Persists pairing data (bridge host + pinned pubkey + token). Values
 * are Keystore-encrypted via SecureStore — the pairing token is the
 * credential that gates the WS handshake, so it must not sit in
 * plaintext prefs (a rooted phone could otherwise steal it).
 * The pinned key is the MITM defense — a different key later = warn loudly.
 */
object PairingStore {
    private const val PREFS = "pairing"

    fun save(context: Context, p: PairingPayload) {
        SecureStore.putString(context, PREFS, "host", p.host)
        SecureStore.putInt(context, PREFS, "port", p.port)
        SecureStore.putString(context, PREFS, "pubkey", p.bridgeStaticPubKey)
        // Credentials fail closed — a token/secret that can't be
        // Keystore-wrapped must not land in plaintext prefs instead
        // (the UI surfaces degraded storage via SecureStore.degraded).
        SecureStore.putString(context, PREFS, "token", p.pairingToken,
            failClosed = true)
        SecureStore.putString(context, PREFS, "name", p.deviceName)
        SecureStore.putString(context, PREFS, "relay", p.relay)
        SecureStore.putString(context, PREFS, "relay_token", p.relayToken,
            failClosed = true)
        SecureStore.putString(context, PREFS, "relay_secret", p.relaySecret,
            failClosed = true)
    }

    fun load(context: Context): PairingPayload? {
        val host = SecureStore.getString(context, PREFS, "host") ?: return null
        val pub = SecureStore.getString(context, PREFS, "pubkey") ?: return null
        return PairingPayload(
            host = host,
            port = SecureStore.getInt(context, PREFS, "port", 0),
            bridgeStaticPubKey = pub,
            pairingToken = SecureStore.getString(context, PREFS, "token") ?: "",
            deviceName = SecureStore.getString(context, PREFS, "name") ?: "bridge",
            relay = SecureStore.getString(context, PREFS, "relay"),
            relayToken = SecureStore.getString(context, PREFS, "relay_token"),
            relaySecret = SecureStore.getString(context, PREFS, "relay_secret"),
        )
    }

    /** Manual relay entry — overrides whatever the QR carried. */
    fun updateRelay(context: Context, relay: String?, token: String?) {
        SecureStore.putString(context, PREFS, "relay", relay?.trim() ?: "")
        SecureStore.putString(context, PREFS, "relay_token", token?.trim() ?: "",
            failClosed = true)
    }

    /**
     * The client's own X25519 identity, generated once and persisted
     * Keystore-wrapped. IK transmits the public half inside encrypted
     * msg1; the bridge pins it, so after first pair the pairing token
     * alone is no longer a sufficient credential (a photographed QR
     * can't be replayed from a stranger's device). clear() wipes it —
     * a re-pair generates a fresh identity.
     */
    fun clientKeyPair(context: Context): Pair<ByteArray, ByteArray> {
        val dec = java.util.Base64.getDecoder()
        val enc = java.util.Base64.getEncoder()
        val priv = SecureStore.getString(context, PREFS, "client_priv")
        val pub = SecureStore.getString(context, PREFS, "client_pub")
        if (priv != null && pub != null) return dec.decode(priv) to dec.decode(pub)
        val pair = com.simtether.shared.crypto.SecureSession.generateKeyPair()
        // Both halves fail closed — the private key must never sit
        // plaintext, and persisting only one half would resurrect a
        // mismatched identity on the next launch.
        SecureStore.putString(context, PREFS, "client_priv",
            enc.encodeToString(pair.first), failClosed = true)
        SecureStore.putString(context, PREFS, "client_pub",
            enc.encodeToString(pair.second), failClosed = true)
        return pair
    }

    /** Token rotation — the bridge pushes a fresh credential inside
     *  each established session (pairing.rotate event). */
    fun updateToken(context: Context, token: String) {
        SecureStore.putString(context, PREFS, "token", token, failClosed = true)
    }

    /** Update the cached address after mDNS rediscovery — the identity
     *  (pubkey) stays pinned, only the location changes. */
    fun updateHost(context: Context, host: String, port: Int) {
        SecureStore.putString(context, PREFS, "host", host)
        SecureStore.putInt(context, PREFS, "port", port)
    }

    fun isPaired(context: Context) = load(context) != null

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
