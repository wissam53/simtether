package com.simtether.shared

import com.simtether.shared.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Bubble-state transitions — no init(), so the store is pure
 * in-memory (rewrite() no-ops without a file).
 */
class ConversationStoreTest {

    private fun sms(address: String) =
        Protocol.SmsReceived(address = address, body = "otp 123456", timestamp = 1L)

    @Test
    fun `incoming sms lands in its thread`() {
        ConversationStore.onIncoming(sms("+90555-x1"))
        val thread = ConversationStore.thread("+90555-x1")
        assertEquals("otp 123456", thread.last().body)
        assertFalse(thread.last().outgoing)
    }

    @Test
    fun `outgoing ref correlates the status update`() {
        val ref = ConversationStore.onOutgoing("+90555-x2", "reply")
        val before = ConversationStore.thread("+90555-x2").last()
        assertEquals("sending", before.status)

        ConversationStore.onStatus(
            Protocol.SmsStatus(ref, Protocol.SmsStatus.Status.SENT))
        assertEquals("sent", ConversationStore.thread("+90555-x2").last().status)
    }

    @Test
    fun `status for an unknown ref is a no-op`() {
        val size = ConversationStore.messages.value.size
        ConversationStore.onStatus(
            Protocol.SmsStatus("no-such-ref", Protocol.SmsStatus.Status.FAILED))
        assertEquals(size, ConversationStore.messages.value.size)
    }

    @Test
    fun `status without ref is ignored`() {
        val size = ConversationStore.messages.value.size
        ConversationStore.onStatus(
            Protocol.SmsStatus(null, Protocol.SmsStatus.Status.SENT))
        assertEquals(size, ConversationStore.messages.value.size)
    }

    @Test
    fun `failed status overwrites sending`() {
        val ref = ConversationStore.onOutgoing("+90555-x3", "hi")
        ConversationStore.onStatus(
            Protocol.SmsStatus(ref, Protocol.SmsStatus.Status.FAILED, "no service"))
        assertEquals("failed", ConversationStore.thread("+90555-x3").last().status)
    }

    @Test
    fun `uninitialised store still records in memory`() {
        // file == null here — rewrite() no-ops, memory still updates.
        val ref = ConversationStore.onOutgoing("+90555-x4", "persistless")
        assertEquals(ref, ConversationStore.thread("+90555-x4").last().ref)
    }
}
