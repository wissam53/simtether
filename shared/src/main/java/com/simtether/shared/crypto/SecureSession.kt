package com.simtether.shared.crypto

import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import javax.crypto.ShortBufferException

/**
 * Noise IK handshake over the pairing QR.
 *
 * The bridge holds a static X25519 key published in the QR, so the
 * client already knows the responder's static key before connecting
 * (IK's "<- s" pre-message). The pairing token travels inside the
 * first handshake message as encrypted payload — only the holder of
 * the bridge static key can read it, and it's bound to the
 * handshake transcript.
 *
 * Wire:
 *   client -> bridge : IK msg1 (e, es, s, ss + encrypted token)
 *   bridge -> client : IK msg2 (e, ee, se)
 *   then both sides split() into transport CipherStates
 *   (ChaCha20-Poly1305, per-direction nonces).
 */
class SecureSession private constructor(
    private val sendCipher: CipherState,
    private val recvCipher: CipherState,
) {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + TAG_LEN)
        val len = sendCipher.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
        return out.copyOf(len)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val out = ByteArray(ciphertext.size)
        val len = recvCipher.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
        return out.copyOf(len)
    }

    fun destroy() {
        sendCipher.destroy()
        recvCipher.destroy()
    }

    /** Initiator half of an in-flight IK handshake. */
    class ClientHandshake internal constructor(
        private val hs: HandshakeState,
        /** IK message 1 — send as the first binary frame. */
        val outgoing: ByteArray,
    ) {
        /** Feed the bridge's IK message 2; returns the live session. */
        fun complete(reply: ByteArray): SecureSession {
            hs.readMessage(reply, 0, reply.size, ByteArray(0), 0)
            if (hs.action != HandshakeState.COMPLETE && hs.action != HandshakeState.SPLIT) {
                throw ShortBufferException()
            }
            val pair = hs.split()
            return SecureSession(pair.sender, pair.receiver)
        }
    }

    /** Responder half: parsed msg1 + its decrypted payload. */
    class BridgeHandshake internal constructor(
        private val hs: HandshakeState,
        /** Decrypted msg1 payload — the pairing token to verify. */
        val peerPayload: ByteArray,
    ) {
        /** Writes IK msg2; returns (frame to send, live session). */
        fun complete(): Pair<ByteArray, SecureSession> {
            val buf = ByteArray(256)
            val len = hs.writeMessage(buf, 0, ByteArray(0), 0, 0)
            val pair = hs.split()
            return buf.copyOf(len) to SecureSession(pair.sender, pair.receiver)
        }
    }

    companion object {
        private const val PROTOCOL = "Noise_IK_25519_ChaChaPoly_SHA256"
        private const val TAG_LEN = 16

        /** (private, public) raw X25519 keypair — the bridge identity. */
        fun generateKeyPair(): Pair<ByteArray, ByteArray> {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            val priv = ByteArray(dh.privateKeyLength)
            val pub = ByteArray(dh.publicKeyLength)
            dh.getPrivateKey(priv, 0)
            dh.getPublicKey(pub, 0)
            return priv to pub
        }

        /**
         * Client side: builds IK message 1 with the pairing token as
         * encrypted payload. The returned handshake must be kept until
         * the bridge's reply arrives (complete()).
         */
        fun clientHandshake(bridgeStaticPub: ByteArray, pairingToken: ByteArray): ClientHandshake {
            val hs = HandshakeState(PROTOCOL, HandshakeState.INITIATOR)
            hs.remotePublicKey.setPublicKey(bridgeStaticPub, 0)
            hs.localKeyPair.generateKeyPair()
            hs.start()
            val buf = ByteArray(256)
            val len = hs.writeMessage(buf, 0, pairingToken, 0, pairingToken.size)
            return ClientHandshake(hs, buf.copyOf(len))
        }

        /**
         * Bridge side: parses IK message 1. Throws if the frame is
         * malformed or doesn't authenticate against the static key.
         * The caller must verify peerPayload == pairingToken before
         * calling complete().
         */
        fun bridgeHandshake(bridgeStaticPriv: ByteArray, msg1: ByteArray): BridgeHandshake {
            val hs = HandshakeState(PROTOCOL, HandshakeState.RESPONDER)
            hs.localKeyPair.setPrivateKey(bridgeStaticPriv, 0)
            hs.start()
            val payload = ByteArray(256)
            val plen = hs.readMessage(msg1, 0, msg1.size, payload, 0)
            return BridgeHandshake(hs, payload.copyOf(plen))
        }
    }
}
