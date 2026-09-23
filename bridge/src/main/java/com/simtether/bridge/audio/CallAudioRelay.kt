package com.simtether.bridge.audio

import android.content.Context

/**
 * Call-audio relay contract — the rooted bridge flavor's contribution.
 *
 * The interface lives in :bridge so call-state code can drive it
 * flavor-agnostically; the implementation lives in bridgeapp's
 * `src/rooted` source set, so root-dependent code never compiles into
 * the Play build at all (the store variant simply leaves the provider
 * empty and every call site no-ops).
 *
 * Audio on the wire is 8kHz mono PCM16 — GSM is narrowband anyway, and
 * ~16KB/s fits comfortably under the relay's media byte cap.
 */
interface CallAudioRelay {
    /**
     * A call went ACTIVE and a capable client is listening. Starts
     * downlink capture + uplink injection; [onPcm] receives captured
     * 20ms frames to ship to the client. Returns true when a capture
     * path actually came up — false means "tell the client there's no
     * audio" rather than a silent dead session.
     */
    fun start(context: Context, onPcm: (ByteArray) -> Unit): Boolean

    /** Client mic audio — inject into the live GSM uplink. */
    fun inject(pcm: ByteArray)

    /** Call ended or the client went away — release everything. */
    fun stop()

    /** Whether this build/device will even try (root present, user enabled). */
    fun available(context: Context): Boolean
}

/** Set once by the app flavor (App.onCreate); null on the store build. */
object AudioRelayProvider {
    @Volatile var relay: CallAudioRelay? = null
}
