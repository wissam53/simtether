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
