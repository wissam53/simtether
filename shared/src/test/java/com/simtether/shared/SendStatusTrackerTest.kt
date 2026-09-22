package com.simtether.shared

import com.simtether.shared.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendStatusTrackerTest {

    private val SENT = Protocol.SmsStatus.Status.SENT
    private val FAILED = Protocol.SmsStatus.Status.FAILED
    private val DELIVERED = Protocol.SmsStatus.Status.DELIVERED

    @Test
    fun `multipart SENT only after every part reports`() {
        SendStatusTracker.expect("r1", 3)
        assertNull(SendStatusTracker.onSent("r1", true, null))
        assertNull(SendStatusTracker.onSent("r1", true, null))
        assertEquals(SENT, SendStatusTracker.onSent("r1", true, null)?.status)
    }

    @Test
    fun `first part failure reports FAILED once`() {
        SendStatusTracker.expect("r2", 2)
        assertEquals(FAILED, SendStatusTracker.onSent("r2", false, "radio off")?.status)
        // The surviving part's late result must not resurrect the send.
        assertNull(SendStatusTracker.onSent("r2", true, null))
    }

    @Test
    fun `delivered aggregates across parts`() {
        SendStatusTracker.expect("r3", 2)
        SendStatusTracker.onSent("r3", true, null)
        SendStatusTracker.onSent("r3", true, null)
        assertNull(SendStatusTracker.onDelivered("r3", true, null))
        assertEquals(DELIVERED, SendStatusTracker.onDelivered("r3", true, null)?.status)
    }

    @Test
    fun `single part reports immediately`() {
        SendStatusTracker.expect("r4", 1)
        assertEquals(SENT, SendStatusTracker.onSent("r4", true, null)?.status)
    }

    @Test
    fun `unregistered ref reports verbatim`() {
        // Process restart mid-send — no expected state survives.
        assertEquals(SENT, SendStatusTracker.onSent("ghost", true, null)?.status)
        assertEquals(FAILED, SendStatusTracker.onSent("ghost2", false, "x")?.status)
        assertEquals(DELIVERED, SendStatusTracker.onDelivered("ghost3", true, null)?.status)
    }
}
