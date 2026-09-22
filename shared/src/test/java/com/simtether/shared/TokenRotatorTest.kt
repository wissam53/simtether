package com.simtether.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRotatorTest {

    private fun tok(seed: Int) = ByteArray(16) { (seed + it).toByte() }

    @Test
    fun `current token authenticates, random does not`() {
        val r = TokenRotator(tok(0))
        assertTrue(r.accept(tok(0)))
        assertFalse(r.accept(tok(99)))
    }

    @Test
    fun `rotate issues pending and ack promotes it`() {
        val r = TokenRotator(tok(0))
        val next = r.rotate()
        assertNotNull(next)
        // Both authenticate during the window.
        assertTrue(r.accept(tok(0)))
        r.onAck()
        assertArrayEquals(next, r.current)
        assertNull(r.pending)
        // Old token is retired once the client durably has the new one.
        assertFalse(r.accept(tok(0)))
        assertTrue(r.accept(next!!))
    }

    @Test
    fun `authenticating with pending promotes without ack`() {
        val r = TokenRotator(tok(0))
        val next = r.rotate()!!
        assertTrue(r.accept(next))
        // Promoted on use — a lost ack can't strand the client.
        assertArrayEquals(next, r.current)
        assertFalse(r.accept(tok(0)))
    }

    @Test
    fun `only one rotation in flight`() {
        val r = TokenRotator(tok(0))
        val first = r.rotate()
        assertNull(r.rotate())  // second rotate refused while pending
        r.onAck()
        assertNotNull(r.rotate())
        assertFalse(java.util.Arrays.equals(first, r.pending))
    }

    @Test
    fun `restored pending token still authenticates after restart`() {
        // Persisted pending survives process death — the client may
        // hold it even though we never saw the ack.
        val persisted = tok(42)
        val r = TokenRotator(tok(0), pending = persisted)
        assertTrue(r.accept(tok(0)))       // current still valid after restart
        assertTrue(r.accept(persisted))    // pending valid → promotes on use
        assertFalse(r.accept(tok(0)))      // promotion retires the old token
    }

    @Test
    fun `persist callback sees both slots`() {
        val writes = mutableListOf<Pair<ByteArray, ByteArray?>>()
        val r = TokenRotator(tok(0)) { cur, pend -> writes.add(cur to pend) }
        val next = r.rotate()!!
        r.onAck()
        assertArrayEquals(next, writes[0].second)
        assertArrayEquals(next, writes[1].first)
        assertNull(writes[1].second)
    }
}
