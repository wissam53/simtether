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
        SecureStore.putString(context, PREFS, "token", p.pairingToken)
        SecureStore.putString(context, PREFS, "name", p.deviceName)
        SecureStore.putString(context, PREFS, "relay", p.relay)
        SecureStore.putString(context, PREFS, "relay_token", p.relayToken)
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
        )
    }

    /** Manual relay entry — overrides whatever the QR carried. */
    fun updateRelay(context: Context, relay: String?, token: String?) {
        SecureStore.putString(context, PREFS, "relay", relay?.trim() ?: "")
        SecureStore.putString(context, PREFS, "relay_token", token?.trim() ?: "")
    }

    /** Token rotation — the bridge pushes a fresh credential inside
     *  each established session (pairing.rotate event). */
    fun updateToken(context: Context, token: String) {
        SecureStore.putString(context, PREFS, "token", token)
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
