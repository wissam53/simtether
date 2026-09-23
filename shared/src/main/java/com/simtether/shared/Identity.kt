package com.simtether.shared

import java.security.MessageDigest

/**
 * Bridge identity helpers. The mDNS service name embeds a short
 * fingerprint of the bridge's static pubkey so a client can find ITS
 * bridge on a shared LAN without probing strangers' devices.
 * Cryptographic verification still happens in the handshake — the
 * fingerprint is just a filter.
 */
object Identity {
    const val SERVICE_TYPE = "_st1._tcp."

    /** 8 hex chars — collision-safe enough for LAN-scale filtering. */
    fun fingerprint(staticPubKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(staticPubKey)
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun serviceName(staticPubKey: ByteArray): String = "st1-${fingerprint(staticPubKey)}"

    /**
     * Relay room name — the full static pubkey, b64url (~43 chars).
     * Not the short fingerprint: a 32-bit room id is collision-
     * mineable (~2^32 keygens), which would let any relay-token
     * holder register a colliding key and evict a victim's room.
     */
    fun roomId(staticPubKey: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(staticPubKey)
}
