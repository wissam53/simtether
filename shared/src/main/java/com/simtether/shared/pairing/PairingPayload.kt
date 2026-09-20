package com.simtether.shared.pairing

import com.simtether.shared.protocol.Protocol
import kotlinx.serialization.Serializable

/**
 * Content of the QR shown by the bridge and scanned by the client.
 * Contains everything needed to reach and authenticate the bridge.
 */
@Serializable
data class PairingPayload(
    val host: String,              // bridge LAN IP (usually hotspot gateway)
    val port: Int,
    val bridgeStaticPubKey: String, // base64 X25519 — pinned on pair
    val oneTimeToken: String,       // base64 — proves QR scan, kills MITM
    val deviceName: String = "bridge",
) {
    fun encode(): String = Protocol.json.encodeToString(serializer(), this)

    companion object {
        fun decode(qrText: String): PairingPayload =
            Protocol.json.decodeFromString(serializer(), qrText)
    }
}
