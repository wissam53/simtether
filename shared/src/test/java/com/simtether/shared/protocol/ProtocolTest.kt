package com.simtether.shared.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun `envelope carries protocol version on the wire`() {
        val env = Protocol.Envelope("id-1", "sms.received", 7, "{}")
        val decoded = Protocol.decode(Protocol.encode(env))
        assertEquals(Protocol.PROTOCOL_VERSION, decoded.pv)
        assertEquals("id-1", decoded.id)
    }

    @Test
    fun `envelope missing pv decodes as current version`() {
        // A peer built before pv existed still parses — the field has
        // a default.
        val raw = """{"id":"x","type":"hb","seq":0,"payload":""}"""
        assertEquals(Protocol.PROTOCOL_VERSION, Protocol.decode(raw).pv)
    }

    @Test
    fun `unknown fields are tolerated for forward compat`() {
        val raw = """{"id":"x","type":"hb","seq":0,"payload":"","pv":99,"future":"field"}"""
        assertEquals(99, Protocol.decode(raw).pv)
    }

    @Test
    fun `ack and rotate payloads roundtrip`() {
        val ack = Protocol.json.decodeFromString(
            Protocol.Ack.serializer(),
            Protocol.json.encodeToString(Protocol.Ack.serializer(), Protocol.Ack("e1")))
        assertEquals("e1", ack.forId)

        val rot = Protocol.json.decodeFromString(
            Protocol.PairingRotate.serializer(),
            Protocol.json.encodeToString(
                Protocol.PairingRotate.serializer(), Protocol.PairingRotate("dG9rZW4=")))
        assertEquals("dG9rZW4=", rot.token)
    }

    // ── isServiceCode: the gate that routes *#/MMI strings to the
    // opt-in USSD path instead of dial/SMS ─────────────────────────

    @Test
    fun `service code accepts carrier MMI shapes`() {
        assertTrue(Protocol.isServiceCode("*123#"))
        assertTrue(Protocol.isServiceCode("*#06#"))
        // Full MMI forward-enable — the shape the opt-in gate exists for.
        assertTrue(Protocol.isServiceCode("**61*00441234567*20#"))
        assertTrue(Protocol.isServiceCode("*#*#4636#*#*"))
    }

    @Test
    fun `service code rejects ordinary dialable numbers`() {
        assertFalse(Protocol.isServiceCode("5551234"))
        assertFalse(Protocol.isServiceCode("+15551234567"))
        assertFalse(Protocol.isServiceCode(""))
    }

    @Test
    fun `service code rejects non-dialpad characters`() {
        assertFalse(Protocol.isServiceCode("*abc#"))
        // '+' can't appear mid-code — this shape fails both gates.
        assertFalse(Protocol.isServiceCode("*21*+15551234567#"))
    }

    @Test
    fun `service code length cap is enforced`() {
        assertTrue(Protocol.isServiceCode("*" + "1".repeat(62) + "#"))   // 64 chars
        assertFalse(Protocol.isServiceCode("*" + "1".repeat(63) + "#"))  // 65 chars
    }
}
