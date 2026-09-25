package com.simtether.client

import com.simtether.shared.IdDedup
import com.simtether.shared.protocol.Protocol

/**
 * Dedup → process → ack ordering for inbound bridge events.
 *
 * The ack goes out only after routing completes, so an event that
 * kills processing is redelivered on the next session — and its dedup
 * mark is dropped so the redelivery itself isn't swallowed. A
 * duplicate (ack lost in transit) is re-acked but never re-routed.
 * Heartbeats are neither deduped nor acked — they never enter the
 * bridge's pending queue.
 *
 * Extracted from ClientService so the redelivery semantics are
 * unit-testable without an Android runtime.
 */
internal class EventPipeline(
    private val seenIds: IdDedup,
    private val route: (Protocol.Envelope) -> Unit,
    private val ack: (String) -> Unit,
    private val onError: (Protocol.Envelope, Throwable) -> Unit,
) {
    fun handle(env: Protocol.Envelope) {
        if (env.type != "hb" && !seenIds.add(env.id)) {
            ack(env.id)   // redelivery — retire it again, don't re-run
            return
        }
        val ok = runCatching { route(env) }
            .onFailure { onError(env, it) }.isSuccess
        if (env.type == "hb") return
        if (ok) ack(env.id)
        else seenIds.remove(env.id)
    }
}
