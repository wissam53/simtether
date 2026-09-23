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
        /**
         * Parses AND validates — a malformed QR must throw here, not
         * survive into ClientService where an undecodable field crashes
         * connectToBridge into a service-restart loop.
         */
        fun decode(qrText: String): PairingPayload {
            val p = Protocol.json.decodeFromString(serializer(), qrText)
            require(p.port in 1..65535) { "bad port" }
            require(p.host.isNotBlank() && p.host.length <= 255) { "bad host" }
            require(p.deviceName.length <= 100) { "deviceName too long" }
            val pub = runCatching {
                java.util.Base64.getDecoder().decode(p.bridgeStaticPubKey)
            }.getOrNull()
            require(pub != null && pub.size == 32) { "bad bridge key" }
            val tok = runCatching {
                java.util.Base64.getDecoder().decode(p.pairingToken)
            }.getOrNull()
            require(tok != null && tok.size in 8..64) { "bad pairing token" }
            p.relay?.let { require(it.length <= 255) { "relay too long" } }
            p.relayToken?.let { require(it.length <= 255) { "token too long" } }
            p.relaySecret?.let {
                val s = runCatching {
                    java.util.Base64.getDecoder().decode(it)
                }.getOrNull()
                require(s != null && s.size == 32) { "bad relay secret" }
            }
            return p
        }
    }
}
