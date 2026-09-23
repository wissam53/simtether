package com.simtether.shared.pairing

import com.simtether.shared.protocol.Protocol
import kotlinx.serialization.SerialName
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
    // base64 — proves the QR scan, kills MITM. Rotates each session
    // (bridge pushes the next one inside the encrypted channel).
    @SerialName("oneTimeToken")     // wire compat with stored pairings
    val pairingToken: String,
    val deviceName: String = "bridge",
    // Remote-access rendezvous — present only when the bridge owner
    // opted in. The QR is a secret channel already (it carries the
    // pairing token), so the relay token can ride along.
    val relay: String? = null,      // "host:port" of a splice relay
    val relayToken: String? = null, // access token the relay expects
    // base64 — derives the room ticket that gates /connect; only the
    // bridge and the paired client ever hold it.
    val relaySecret: String? = null,
) {
    fun encode(): String = Protocol.json.encodeToString(serializer(), this)

    companion object {
        fun decode(qrText: String): PairingPayload =
            Protocol.json.decodeFromString(serializer(), qrText)
    }
}
