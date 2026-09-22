package com.simtether.shared

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing-token rotation state machine. The QR credential is a bearer
 * token, so it rotates every session — but a link that dies
 * mid-rotation must never brick the pairing. Both the current token
 * and an in-flight "pending" token authenticate; pending is promoted
 * when the client acks the rotate event OR authenticates with it.
 * The window is persisted via [persist] so a restarted bridge keeps
 * accepting whichever token the client holds.
 */
class TokenRotator(
    current: ByteArray,
    pending: ByteArray? = null,
    private val persist: (current: ByteArray, pending: ByteArray?) -> Unit = { _, _ -> },
) {
    @Volatile var current: ByteArray = current
        private set
    @Volatile var pending: ByteArray? = pending
        private set

    /** Handshake gate — constant-time compare on both slots. */
    @Synchronized
    fun accept(t: ByteArray): Boolean {
        if (MessageDigest.isEqual(t, current)) return true
        val p = pending
        if (p != null && MessageDigest.isEqual(t, p)) {
            // The client rotated in — promote now; a lost ack can't
            // strand it behind a token we no longer accept.
            promote(p)
            return true
        }
        return false
    }

    /**
     * Issue the token to ship inside the session (as a reliable
     * pairing.rotate event). Null while a rotation is already in
     * flight — two pending tokens would let a stale in-flight event
     * overwrite the client's credential with one we no longer accept.
     */
    @Synchronized
    fun rotate(): ByteArray? {
        if (pending != null) return null
        val next = ByteArray(16).also { SecureRandom().nextBytes(it) }
        pending = next
        persist(current, next)
        return next
    }

    /** The client acked the rotate event — retire the old token. */
    @Synchronized
    fun onAck() {
        pending?.let { promote(it) }
    }

    private fun promote(t: ByteArray) {
        current = t
        pending = null
        persist(t, null)
    }
}
