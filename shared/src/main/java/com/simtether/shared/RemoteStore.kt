package com.simtether.shared

import android.content.Context

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

    fun isEnabled(context: Context): Boolean =
        SecureStore.getString(context, PREFS, "enabled") == "1"

    fun setEnabled(context: Context, v: Boolean) {
        SecureStore.putString(context, PREFS, "enabled", if (v) "1" else "0")
    }

    fun relay(context: Context): String? =
        SecureStore.getString(context, PREFS, "relay")?.takeIf { it.isNotBlank() }

    fun relayToken(context: Context): String? =
        SecureStore.getString(context, PREFS, "token")?.takeIf { it.isNotBlank() }

    fun setRelay(context: Context, addr: String?, token: String?) {
        SecureStore.putString(context, PREFS, "relay", addr?.trim() ?: "")
        SecureStore.putString(context, PREFS, "token", token?.trim() ?: "")
    }
}
