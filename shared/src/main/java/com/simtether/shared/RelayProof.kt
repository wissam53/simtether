package com.simtether.shared

import com.southernstorm.noise.protocol.Noise
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Relay room-ownership proof.
 *
 * The old relay gated registration on a shared ACCESS_TOKEN alone —
 * any token holder could /register/{fp} and evict a victim's bridge
 * (rooms are 32-bit fingerprints, enumerable). Now registration is a
 * challenge-response that proves possession of the bridge static key
 * matching the room fingerprint:
 *
 *   relay → bridge : ephPub(32) || nonce(32)
 *   bridge → relay : staticPub(32) || ticket(32) || mac(32)
 *   mac = HMAC-SHA256( key = SHA256("st-relay-reg-v1" || DH(priv,eph)),
 *                      msg = "register" || fp || ephPub || nonce
 *                            || staticPub || ticket )
 *
 * X25519 can't sign, so key ownership is proven by an ephemeral-DH
 * shared secret only the real bridge can compute. The relay verifies
 * fingerprint(staticPub) == room and the MAC, then adopts.
 *
 * The [ticket] gates /connect on the same room: both ends derive it
 * from a per-identity relaySecret carried in the pairing QR, so a
 * token holder who isn't the paired client can't occupy the room's
 * client slot either.
 */
object RelayProof {
    const val CHALLENGE_LEN = 64   // ephPub(32) || nonce(32)
    const val RESPONSE_LEN = 96    // staticPub(32) || ticket(32) || mac(32)

    private const val MAC_DOMAIN = "st-relay-reg-v1"
    private const val TICKET_DOMAIN = "st-ticket"

    /** Per-room connect ticket — derived identically by bridge+client
     *  from the QR-carried relaySecret; the relay stores the bridge's
     *  copy at registration and compares on /connect. */
    fun ticket(relaySecret: ByteArray, fingerprint: String): ByteArray =
        hmac(relaySecret, TICKET_DOMAIN.toByteArray() + fingerprint.toByteArray(Charsets.UTF_8))

    /**
     * Bridge side: answer a relay challenge. Returns the 96-byte
     * response frame, or null if the challenge is malformed.
     */
    fun respond(
        staticPriv: ByteArray,
        staticPub: ByteArray,
        challenge: ByteArray,
        ticket: ByteArray,
    ): ByteArray? {
        if (challenge.size != CHALLENGE_LEN) return null
        val ephPub = challenge.copyOfRange(0, 32)
        val nonce = challenge.copyOfRange(32, 64)
        val shared = runCatching { x25519(staticPriv, ephPub) }.getOrNull()
            ?: return null
        val fp = Identity.fingerprint(staticPub)
        val mac = mac(shared, fp, ephPub, nonce, staticPub, ticket)
        return staticPub + ticket + mac
    }

    /** mac over the proof message — relay recomputes with its own DH. */
    private fun mac(
        shared: ByteArray, fp: String, ephPub: ByteArray,
        nonce: ByteArray, staticPub: ByteArray, ticket: ByteArray,
    ): ByteArray {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(MAC_DOMAIN.toByteArray() + shared)
        return hmac(key,
            "register".toByteArray() + fp.toByteArray(Charsets.UTF_8) +
                ephPub + nonce + staticPub + ticket)
    }

    /**
     * Raw X25519 DH via the noise lib already in this module —
     * javax.crypto X25519 needs API 33+, below our minSdk.
     */
    private fun x25519(priv: ByteArray, remotePub: ByteArray): ByteArray {
        val remote = Noise.createDH("25519")
        remote.setPublicKey(remotePub, 0)
        val local = Noise.createDH("25519")
        local.setPrivateKey(priv, 0)
        return ByteArray(local.sharedKeyLength)
            .also { local.calculate(it, 0, remote) }
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(msg)
        }
}
