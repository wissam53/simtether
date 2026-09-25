package com.simtether.client

import com.simtether.shared.IdDedup
import com.simtether.shared.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPipelineTest {

    private fun env(id: String, type: String = "sms.received") =
        Protocol.Envelope(id, type, 1, "{}")

    private class Probe(
        val routeImpl: (Protocol.Envelope) -> Unit = {},
    ) {
        val routed = mutableListOf<String>()
        val acked = mutableListOf<String>()
        val errors = mutableListOf<String>()
        fun pipeline() = EventPipeline(
            IdDedup(),
            route = { routed.add(it.id); routeImpl(it) },
            ack = { acked.add(it) },
            onError = { e, _ -> errors.add(e.id) },
        )
    }

    @Test
    fun `fresh event is routed then acked`() {
        val order = mutableListOf<String>()
        EventPipeline(
            IdDedup(),
            route = { order.add("route:${it.id}") },
            ack = { order.add("ack:$it") },
            onError = { _, _ -> },
        ).handle(env("e1"))
        // Ack only after routing — an earlier ack would retire an event
        // that might still fail.
        assertEquals(listOf("route:e1", "ack:e1"), order)
    }

    @Test
    fun `redelivery re-acks without re-routing`() {
        val p = Probe()
        val pl = p.pipeline()
        pl.handle(env("e1"))
        pl.handle(env("e1"))   // bridge resends — our first ack was lost
        assertEquals(listOf("e1"), p.routed)
        assertEquals(listOf("e1", "e1"), p.acked)
    }

    @Test
    fun `failed route is not acked and its dedup mark is dropped`() {
        var fail = true
        val p = Probe(routeImpl = { if (fail) throw RuntimeException("boom") })
        val pl = p.pipeline()
        pl.handle(env("e1"))
        assertEquals(emptyList<String>(), p.acked)
        assertEquals(listOf("e1"), p.errors)
        // The redelivery must process — the mark was rolled back, so
        // this isn't swallowed as a duplicate.
        fail = false
        pl.handle(env("e1"))
        assertEquals(listOf("e1", "e1"), p.routed)
        assertEquals(listOf("e1"), p.acked)
    }

    @Test
    fun `heartbeats are routed but never deduped or acked`() {
        val p = Probe()
        val pl = p.pipeline()
        pl.handle(env("h1", "hb"))
        pl.handle(env("h1", "hb"))   // same id twice still routes
        assertEquals(listOf("h1", "h1"), p.routed)
        assertTrue(p.acked.isEmpty())
    }

    @Test
    fun `a heartbeat failure still sends no ack`() {
        val p = Probe(routeImpl = { throw RuntimeException() })
        p.pipeline().handle(env("h1", "hb"))
        assertTrue(p.acked.isEmpty())
    }
}
