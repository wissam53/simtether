package com.simtether.shared

import com.simtether.shared.protocol.Protocol

/**
 * Aggregates per-part SmsManager result broadcasts into one logical
 * send status. A multipart SMS fires one result per part but the
 * client wants a single bubble state: SENT only once every part sent,
 * FAILED on the first part failure, DELIVERED once every part
 * confirmed delivered. Unregistered refs (process restart mid-send)
 * report through verbatim, one result each.
 */
object SendStatusTracker {

    data class Report(val status: Protocol.SmsStatus.Status, val error: String?)

    private class State(
        val parts: Int,
        var sent: Int = 0,
        var delivered: Int = 0,
        var failed: Boolean = false,
    )

    private val states = object : LinkedHashMap<String, State>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>?) =
            size > 512
    }

    /** Call before send* so result receivers know the part count. */
    @Synchronized
    fun expect(ref: String?, parts: Int) {
        if (ref != null) states[ref] = State(parts.coerceAtLeast(1))
    }

    /** A SENT result for one part. Null = keep waiting for more parts. */
    fun onSent(ref: String?, ok: Boolean, error: String?): Report? =
        onResult(ref, ok, error, isSent = true)

    /** A DELIVERED result for one part. Null = keep waiting. */
    fun onDelivered(ref: String?, ok: Boolean, error: String?): Report? =
        onResult(ref, ok, error, isSent = false)

    @Synchronized
    private fun onResult(ref: String?, ok: Boolean, error: String?, isSent: Boolean): Report? {
        val st = ref?.let { states[it] }
            ?: return Report(
                if (ok) if (isSent) Protocol.SmsStatus.Status.SENT
                        else Protocol.SmsStatus.Status.DELIVERED
                else Protocol.SmsStatus.Status.FAILED,
                error,
            )
        if (st.failed) return null
        if (!ok) {
            // Tombstone — a surviving part's late result must not
            // resurrect the bubble back to SENT.
            st.failed = true
            return Report(Protocol.SmsStatus.Status.FAILED, error)
        }
        if (isSent) {
            st.sent++
            if (st.sent < st.parts) return null
            // Keep the entry — DELIVERED results may still arrive. If
            // none were requested the entry just evicts by age.
            return Report(Protocol.SmsStatus.Status.SENT, null)
        }
        st.delivered++
        if (st.delivered < st.parts) return null
        states.remove(ref)
        return Report(Protocol.SmsStatus.Status.DELIVERED, null)
    }
}
