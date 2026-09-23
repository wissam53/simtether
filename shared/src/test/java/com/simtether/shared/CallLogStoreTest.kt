package com.simtether.shared

import com.simtether.shared.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The missed-call derivation is the bit that must be right: it folds
 * a call's whole event sequence into one entry. No init() — without a
 * file the store is pure in-memory.
 */
class CallLogStoreTest {

    private fun ev(id: String, state: Protocol.CallEvent.State, incoming: Boolean) =
        Protocol.CallEvent(callId = id, state = state, number = "+900", incoming = incoming)

    private fun entry(id: String) =
        CallLogStore.entries.value.last { it.callId == id }

    @Test
    fun `ringing then disconnect is a missed call`() {
        val id = "t-missed"
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.RINGING, incoming = true))
        val e = CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        assertTrue(e!!.missed)
        assertTrue(entry(id).missed)
    }

    @Test
    fun `answered call is not missed`() {
        val id = "t-answered"
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.RINGING, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.ACTIVE, incoming = true))
        val e = CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        assertTrue(e!!.answered)
        assertFalse(e.missed)
    }

    @Test
    fun `outgoing call is never missed`() {
        val id = "t-outgoing"
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DIALING, incoming = false))
        val e = CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = false))
        assertFalse(e!!.incoming)
        assertFalse(e.missed)
    }

    @Test
    fun `states fold into one entry per callId`() {
        val id = "t-fold"
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.RINGING, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.ACTIVE, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        assertEquals(1, CallLogStore.entries.value.count { it.callId == id })
    }

    @Test
    fun `hold events are filtered before the log`() {
        val id = "t-hold"
        // HOLDING is filtered by onEvent — it never reaches append, so
        // a RINGING→DISCONNECTED call still counts as missed.
        assertNull(CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.HOLDING, incoming = true)))
        assertTrue(CallLogStore.entries.value.none { it.callId == id })
    }

    @Test
    fun `missed call is unseen until Recents is viewed`() {
        val id = "t-unseen"
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.RINGING, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        assertFalse(entry(id).seen)
        CallLogStore.markAllSeen()
        assertTrue(entry(id).seen)
    }

    @Test
    fun `answered and outgoing calls are never unseen`() {
        val id = "t-seen"
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.RINGING, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.ACTIVE, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        assertTrue(entry(id).seen)
        val out = "t-seen-out"
        CallLogStore.onEvent(ev(out, Protocol.CallEvent.State.DIALING, incoming = false))
        CallLogStore.onEvent(ev(out, Protocol.CallEvent.State.DISCONNECTED, incoming = false))
        assertTrue(entry(out).seen)
    }

    @Test
    fun `viewed missed call stays seen on a duplicate event`() {
        val id = "t-dupmissed"
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.RINGING, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        CallLogStore.markAllSeen()
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        assertTrue(entry(id).seen)
    }

    @Test
    fun `disconnected-first event still counts as missed`() {
        // Client was offline for RINGING — direction comes from the
        // event's own incoming flag, not from having seen RINGING.
        val id = "t-latemissed"
        val e = CallLogStore.onEvent(
            ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        assertTrue(e!!.missed)
        assertFalse(e.seen)
    }

    @Test
    fun `answered via ACTIVE survives a later hold`() {
        val id = "t-anshold"
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.RINGING, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.ACTIVE, incoming = true))
        CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.HOLDING, incoming = true))
        val e = CallLogStore.onEvent(ev(id, Protocol.CallEvent.State.DISCONNECTED, incoming = true))
        assertTrue(e!!.answered)
        assertFalse(e.missed)
    }
}
