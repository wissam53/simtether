package com.simtether.shared.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.BadPaddingException

class SecureSessionTest {

    @Test
    fun `ik handshake establishes bidirectional transport`() {
        val (priv, pub) = SecureSession.generateKeyPair()
        val (clientPriv, clientPub) = SecureSession.generateKeyPair()
        val token = ByteArray(16) { (it * 7).toByte() }

        val clientHs = SecureSession.clientHandshake(pub, token, clientPriv)
        val bridgeHs = SecureSession.bridgeHandshake(priv, clientHs.outgoing)
        assertArrayEquals(token, bridgeHs.peerPayload)
        // The initiator static rides encrypted msg1 — the bridge sees
        // the client's identity key for pinning.
        assertArrayEquals(clientPub, bridgeHs.peerStaticPub)

        val (reply, bridgeSession) = bridgeHs.complete()
        val clientSession = clientHs.complete(reply)

        val c2s = clientSession.encrypt("otp 123456".encodeToByteArray())
        assertEquals("otp 123456", bridgeSession.decrypt(c2s).decodeToString())

        val s2c = bridgeSession.encrypt("ack".encodeToByteArray())
        assertEquals("ack", clientSession.decrypt(s2c).decodeToString())
    }

    @Test
    fun `wrong responder key fails authentication`() {
        val (_, pub) = SecureSession.generateKeyPair()
        val (clientPriv, _) = SecureSession.generateKeyPair()
        val (wrongPriv, _) = SecureSession.generateKeyPair()
        val clientHs = SecureSession.clientHandshake(pub, ByteArray(16), clientPriv)
        assertThrows(BadPaddingException::class.java) {
            SecureSession.bridgeHandshake(wrongPriv, clientHs.outgoing)
        }
    }

    @Test
    fun `tampered handshake frame rejected`() {
        val (priv, pub) = SecureSession.generateKeyPair()
        val (clientPriv, _) = SecureSession.generateKeyPair()
        val clientHs = SecureSession.clientHandshake(pub, ByteArray(16), clientPriv)
        val bad = clientHs.outgoing.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
        assertThrows(BadPaddingException::class.java) {
            SecureSession.bridgeHandshake(priv, bad)
        }
    }

    @Test
    fun `tampered ciphertext rejected`() {
        val (priv, pub) = SecureSession.generateKeyPair()
        val (clientPriv, _) = SecureSession.generateKeyPair()
        val token = ByteArray(16)
        val clientHs = SecureSession.clientHandshake(pub, token, clientPriv)
        val bridgeHs = SecureSession.bridgeHandshake(priv, clientHs.outgoing)
        val (reply, bridgeSession) = bridgeHs.complete()
        clientHs.complete(reply)

        // A tampered msg2 must fail AEAD on a waiting client.
        val bad = reply.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
        val clientHs2 = SecureSession.clientHandshake(pub, token, clientPriv)
        assertThrows(BadPaddingException::class.java) {
            clientHs2.complete(bad)
        }
    }
}
