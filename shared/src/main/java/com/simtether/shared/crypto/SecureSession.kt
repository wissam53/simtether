package com.simtether.shared.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Minimal Noise-IK-inspired handshake over the pairing QR.
 *
 * Bridge holds a static X25519 key (published in the QR). Client sends
 * an ephemeral X25519 key; session keys come from DH(ee, es) mixed
 * through HKDF-SHA256. Transport is ChaCha20-Poly1305.
 *
 * TODO(security): this is a compact DH handshake, not audited Noise IK.
 * Before release either port the full Noise IK pattern (proper
 * transcript hashing + token-bound static encryption) or swap in an
 * audited Noise library. Do not ship this as-is.
 */
class SecureSession private constructor(
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
) {
    private val rand = SecureRandom()
    private var sendNonce = 0L
    private var recvNonce = 0L

    fun encrypt(plaintext: ByteArray): ByteArray = aead(true, sendKey, sendNonce++, plaintext)

    fun decrypt(ciphertext: ByteArray): ByteArray = aead(false, recvKey, recvNonce++, ciphertext)

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: Long, data: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        val nonceBytes = ByteArray(12).also { nb ->
            for (i in 0 until 8) nb[4 + i] = (nonce shr (8 * i)).toByte()
        }
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonceBytes))
        val out = ByteArray(cipher.getOutputSize(data.size))
        var len = cipher.processBytes(data, 0, data.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    companion object {
        private const val KEY_LEN = 32

        fun generateKeyPair(): Pair<ByteArray, ByteArray> {
            val priv = X25519PrivateKeyParameters(SecureRandom())
            return priv.encoded to priv.generatePublicKey().encoded
        }

        /**
         * Client side of the handshake. Returns the client's ephemeral
         * public key (to send in the clear over the new WS connection)
         * and the established session.
         */
        fun clientHandshake(bridgeStaticPub: ByteArray): Pair<ByteArray, SecureSession> {
            val (ephPrivRaw, ephPub) = generateKeyPair()
            val ephPriv = X25519PrivateKeyParameters(ephPrivRaw, 0)
            val shared = ByteArray(32)
            X25519Agreement().apply {
                init(ephPriv)
                calculateAgreement(X25519PublicKeyParameters(bridgeStaticPub, 0), shared, 0)
            }
            return ephPub to fromSharedSecret(shared, clientIsSender = true)
        }

        /** Bridge side: derive the session from the client's ephemeral key. */
        fun bridgeHandshake(bridgeStaticPriv: ByteArray, clientEphPub: ByteArray): SecureSession {
            val priv = X25519PrivateKeyParameters(bridgeStaticPriv, 0)
            val shared = ByteArray(32)
            X25519Agreement().apply {
                init(priv)
                calculateAgreement(X25519PublicKeyParameters(clientEphPub, 0), shared, 0)
            }
            return fromSharedSecret(shared, clientIsSender = false)
        }

        private fun fromSharedSecret(shared: ByteArray, clientIsSender: Boolean): SecureSession {
            val okm = ByteArray(KEY_LEN * 2)
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(shared, null, "simtether-v1".toByteArray()))
                generateBytes(okm, 0, okm.size)
            }
            val c2s = okm.copyOfRange(0, KEY_LEN)
            val s2c = okm.copyOfRange(KEY_LEN, KEY_LEN * 2)
            return if (clientIsSender) SecureSession(c2s, s2c) else SecureSession(s2c, c2s)
        }
    }
}
