package com.simtether

import android.content.Context
import com.simtether.bridge.audio.AudioRelayProvider
import com.simtether.rooted.RootedAudioRelay
import com.simtether.rooted.RootShell

/**
 * Rooted (GitHub) flavor: wires the call-audio relay into :bridge and
 * exposes root state to the UI. Keep the API surface in sync with
 * src/store/java/com/simtether/RootFeatures.kt — main-source call
 * call sites compile against whichever variant the flavor provides.
 */
object RootFeatures {
    const val HAS_ROOT_FEATURES = true
    private const val PREF_AUDIO_RELAY = "audio_relay_enabled"

    /** Last probe result — `su` execution can block for seconds (and
     *  may surface Magisk's grant dialog), so nothing may wait on it. */
    @Volatile private var rootOk = false
    @Volatile private var probing = false

    /** Called once from App.onCreate — installs the relay impl and
     *  warms the root cache on a worker so the first call's
     *  available() check hits a settled answer. */
    fun install(context: Context) {
        AudioRelayProvider.relay = RootedAudioRelay()
        probeAsync {}
    }

    /** Non-blocking: the last known probe answer. False until the
     *  warm/first probe lands — a call that races it simply retries
     *  on the next call-state emit. */
    fun rootAvailable() = rootOk

    /**
     * Probe `su` off-thread; [done] fires with the result on the
     * probe thread (Compose state may be set from there — mutableStateOf
     * writes are thread-safe). The UI retry path uses this so the
     * Magisk grant dialog never holds a frame.
     */
    fun probeAsync(done: (Boolean) -> Unit) {
        if (probing) { done(rootOk); return }
        probing = true
        Thread({
            rootOk = RootShell.probe()
            probing = false
            done(rootOk)
        }, "su-probe").also { it.isDaemon = true }.start()
    }

    /** Re-probe — e.g. after the user grants root in Magisk. */
    fun recheckRoot() = probeAsync {}

    /** User setting — default ON: anyone who installed this build did it for this. */
    fun audioRelayEnabled(context: Context): Boolean =
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getBoolean(PREF_AUDIO_RELAY, true)

    fun setAudioRelayEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_AUDIO_RELAY, on).apply()
        if (!on) AudioRelayProvider.relay?.stop()
    }
}
