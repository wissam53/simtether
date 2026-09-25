package com.simtether.shared.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingPayloadTest {

    private fun b64(n: Int) =
        Base64.getEncoder().encodeToString(ByteArray(n) { it.toByte() })

    private fun json(
        host: String = "192.168.1.10",
        port: Int = 44710,
        key: String = b64(32),
        token: String = b64(16),
        extra: String = "",
    ): String =
        """{"host":"$host","port":$port,"bridgeStaticPubKey":"$key","oneTimeToken":"$token"$extra}"""

    @Test
    fun `minimal payload decodes`() {
        val p = PairingPayload.decode(json())
        assertEquals("192.168.1.10", p.host)
        assertEquals(44710, p.port)
        assertNull(p.relay)
    }

    @Test
    fun `encode then decode roundtrips all fields`() {
        val p = PairingPayload(
            host = "10.0.0.2", port = 44710,
            bridgeStaticPubKey = b64(32), pairingToken = b64(16),
            deviceName = "kitchen bridge",
            relay = "relay.example.com:443", relayToken = "tok",
            relaySecret = b64(32),
        )
        assertEquals(p, PairingPayload.decode(p.encode()))
    }

    @Test
    fun `wire key is oneTimeToken — stored pairings keep parsing`() {
        // The field was renamed pairingToken → oneTimeToken behind a
        // SerialName shim; encode must keep emitting the wire name.
        assertTrue(
            PairingPayload("h", 1, b64(32), b64(16)).encode()
                .contains("\"oneTimeToken\""))
    }

    @Test
    fun `unknown fields are ignored for forward compat`() {
        PairingPayload.decode(json(extra = ""","future": "x""""))
    }

    @Test
    fun `malformed or unbounded fields throw`() {
        // decode() validates so a hostile/malformed QR can't crash the
        // service into a restart loop. SerializationException extends
        // IllegalArgumentException — one assert covers both layers.
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(json(port = 0)) }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(json(port = 65536)) }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(json(host = "")) }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(json(key = b64(16))) }          // wrong size
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(json(key = "%%%not-base64%%%")) }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(json(token = b64(4))) }         // too short
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(json(token = b64(65))) }        // too long
    }

    @Test
    fun `relay secret must be exactly 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(json(extra = ""","relaySecret":"${b64(16)}"""")) }
        val p = PairingPayload.decode(
            json(extra = ""","relaySecret":"${b64(32)}""""))
        assertEquals(b64(32), p.relaySecret)
    }

    @Test
    fun `oversized deviceName is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.decode(
                json(extra = ""","deviceName":"${"x".repeat(101)}"""")) }
    }
}
