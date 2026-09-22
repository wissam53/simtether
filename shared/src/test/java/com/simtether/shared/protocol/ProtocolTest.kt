package com.simtether.shared.protocol

import org.junit.Assert.assertEquals
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
}
